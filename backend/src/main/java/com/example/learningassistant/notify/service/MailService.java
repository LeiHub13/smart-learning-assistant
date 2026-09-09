package com.example.learningassistant.notify.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件通知渠道：仅在 app.notify.email-enabled=true 且 SMTP 配置完整时装配。
 * 发送失败只告警不抛异常——邮件是站内信的增强渠道，不能阻塞主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notify.email-enabled", havingValue = "true")
public class MailService {

    private final JavaMailSender mailSender;

    @org.springframework.beans.factory.annotation.Value("${spring.mail.username:}")
    private String from;

    public void send(String to, String subject, String content) {
        if (to == null || to.isBlank()) {
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(content);
            mailSender.send(msg);
            log.info("邮件已发送: to={}, subject={}", to, subject);
        } catch (Exception e) {
            log.warn("邮件发送失败（不影响站内信）: to={}, err={}", to, e.getMessage());
        }
    }
}
