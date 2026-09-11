package com.example.learningassistant.export.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.exam.entity.Exam;
import com.example.learningassistant.exam.entity.ExamRecord;
import com.example.learningassistant.exam.mapper.ExamMapper;
import com.example.learningassistant.exam.mapper.ExamRecordMapper;
import com.example.learningassistant.practice.entity.Practice;
import com.example.learningassistant.practice.mapper.PracticeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 数据导出：练习记录 / 考试成绩 → Excel（xlsx）。
 * 列头加粗，数值列保留原始类型；文件由 Controller 附下载头返回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final PracticeMapper practiceMapper;
    private final ExamMapper examMapper;
    private final ExamRecordMapper examRecordMapper;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public byte[] practiceWorkbook(Long userId) throws IOException {
        List<Practice> list = practiceMapper.selectList(new LambdaQueryWrapper<Practice>()
                .eq(Practice::getUserId, userId)
                .orderByDesc(Practice::getCreatedAt));
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("练习记录");
            writeHeader(sheet, headStyle(wb), "练习标题", "时间", "得分", "总分", "正确率");
            int r = 1;
            for (Practice p : list) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(p.getTitle() == null ? "" : p.getTitle());
                row.createCell(1).setCellValue(p.getCreatedAt() == null ? "" : p.getCreatedAt().format(DTF));
                row.createCell(2).setCellValue(p.getScore() == null ? 0 : p.getScore());
                row.createCell(3).setCellValue(p.getTotalScore() == null ? 0 : p.getTotalScore());
                row.createCell(4).setCellValue(rate(p.getScore(), p.getTotalScore()));
            }
            autosize(sheet, 5);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    public byte[] examsWorkbook(Long userId) throws IOException {
        List<ExamRecord> records = examRecordMapper.selectList(new LambdaQueryWrapper<ExamRecord>()
                .eq(ExamRecord::getUserId, userId)
                .orderByDesc(ExamRecord::getStartedAt));
        Map<Long, Exam> exams = records.isEmpty() ? Map.of()
                : examMapper.selectBatchIds(records.stream().map(ExamRecord::getExamId).distinct().toList()).stream()
                        .collect(Collectors.toMap(Exam::getId, Function.identity()));
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("考试成绩");
            writeHeader(sheet, headStyle(wb), "考试名称", "开始时间", "交卷时间", "得分", "总分", "正确率");
            int r = 1;
            for (ExamRecord rec : records) {
                Exam exam = exams.get(rec.getExamId());
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(exam != null && exam.getTitle() != null ? exam.getTitle() : "考试" + rec.getExamId());
                row.createCell(1).setCellValue(rec.getStartedAt() == null ? "" : rec.getStartedAt().format(DTF));
                row.createCell(2).setCellValue(rec.getSubmittedAt() == null ? "未交卷" : rec.getSubmittedAt().format(DTF));
                row.createCell(3).setCellValue(rec.getScore() == null ? 0 : rec.getScore());
                row.createCell(4).setCellValue(rec.getTotalScore() == null ? 0 : rec.getTotalScore());
                row.createCell(5).setCellValue(rate(rec.getScore(), rec.getTotalScore()));
            }
            autosize(sheet, 6);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void writeHeader(Sheet sheet, CellStyle style, String... names) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < names.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(names[i]);
            if (style != null) {
                c.setCellStyle(style);
            }
        }
    }

    private CellStyle headStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private String rate(Integer score, Integer total) {
        if (score == null || total == null || total == 0) {
            return "0%";
        }
        return Math.round(score * 1000.0 / total) / 10.0 + "%";
    }

    private void autosize(Sheet sheet, int cols) {
        for (int i = 0; i < cols; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}
