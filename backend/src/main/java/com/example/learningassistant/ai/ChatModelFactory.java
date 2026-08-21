package com.example.learningassistant.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型工厂：根据配置（app.model.provider）选择当前启用的适配器。
 * 支持三种真实模型接入：python（Python langchain ai-service，支持 Agent 场景）/
 * openai-compatible（手写 HTTP 直连厂商）/ spring-ai（Spring AI 框架，内置 Tool Calling）。
 * 不提供 mock 回退：配置错误或服务不可用时直接抛错，保证线上不静默降级。
 */
@Slf4j
@Component
public class ChatModelFactory {

    private final OpenAiCompatibleChatModel openAi;
    private final PythonAIChatModel python;
    private final SpringAiChatModel springAi;
    private final String provider;
    private final String apiKey;
    private final String modelName;

    public ChatModelFactory(OpenAiCompatibleChatModel openAi,
                            PythonAIChatModel python,
                            SpringAiChatModel springAi,
                            @Value("${app.model.provider:python}") String provider,
                            @Value("${app.model.api-key:}") String apiKey,
                            @Value("${app.model.model-name:gpt-4o-mini}") String modelName) {
        this.openAi = openAi;
        this.python = python;
        this.springAi = springAi;
        this.provider = provider;
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    private ChatModel active;

    public synchronized ChatModel get() {
        if (active == null) {
            active = resolve();
            log.info("当前 LLM 提供方: {} (模型: {})", active.provider(), modelName);
        }
        return active;
    }

    private ChatModel resolve() {
        if ("python".equalsIgnoreCase(provider) || "python-langchain".equalsIgnoreCase(provider)) {
            log.info("LLM 走 Python langchain 服务（ai-service）");
            return python;
        }
        if ("openai-compatible".equalsIgnoreCase(provider)) {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("app.model.provider=openai-compatible 但未配置 app.model.api-key");
            }
            return openAi;
        }
        if ("spring-ai".equalsIgnoreCase(provider)) {
            log.info("LLM 走 Spring AI（OpenAI 兼容协议 + Tool Calling）");
            return springAi;
        }
        throw new IllegalStateException("未知的 app.model.provider: " + provider + "（支持 python / openai-compatible / spring-ai）");
    }
}