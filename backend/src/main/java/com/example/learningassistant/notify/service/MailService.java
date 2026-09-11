package com.example.learningassistant.notify.service;

import com.example.learningassistant.notify.entity.MailLog;
import com.example.learningassistant.notify.mapper.MailLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 邮件通知渠道：仅在 app.notify.email-enabled=true 且 SMTP 配置完整时装配。
 * 发送失败只告警不抛异常——邮件是站内信的增强渠道，不能阻塞主流程。
 * 每次发送留痕 t_mail_log（成败均记录），供通知中心"邮箱通知"页签展示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notify.email-enabled", havingValue = "true")
public class MailService {

    private final JavaMailSender mailSender;
    private final MailLogMapper mailLogMapper;

    @Value("${spring.mail.username:}")
    private String from;

    public void send(Long userId, String to, String subject, String content) {
        if (to == null || to.isBlank()) {
            return;
        }
        MailLog logRow = new MailLog();
        logRow.setTenantId(1L);
        logRow.setUserId(userId);
        logRow.setEmail(to);
        logRow.setSubject(subject);
        logRow.setContent(content);
        logRow.setCreatedAt(LocalDateTime.now());

        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(content);
            mailSender.send(msg);
            logRow.setStatus("SENT");
            log.info("邮件已发送: to={}, subject={}", to, subject);
        } catch (Exception e) {
            logRow.setStatus("FAILED");
            logRow.setError(e.getMessage());
            log.warn("邮件发送失败（不影响站内信）: to={}, err={}", to, e.getMessage());
        }
        mailLogMapper.insert(logRow);
    }
}
