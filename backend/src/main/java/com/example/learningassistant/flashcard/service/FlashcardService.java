package com.example.learningassistant.flashcard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.ai.AIChatMessage;
import com.example.learningassistant.ai.ChatModel;
import com.example.learningassistant.ai.ChatModelFactory;
import com.example.learningassistant.chat.entity.ChatMessage;
import com.example.learningassistant.chat.service.ChatService;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.flashcard.entity.Flashcard;
import com.example.learningassistant.flashcard.mapper.FlashcardMapper;
import com.example.learningassistant.kb.entity.Chunk;
import com.example.learningassistant.kb.entity.Document;
import com.example.learningassistant.kb.mapper.ChunkMapper;
import com.example.learningassistant.kb.mapper.DocumentMapper;
import com.example.learningassistant.practice.entity.Question;
import com.example.learningassistant.practice.mapper.QuestionMapper;
import com.example.learningassistant.practice.service.MistakeService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 闪卡服务：三个生成入口（答疑会话 LLM 提炼 / 文档 LLM 提炼 / 错题直转）、
 * Leitner 盒式学习队列（答对升盒、答错回 1）与 Anki 导出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlashcardService {

    private static final int MAX_CARDS_PER_GEN = 10;
    private static final int MAX_MATERIAL_CHARS = 4000;
    /** Leitner 盒 → 答对后的下次间隔（天）：盒越高记得越牢，间隔越长。 */
    private static final int[] BOX_INTERVAL_DAYS = {1, 2, 4, 7, 15};
    /** 没记住回盒 1 后的短间隔（分钟）：当天内重现巩固。 */
    private static final int MISS_DELAY_MINUTES = 10;

    private final FlashcardMapper flashcardMapper;
    private final ChatModelFactory modelFactory;
    private final ObjectMapper objectMapper;
    private final ChatService chatService;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final QuestionMapper questionMapper;
    private final MistakeService mistakeService;

    /** 从答疑会话提炼闪卡：取最近对话轮次交给 LLM 提炼问对。 */
    public int generateFromChat(Long userId, Long sessionId) {
        List<ChatMessage> msgs = chatService.messages(sessionId, userId);
        if (msgs.size() < 2) {
            throw new BizException("会话内容太少，先聊几个来回再生成闪卡");
        }
        StringBuilder material = new StringBuilder();
        int taken = 0;
        for (int i = msgs.size() - 1; i >= 0 && taken < 12; i--) {
            ChatMessage m = msgs.get(i);
            String role = "user".equals(m.getRole()) ? "学生" : "AI";
            material.insert(0, role + "：" + clip(m.getContent(), 600) + "\n");
            taken++;
        }
        return generateByLlm(userId, sessionId, "chat", material.toString());
    }

    /** 从文档提炼闪卡：取文档全文（截断）交给 LLM。 */
    public int generateFromDoc(Long userId, Long docId) {
        Document doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new BizException("文档不存在");
        }
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocId, docId)
                .orderByAsc(Chunk::getIdx));
        StringBuilder sb = new StringBuilder();
        for (Chunk c : chunks) {
            if (sb.length() >= MAX_MATERIAL_CHARS) {
                break;
            }
            sb.append(c.getContent()).append('\n');
        }
        String text = sb.length() > MAX_MATERIAL_CHARS ? sb.substring(0, MAX_MATERIAL_CHARS) + "…（后文截断）" : sb.toString().trim();
        if (text.isBlank()) {
            throw new BizException("该文档没有可提炼的内容");
        }
        return generateByLlm(userId, docId, "doc", "【资料标题】" + clip(doc.getFileName(), 100) + "\n【资料文本】\n" + text);
    }

    /** 从错题直转闪卡：错题的题干/答案/解析即现成问对，无需 LLM。 */
    public int generateFromMistakes(Long userId) {
        Map<String, Object> page = mistakeService.page(userId, null, 1, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) page.get("records");
        if (records == null || records.isEmpty()) {
            throw new BizException("错题本是空的，没有可生成的闪卡");
        }
        int created = 0;
        for (Map<String, Object> r : records) {
            Question q = (Question) r.get("question");
            if (q == null || q.getStem() == null || q.getAnswer() == null) {
                continue;
            }
            String front = clip("【" + (q.getKpName() == null ? "错题" : q.getKpName()) + "】" + q.getStem(), 500);
            String back = clip(q.getAnswer()
                    + (q.getAnalysis() == null || q.getAnalysis().isBlank() ? "" : "\n解析：" + q.getAnalysis()), 800);
            if (createCard(userId, q.getCourseId(), "mistake", q.getId(), front, back)) {
                created++;
            }
            if (created >= MAX_CARDS_PER_GEN) {
                break;
            }
        }
        if (created == 0) {
            throw new BizException("没有可新增的闪卡（可能都已生成过）");
        }
        return created;
    }

    /**
     * Agent 确认执行入口：单卡直建（同用户同正面去重），来源记为 chat、sourceId 记答疑会话。
     * 返回是否真正新建（false = 已存在相同正面的卡片，确认动作按幂等处理不报错）。
     */
    public boolean createFromAgent(Long userId, Long courseId, Long sessionId, String front, String back) {
        return createCard(userId, courseId, "chat", sessionId, front, back);
    }

    /** 错题直转单卡（收录错题时联动生成）：来源 mistake、sourceId 记题目 id，立即到期。 */
    public boolean createFromMistake(Long userId, Long courseId, Long questionId, String front, String back) {
        return createCard(userId, courseId, "mistake", questionId, front, back);
    }

    /** 学习队列：到期卡优先（SRS），未到期的按到期时间就近垫底；盒小（薄弱）再优先。 */
    public List<Flashcard> study(Long userId, Long courseId, int count) {
        int c = Math.min(Math.max(count, 1), 30);
        LambdaQueryWrapper<Flashcard> w = new LambdaQueryWrapper<Flashcard>()
                .eq(Flashcard::getUserId, userId)
                .eq(courseId != null, Flashcard::getCourseId, courseId)
                .last("ORDER BY (due_at IS NULL OR due_at <= NOW()) DESC, due_at ASC, box ASC, "
                        + "last_reviewed_at ASC LIMIT " + c);
        return flashcardMapper.selectList(w);
    }

    /** 自评：记住升盒（最多 5）并按盒序拉长下次间隔；没记住回盒 1，10 分钟后重现。 */
    public Flashcard review(Long userId, Long cardId, String result) {
        Flashcard card = flashcardMapper.selectById(cardId);
        if (card == null || !card.getUserId().equals(userId)) {
            throw new BizException("卡片不存在");
        }
        boolean ok = "ok".equals(result);
        LocalDateTime now = LocalDateTime.now();
        int newBox = ok ? Math.min((card.getBox() == null ? 1 : card.getBox()) + 1, 5) : 1;
        card.setBox(newBox);
        card.setLastResult(ok ? "ok" : "miss");
        card.setLastReviewedAt(now);
        card.setDueAt(ok ? now.plusDays(BOX_INTERVAL_DAYS[newBox - 1]) : now.plusMinutes(MISS_DELAY_MINUTES));
        flashcardMapper.updateById(card);
        return card;
    }

    /** 今日到期卡数（due_at 为空视为立即到期），供首页与闪卡页展示复习义务。 */
    public long dueCount(Long userId, Long courseId) {
        return flashcardMapper.selectCount(new LambdaQueryWrapper<Flashcard>()
                .eq(Flashcard::getUserId, userId)
                .eq(courseId != null, Flashcard::getCourseId, courseId)
                .and(w -> w.le(Flashcard::getDueAt, LocalDateTime.now())
                        .or().isNull(Flashcard::getDueAt)));
    }

    /** Anki 导出：制表符分隔的纯文本（front<TAB>back），换行转 <br>，可直接被 Anki 导入。 */
    public String exportText(Long userId) {
        List<Flashcard> cards = flashcardMapper.selectList(new LambdaQueryWrapper<Flashcard>()
                .eq(Flashcard::getUserId, userId)
                .orderByAsc(Flashcard::getBox)
                .orderByDesc(Flashcard::getCreatedAt));
        StringBuilder sb = new StringBuilder("#separator:tab\n#html:true\n");
        for (Flashcard c : cards) {
            String front = (c.getFront() == null ? "" : c.getFront()).replace("\n", "<br>").replace("\t", " ");
            String back = (c.getBack() == null ? "" : c.getBack()).replace("\n", "<br>").replace("\t", " ");
            sb.append(front).append('\t').append(back).append('\n');
        }
        return sb.toString();
    }

    /** 删除单张卡片（归属校验）。 */
    public void delete(Long userId, Long cardId) {
        Flashcard card = flashcardMapper.selectById(cardId);
        if (card == null || !card.getUserId().equals(userId)) {
            throw new BizException("卡片不存在");
        }
        flashcardMapper.deleteById(cardId);
    }

    public long count(Long userId) {
        return flashcardMapper.selectCount(new LambdaQueryWrapper<Flashcard>().eq(Flashcard::getUserId, userId));
    }

    /** LLM 提炼：FLASHCARDS 场景输出 [{front, back}]，同用户去重后入库，返回新增张数。 */
    private int generateByLlm(Long userId, Long sourceId, String source, String material) {
        ChatModel model = modelFactory.get();
        String raw = model.complete(List.of(
                new AIChatMessage("system",
                        "FLASHCARDS\n你是学习卡片制作师。阅读材料，提炼 5-10 张问对式闪卡帮助记忆："
                                + "front 是一个具体、可自测的问题（不要是非句），back 是简洁准确的答案（50 字内）。"
                                + "覆盖材料中最重要的概念与关系。只输出 JSON 数组，不要输出任何其他文字："
                                + "[{\"front\":\"问题\",\"back\":\"答案\"}]"),
                new AIChatMessage("user", material)));
        List<Map<String, Object>> parsed;
        try {
            parsed = objectMapper.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("闪卡提炼解析失败: {}", raw);
            throw new BizException("闪卡生成失败，请重试");
        }
        int created = 0;
        for (Map<String, Object> m : parsed) {
            if (created >= MAX_CARDS_PER_GEN) {
                break;
            }
            String front = clip(m.get("front"), 500);
            String back = clip(m.get("back"), 800);
            if (front.isBlank() || back.isBlank()) {
                continue;
            }
            if (createCard(userId, null, source, sourceId, front, back)) {
                created++;
            }
        }
        if (created == 0) {
            throw new BizException("未能提炼出闪卡，请重试");
        }
        return created;
    }

    /** 同一用户下相同正面文本视为重复，跳过；courseId 可空（跨课程来源）。 */
    private boolean createCard(Long userId, Long courseId, String source, Long sourceId, String front, String back) {
        Long exists = flashcardMapper.selectCount(new LambdaQueryWrapper<Flashcard>()
                .eq(Flashcard::getUserId, userId)
                .eq(Flashcard::getFront, front));
        if (exists > 0) {
            return false;
        }
        Flashcard card = new Flashcard();
        card.setTenantId(1L);
        card.setUserId(userId);
        card.setCourseId(courseId);
        card.setSource(source);
        card.setSourceId(sourceId);
        card.setFront(front);
        card.setBack(back);
        card.setBox(1);
        card.setDueAt(LocalDateTime.now());
        card.setCreatedAt(LocalDateTime.now());
        flashcardMapper.insert(card);
        return true;
    }

    private static String clip(Object raw, int max) {
        String s = raw == null ? "" : String.valueOf(raw).trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
