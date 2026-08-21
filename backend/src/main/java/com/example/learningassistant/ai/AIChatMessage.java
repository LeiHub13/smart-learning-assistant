package com.example.learningassistant.ai;

/**
 * 对话消息：role = system / user / assistant。
 */
public record AIChatMessage(String role, String content) {
}
