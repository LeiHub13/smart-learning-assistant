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

    /** 流式补全：增量文本回调 + 完成回调 + 异常回调 */
    void stream(List<AIChatMessage> messages, Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError);
}
