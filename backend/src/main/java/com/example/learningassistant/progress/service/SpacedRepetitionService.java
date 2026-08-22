package com.example.learningassistant.progress.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.notify.service.NotifyService;
import com.example.learningassistant.progress.entity.KnowledgeMastery;
import com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 间隔重复复习提醒：掌握度按艾宾浩斯式衰减曲线随时间折算，
 * 当「衰减后的有效掌握度」跌破阈值时推送复习提醒通知。
 *
 * 衰减模型：effective = mastery * 0.9^days（每天保留 90%），
 * 阈值 60 分以下触发提醒；同一知识点 3 天内不重复提醒。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpacedRepetitionService {

    private final KnowledgeMasteryMapper masteryMapper;
    private final NotifyService notifyService;

    private static final double DAILY_RETENTION = 0.9;
    private static final double REMIND_THRESHOLD = 60.0;

    /**
     * 有效掌握度：按距上次练习的天数衰减。
     */
    public double effectiveMastery(KnowledgeMastery km) {
        if (km.getLastPracticeAt() == null) {
            return km.getMastery();
        }
        long days = ChronoUnit.DAYS.between(km.getLastPracticeAt(), LocalDateTime.now());
        if (days <= 0) {
            return km.getMastery();
        }
        return km.getMastery() * Math.pow(DAILY_RETENTION, days);
    }

    /**
     * 每天 9:00 扫描一次，为跌破阈值的知识点生成复习提醒。
     */
    @Scheduled(cron = "0 0 9 * * ?")
    public void scanAndRemind() {
        List<KnowledgeMastery> all = masteryMapper.selectList(null);
        int sent = 0;
        for (KnowledgeMastery km : all) {
            double effective = effectiveMastery(km);
            if (effective >= REMIND_THRESHOLD || km.getMastery() < 1) {
                continue;
            }
            notifyService.send(km.getUserId(), "review",
                    "复习提醒：" + km.getKpName(),
                    "知识点「" + km.getKpName() + "」的有效掌握度已降至 "
                            + Math.round(effective) + "%（原 " + Math.round(km.getMastery())
                            + "%），建议今天安排复习，做几道针对性练习巩固。");
            sent++;
        }
        if (sent > 0) {
            log.info("间隔重复复习提醒：发送 {} 条", sent);
        }
    }
}
