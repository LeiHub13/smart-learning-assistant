package com.example.learningassistant.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 基于 Spring AI 的模型适配器（第三种 provider：spring-ai）。
 *
 * 与 OpenAiCompatibleChatModel（手写 HTTP）的区别：
 * - ChatClient 统一抽象：OpenAI 协议厂商（OpenAI/DeepSeek/通义/智谱）零代码切换；
 * - 内置 Tool Calling：模型可自主调用 LearningTools 中标注 @Tool 的方法，
 *   完成「思考 -> 调工具 -> 观察 -> 回答」的 ReAct 循环（由框架驱动）；
 * - 流式由 WebClient/Reactor 支撑，无需手写 SSE 解析。
 *
 * 复用 app.model.* 配置（与 python / openai-compatible 共用一套参数），
 * ChatClient 懒加载：仅在 provider=spring-ai 被激活时才校验 api-key 并构建。
 */
@Slf4j
@Component
public class SpringAiChatModel implements ChatModel {

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Double temperature;
    private final LearningTools learningTools;

    private volatile ChatClient client;

    public SpringAiChatModel(@Value("${app.model.base-url:https://api.openai.com/v1}") String baseUrl,
                             @Value("${app.model.api-key:}") String apiKey,
                             @Value("${app.model.model-name:gpt-4o-mini}") String modelName,
                             @Value("${app.model.temperature:0.7}") Double temperature,
                             LearningTools learningTools) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.temperature = temperature;
        this.learningTools = learningTools;
    }

    @Override
    public String provider() {
        return "spring-ai";
    }

    @Override
    public String complete(List<AIChatMessage> messages) {
        return client().prompt()
                .messages(toSpringMessages(messages))
                .call()
                .content();
    }

    @Override
    public void stream(List<AIChatMessage> messages, Consumer<String> onDelta, Runnable onDone, Consumer<Throwable> onError) {
        try {
            client().prompt()
                    .messages(toSpringMessages(messages))
                    .stream()
                    .content()
                    .subscribe(onDelta::accept, onError::accept, onDone::run);
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    /** 懒构建：双重检查锁，避免未配置 api-key 时启动期就失败。 */
    private ChatClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalStateException("app.model.provider=spring-ai 但未配置 app.model.api-key");
                    }
                    OpenAiApi api = OpenAiApi.builder()
                            .baseUrl(baseUrl)
                            .apiKey(apiKey)
                            .build();
                    OpenAiChatOptions options = OpenAiChatOptions.builder()
                            .model(modelName)
                            .temperature(temperature)
                            .build();
                    org.springframework.ai.chat.model.ChatModel chatModel = OpenAiChatModel.builder()
                            .openAiApi(api)
                            .defaultOptions(options)
                            .build();
                    client = ChatClient.builder(chatModel)
                            .defaultTools(learningTools)
                            .build();
                    log.info("Spring AI ChatClient 就绪: baseUrl={}, model={}, tools 已注册", baseUrl, modelName);
                }
            }
        }
        return client;
    }

    /** 自研 AIChatMessage -> Spring AI Message 角色映射。 */
    private List<org.springframework.ai.chat.messages.Message> toSpringMessages(List<AIChatMessage> messages) {
        return messages.stream().map(m -> switch (m.role()) {
            case "system" -> new SystemMessage(m.content());
            case "assistant" -> new AssistantMessage(m.content());
            default -> new UserMessage(m.content());
        }).collect(Collectors.toList());
    }

    private static String trimTrailingSlash(String url) {
        return (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
    }
}
