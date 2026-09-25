package com.example.learningassistant.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.notify.entity.MailLog;
import com.example.learningassistant.notify.mapper.MailLogMapper;
import com.example.learningassistant.user.entity.EmailCode;
import com.example.learningassistant.user.entity.User;
import com.example.learningassistant.user.mapper.EmailCodeMapper;
import com.example.learningassistant.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 邮箱验证码：注册时验证邮箱归属、重置密码时验证身份。
 * 与通知邮件（MailService，可静默降级）不同——验证码必须真实送达，发送失败直接抛错告知用户；
 * 复用 spring.mail 的 SMTP 配置，成败均留痕 t_mail_log。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCodeService {

    public static final String SCENE_REGISTER = "register";
    public static final String SCENE_RESET_PASSWORD = "reset_password";

    private static final Map<String, String> SCENE_LABELS =
            Map.of(SCENE_REGISTER, "注册账号", SCENE_RESET_PASSWORD, "重置密码");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    /** 单条验证码允许的最大校验失败次数，超过即作废（防爆破） */
    private static final int MAX_FAIL_COUNT = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailCodeMapper emailCodeMapper;
    private final UserMapper userMapper;
    private final MailLogMapper mailLogMapper;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String from;
    @Value("${app.email-code.ttl-seconds:600}")
    private int ttlSeconds;
    @Value("${app.email-code.cooldown-seconds:60}")
    private int cooldownSeconds;

    /** 发送验证码：场景前置校验 → 冷却检查 → 发信（失败即抛，不落库不占冷却） → 作废旧码并落库 */
    public void sendCode(String scene, String email) {
        String label = SCENE_LABELS.get(scene);
        if (label == null) {
            throw new BizException("不支持的验证码场景");
        }
        String mail = normalizedEmail(email);
        List<User> bound = userMapper.selectList(new LambdaQueryWrapper<User>().eq(User::getEmail, mail));
        if (SCENE_REGISTER.equals(scene) && !bound.isEmpty()) {
            throw new BizException("该邮箱已被注册");
        }
        if (SCENE_RESET_PASSWORD.equals(scene) && bound.isEmpty()) {
            throw new BizException("该邮箱未绑定任何账号");
        }
        // 重置密码场景邮箱必然已归属账号：留痕带上 userId，邮件记录才会出现在用户的「邮箱通知」页签
        Long userId = SCENE_RESET_PASSWORD.equals(scene) && !bound.isEmpty() ? bound.get(0).getId() : null;

        EmailCode latest = latestCode(mail, scene, null);
        if (latest != null && latest.getCreatedAt() != null
                && latest.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(cooldownSeconds))) {
            throw new BizException("发送太频繁，请 " + cooldownSeconds + " 秒后再试");
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        sendMail(userId, mail, label, code);

        emailCodeMapper.update(null, new LambdaUpdateWrapper<EmailCode>()
                .set(EmailCode::getUsedFlag, true)
                .eq(EmailCode::getEmail, mail)
                .eq(EmailCode::getScene, scene)
                .eq(EmailCode::getUsedFlag, false));

        EmailCode row = new EmailCode();
        row.setTenantId(1L);
        row.setEmail(mail);
        row.setScene(scene);
        row.setCode(code);
        row.setUsedFlag(false);
        row.setFailCount(0);
        row.setExpiredAt(LocalDateTime.now().plusSeconds(ttlSeconds));
        row.setCreatedAt(LocalDateTime.now());
        emailCodeMapper.insert(row);
    }

    /** 校验验证码：只认最新一条未用码，成功即标记已用；失败计次，防爆破 */
    public void verify(String scene, String email, String code) {
        String mail = normalizedEmail(email);
        if (code == null || code.isBlank()) {
            throw new BizException("请输入邮箱验证码");
        }
        EmailCode latest = latestCode(mail, scene, false);
        if (latest == null || latest.getExpiredAt() == null || latest.getExpiredAt().isBefore(LocalDateTime.now())) {
            throw new BizException("验证码错误或已过期");
        }
        if (latest.getFailCount() != null && latest.getFailCount() >= MAX_FAIL_COUNT) {
            throw new BizException("验证码错误次数过多，请重新获取");
        }
        if (!latest.getCode().equals(code.trim())) {
            emailCodeMapper.update(null, new LambdaUpdateWrapper<EmailCode>()
                    .setSql("fail_count = fail_count + 1")
                    .eq(EmailCode::getId, latest.getId()));
            throw new BizException("验证码错误");
        }
        emailCodeMapper.update(null, new LambdaUpdateWrapper<EmailCode>()
                .set(EmailCode::getUsedFlag, true)
                .eq(EmailCode::getId, latest.getId()));
    }

    private EmailCode latestCode(String email, String scene, Boolean usedFlag) {
        return emailCodeMapper.selectOne(new LambdaQueryWrapper<EmailCode>()
                .eq(EmailCode::getEmail, email)
                .eq(EmailCode::getScene, scene)
                .eq(usedFlag != null, EmailCode::getUsedFlag, usedFlag)
                .orderByDesc(EmailCode::getId)
                .last("LIMIT 1"));
    }

    private String normalizedEmail(String email) {
        if (email == null) {
            throw new BizException("邮箱格式不正确");
        }
        String mail = email.trim();
        if (!EMAIL_PATTERN.matcher(mail).matches()) {
            throw new BizException("邮箱格式不正确");
        }
        return mail;
    }

    private void sendMail(Long userId, String to, String sceneLabel, String code) {
        String content = "你正在进行「" + sceneLabel + "」操作，验证码为：" + code
                + "，" + Math.max(1, ttlSeconds / 60) + " 分钟内有效。请勿泄露给他人，若非本人操作请忽略本邮件。";
        MailLog logRow = new MailLog();
        logRow.setTenantId(1L);
        logRow.setUserId(userId);
        logRow.setEmail(to);
        logRow.setSubject("【智学助手】邮箱验证码");
        logRow.setContent(content);
        logRow.setCreatedAt(LocalDateTime.now());
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject("【智学助手】邮箱验证码");
            msg.setText("你好！\n\n" + content + "\n\n—— 智学助手");
            mailSender.send(msg);
            logRow.setStatus("SENT");
            log.info("验证码邮件已发送: to={}, scene={}", to, sceneLabel);
        } catch (Exception e) {
            logRow.setStatus("FAILED");
            // error 列 VARCHAR(500)，超长的 SMTP 异常信息截断，避免留痕插入本身报错
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            logRow.setError(err.length() > 500 ? err.substring(0, 500) : err);
            log.warn("验证码邮件发送失败: to={}, err={}", to, e.getMessage());
            mailLogMapper.insert(logRow);
            throw new BizException("验证码邮件发送失败，请稍后重试");
        }
        mailLogMapper.insert(logRow);
    }
}
