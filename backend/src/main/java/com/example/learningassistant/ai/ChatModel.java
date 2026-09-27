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
     * @param onDone 完成回调载荷见 {@link SourceRefs}
     */
    void stream(List<AIChatMessage> messages, Consumer<String> onDelta, Consumer<SourceRefs> onDone, Consumer<Throwable> onError);

    /**
     * 完成回调载荷：引用编号 + 对齐的 chunkId 列表（前端点引用跳原文用）+ 追问推荐。
     * followups 为 JSON 数组串（如 ["追问1","追问2"]），由前端渲染可点问的追问 chips；
     * 不支持该能力的适配器（如 openai-compatible）为空串。
     */
    record SourceRefs(String refs, String chunkIds, String followups) {
        public static SourceRefs empty() {
            return new SourceRefs("", "", "");
        }
    }
}
