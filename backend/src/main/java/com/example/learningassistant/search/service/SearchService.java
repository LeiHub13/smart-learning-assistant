package com.example.learningassistant.search.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.ai.EmbeddingService;
import com.example.learningassistant.chat.entity.ChatSession;
import com.example.learningassistant.infra.vector.VectorStore;
import com.example.learningassistant.kb.entity.Chunk;
import com.example.learningassistant.kb.entity.Document;
import com.example.learningassistant.kb.entity.KnowledgeBase;
import com.example.learningassistant.kb.mapper.ChunkMapper;
import com.example.learningassistant.kb.mapper.DocumentMapper;
import com.example.learningassistant.kb.mapper.KnowledgeBaseMapper;
import com.example.learningassistant.note.entity.Note;
import com.example.learningassistant.note.mapper.NoteMapper;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局搜索：一次查询聚合四类内容（题目/笔记/考试/知识库文档片段）。
 * 文本类走 SQL LIKE；文档片段走向量语义检索（失败降级 LIKE），并按课程过滤。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final QuestionMapper questionMapper;
    private final NoteMapper noteMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final com.example.learningassistant.exam.mapper.ExamMapper examMapper;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;

    public Map<String, Object> search(Long userId, String q, Long courseId) {
        String keyword = q == null ? "" : q.trim();
        if (keyword.isEmpty()) {
            throw new com.example.learningassistant.common.BizException("搜索词不能为空");
        }
        String like = "%" + keyword + "%";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", keyword);
        result.put("questions", searchQuestions(like, courseId));
        result.put("notes", searchNotes(userId, like, courseId));
        result.put("exams", searchExams(like, courseId));
        result.put("docs", searchDocs(keyword, courseId));
        return result;
    }

    private List<Map<String, Object>> searchQuestions(String like, Long courseId) {
        LambdaQueryWrapper<Question> w = new LambdaQueryWrapper<Question>()
                .and(x -> x.like(Question::getStem, like).or().like(Question::getKpName, like));
        if (courseId != null) {
            w.eq(Question::getCourseId, courseId);
        }
        w.last("LIMIT 5");
        return questionMapper.selectList(w).stream().map(q -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.getId());
            m.put("courseId", q.getCourseId());
            m.put("type", q.getType());
            m.put("stem", truncate(q.getStem(), 120));
            m.put("kpName", q.getKpName());
            return m;
        }).toList();
    }

    private List<Map<String, Object>> searchNotes(Long userId, String like, Long courseId) {
        LambdaQueryWrapper<Note> w = new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId)
                .and(x -> x.like(Note::getTitle, like).or().like(Note::getContent, like));
        if (courseId != null) {
            w.eq(Note::getCourseId, courseId);
        }
        w.last("LIMIT 5");
        return noteMapper.selectList(w).stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("courseId", n.getCourseId());
            m.put("title", n.getTitle());
            m.put("content", truncate(n.getContent(), 100));
            m.put("kpName", n.getKpName());
            return m;
        }).toList();
    }

    private List<Map<String, Object>> searchExams(String like, Long courseId) {
        LambdaQueryWrapper<com.example.learningassistant.exam.entity.Exam> w =
                new LambdaQueryWrapper<com.example.learningassistant.exam.entity.Exam>()
                        .like(com.example.learningassistant.exam.entity.Exam::getTitle, like);
        if (courseId != null) {
            w.eq(com.example.learningassistant.exam.entity.Exam::getCourseId, courseId);
        }
        w.last("LIMIT 3");
        return examMapper.selectList(w).stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("courseId", e.getCourseId());
            m.put("title", e.getTitle());
            m.put("totalScore", e.getTotalScore());
            return m;
        }).toList();
    }

    /**
     * 文档片段：优先向量语义检索（含跨 chunk 关键词兜底），再按课程过滤。
     */
    private List<Map<String, Object>> searchDocs(String keyword, Long courseId) {
        List<Chunk> chunks = new ArrayList<>();
        try {
            List<VectorStore.ScoredId> hits = vectorStore.search(embeddingService.embed(keyword), 10);
            List<Long> ids = hits.stream().map(VectorStore.ScoredId::id).toList();
            if (!ids.isEmpty()) {
                chunks = chunkMapper.selectBatchIds(ids);
            }
        } catch (Exception e) {
            log.warn("向量搜索失败，降级 SQL LIKE: {}", e.getMessage());
        }
        if (chunks.isEmpty()) {
            chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                    .like(Chunk::getContent, "%" + keyword + "%")
                    .last("LIMIT 5"));
        }

        // chunk → kb → course 过滤 + 文档名映射
        List<Long> kbIds = chunks.stream().map(Chunk::getKbId).distinct().toList();
        Map<Long, Long> kbCourse = new HashMap<>();
        Map<Long, String> kbName = new HashMap<>();
        if (!kbIds.isEmpty()) {
            for (KnowledgeBase kb : kbMapper.selectBatchIds(kbIds)) {
                kbCourse.put(kb.getId(), kb.getCourseId());
                kbName.put(kb.getId(), kb.getName());
            }
        }
        List<Chunk> filtered = courseId == null ? chunks
                : chunks.stream().filter(c -> courseId.equals(kbCourse.get(c.getKbId()))).limit(5).toList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Chunk c : filtered) {
            Document doc = documentMapper.selectById(c.getDocId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", c.getId());
            m.put("kbName", kbName.getOrDefault(c.getKbId(), ""));
            m.put("docName", doc != null ? doc.getFileName() : "");
            m.put("content", truncate(c.getContent(), 160));
            result.add(m);
            if (result.size() >= 5) {
                break;
            }
        }
        return result;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
