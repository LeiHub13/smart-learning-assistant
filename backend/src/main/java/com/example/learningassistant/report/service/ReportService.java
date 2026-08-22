package com.example.learningassistant.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.ai.AIChatMessage;
import com.example.learningassistant.ai.ChatModel;
import com.example.learningassistant.ai.ChatModelFactory;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.practice.entity.Practice;
import com.example.learningassistant.practice.entity.PracticeQuestion;
import com.example.learningassistant.practice.mapper.PracticeMapper;
import com.example.learningassistant.practice.mapper.PracticeQuestionMapper;
import com.example.learningassistant.progress.entity.KnowledgeMastery;
import com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper;
import com.example.learningassistant.report.entity.Report;
import com.example.learningassistant.report.mapper.ReportMapper;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学习报告服务：聚合周期学习数据 -> LLM 生成 Markdown 报告 -> 可导出 PDF。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportMapper reportMapper;
    private final PracticeMapper practiceMapper;
    private final PracticeQuestionMapper pqMapper;
    private final KnowledgeMasteryMapper masteryMapper;
    private final ChatModelFactory modelFactory;

    /**
     * 生成周报：聚合近 7 天数据，LLM 生成 Markdown。
     */
    public Report generateWeekly(Long userId, Long courseId) {
        LocalDateTime since = LocalDateTime.now().minusDays(7);

        List<Practice> practices = practiceMapper.selectList(new LambdaQueryWrapper<Practice>()
                .eq(Practice::getUserId, userId)
                .eq(Practice::getCourseId, courseId)
                .ge(Practice::getCreatedAt, since)
                .orderByDesc(Practice::getCreatedAt));

        List<KnowledgeMastery> masteries = masteryMapper.selectList(new LambdaQueryWrapper<KnowledgeMastery>()
                .eq(KnowledgeMastery::getUserId, userId)
                .eq(KnowledgeMastery::getCourseId, courseId)
                .orderByAsc(KnowledgeMastery::getMastery));

        long wrongCount = 0;
        if (!practices.isEmpty()) {
            List<Long> pids = practices.stream().map(Practice::getId).toList();
            wrongCount = pqMapper.selectCount(new LambdaQueryWrapper<PracticeQuestion>()
                    .in(PracticeQuestion::getPracticeId, pids)
                    .eq(PracticeQuestion::getCorrect, false));
        }

        StringBuilder data = new StringBuilder();
        data.append("【近7天练习】").append(practices.size()).append(" 次\n");
        for (Practice p : practices) {
            data.append("- ").append(p.getTitle()).append(" 得分 ").append(p.getScore())
                    .append("/").append(p.getTotalScore()).append("\n");
        }
        data.append("【近7天错题数】").append(wrongCount).append("\n【当前掌握度】\n");
        for (KnowledgeMastery m : masteries) {
            data.append("- ").append(m.getKpName()).append(": ").append(Math.round(m.getMastery()))
                    .append("% (").append(m.getCorrectCount()).append("/").append(m.getAttempts()).append(")\n");
        }

        ChatModel model = modelFactory.get();
        String md = model.complete(List.of(
                new AIChatMessage("system", "REPORT\n你是学习分析师，生成 Markdown 学习报告。"),
                new AIChatMessage("user", data + "\n请生成最近 7 天的学习周报。")));

        Report report = new Report();
        report.setUserId(userId);
        report.setCourseId(courseId);
        report.setPeriod("weekly");
        report.setTitle("学习周报 " + LocalDateTime.now().toLocalDate());
        report.setContent(md);
        report.setCreatedAt(LocalDateTime.now());
        reportMapper.insert(report);
        return report;
    }

    public List<Report> list(Long userId) {
        return reportMapper.selectList(new LambdaQueryWrapper<Report>()
                .eq(Report::getUserId, userId)
                .orderByDesc(Report::getCreatedAt));
    }

    public Report detail(Long userId, Long id) {
        Report r = reportMapper.selectById(id);
        if (r == null || !r.getUserId().equals(userId)) {
            throw new BizException("报告不存在");
        }
        return r;
    }

    /**
     * 导出 PDF：Markdown 纯文本化后排版输出（内嵌中文字体 STSong-Light）。
     */
    public byte[] exportPdf(Long userId, Long id) {
        Report r = detail(userId, id);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Document doc = new Document();
            PdfWriter.getInstance(doc, out);
            doc.open();
            BaseFont bf = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            Font titleFont = new Font(bf, 18, Font.BOLD);
            Font bodyFont = new Font(bf, 11);
            doc.add(new Paragraph(r.getTitle(), titleFont));
            doc.add(new Paragraph("生成时间：" + r.getCreatedAt(), bodyFont));
            doc.add(new Paragraph(" "));
            for (String line : r.getContent().split("\n")) {
                String text = line.replaceAll("^#{1,6}\\s*", "").replaceAll("\\*+", "").trim();
                if (!text.isEmpty()) {
                    doc.add(new Paragraph(text, bodyFont));
                }
            }
            doc.close();
        } catch (DocumentException | java.io.IOException e) {
            throw new BizException("PDF 导出失败: " + e.getMessage());
        }
        return out.toByteArray();
    }
}
