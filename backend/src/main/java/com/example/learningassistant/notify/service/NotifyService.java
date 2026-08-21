package com.example.learningassistant.notify.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 通知中心服务（新增模块骨架）。
 *
 * 后续迭代：站内信（WebSocket/轮询）、邮件、WebHook（钉钉/企微）、
 *           模板管理（占位符渲染）、发送限频、失败重试、发送记录。
 */
@Slf4j
@Service
public class NotifyService {

    /**
     * 发送站内信（骨架）。
     */
    public void sendInApp(Long userId, String title, String content) {
        // TODO: t_notice 落库 + WebSocket/轮询推送
        log.info("站内信占位: userId={}, title={}", userId, title);
    }
}
