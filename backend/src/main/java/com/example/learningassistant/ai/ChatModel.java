package com.example.learningassistant.ai;

import java.util.List;
import java.util.function.Consumer;

/**
 * 可插拔的大语言模型适配器接口。
 * 实现类：PythonAIChatModel（Python langchain 服务）、OpenAiCompatibleChatModel（兼容 OpenAI 协议厂商）。
 * 扩展新厂商：实现本接口并在 ChatModelFactory 中注册即可。
 */
public interface ChatModel {

    /** 厂商标识，如 python-langchain / openai-compatible */
    String provider();

    /** 非流式补全 */
    String complete(List<AIChatMessage> messages);

    /**
     * 流式补全：增量文本回调 + 完成回调 + 异常回调。
     *
     * @param onDone 完成回调：refs 为引用来源（RAG 命中的片段编号，如 "1,2,3"；无引用时为空串），
     *               chunkIds 为与编号一一对齐的 chunkId 串（如 "12,15,18"；不支持来源追溯的适配器为空串）
     */
    void stream(List<AIChatMessage> messages, Consumer<String> onDelta, Consumer<SourceRefs> onDone, Consumer<Throwable> onError);

    /** 完成回调载荷：引用编号 + 对齐的 chunkId 列表（前端点引用跳原文用）。 */
    record SourceRefs(String refs, String chunkIds) {
        public static SourceRefs empty() {
            return new SourceRefs("", "");
        }
    }
}
