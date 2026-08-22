package com.example.learningassistant.plan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.ai.AIChatMessage;
import com.example.learningassistant.ai.ChatModel;
import com.example.learningassistant.ai.ChatModelFactory;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.plan.entity.PlanTask;
import com.example.learningassistant.plan.entity.StudyPlan;
import com.example.learningassistant.plan.mapper.PlanTaskMapper;
import com.example.learningassistant.plan.mapper.StudyPlanMapper;
import com.example.learningassistant.progress.entity.KnowledgeMastery;
import com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习计划服务：LLM 按目标 + 掌握度生成逐日计划，支持每日打卡。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanService {

    private final StudyPlanMapper planMapper;
    private final PlanTaskMapper taskMapper;
    private final KnowledgeMasteryMapper masteryMapper;
    private final ChatModelFactory modelFactory;
    private final ObjectMapper objectMapper;

    /**
     * 生成学习计划：LLM 输出 JSON 数组 -> 落库 plan + tasks。
     */
    public StudyPlan generate(Long userId, Long courseId, String goal, int days) {
        if (goal == null || goal.isBlank()) {
            throw new BizException("学习目标不能为空");
        }
        if (days < 1 || days > 30) {
            days = 7;
        }
        // 掌握度数据作为 LLM 输入，让计划有针对性
        List<KnowledgeMastery> masteries = masteryMapper.selectList(new LambdaQueryWrapper<KnowledgeMastery>()
                .eq(KnowledgeMastery::getUserId, userId)
                .eq(KnowledgeMastery::getCourseId, courseId));
        StringBuilder md = new StringBuilder();
        for (KnowledgeMastery m : masteries) {
            md.append(m.getKpName()).append(":").append(Math.round(m.getMastery())).append("%; ");
        }

        ChatModel model = modelFactory.get();
        String raw = model.complete(List.of(
                new AIChatMessage("system", "PLAN\n你是学习计划制定专家，只输出 JSON 数组。"),
                new AIChatMessage("user", "学习目标:" + goal + "\n计划天数:" + days
                        + "\n当前掌握度:" + (md.isEmpty() ? "暂无数据" : md)
                        + "\n起始日期:" + LocalDate.now()
                        + "\n请生成逐日学习计划。")));

        StudyPlan plan = new StudyPlan();
        plan.setUserId(userId);
        plan.setCourseId(courseId);
        plan.setGoal(goal);
        plan.setDays(days);
        plan.setStatus("active");
        plan.setCreatedAt(LocalDateTime.now());
        planMapper.insert(plan);

        try {
            List<Map<String, Object>> items = objectMapper.readValue(raw, new TypeReference<>() {
            });
            int dayNo = 1;
            for (Map<String, Object> item : items) {
                PlanTask task = new PlanTask();
                task.setPlanId(plan.getId());
                task.setDayNo(dayNo);
                Object date = item.get("date");
                try {
                    task.setTaskDate(date == null ? LocalDate.now().plusDays(dayNo - 1L)
                            : LocalDate.parse(String.valueOf(date)));
                } catch (Exception e) {
                    task.setTaskDate(LocalDate.now().plusDays(dayNo - 1L));
                }
                task.setTitle(String.valueOf(item.getOrDefault("title", "第 " + dayNo + " 天")));
                Object tasks = item.get("tasks");
                task.setTasks(tasks instanceof List ? String.join("\n", ((List<?>) tasks).stream().map(String::valueOf).toList())
                        : String.valueOf(tasks == null ? "" : tasks));
                task.setFocusKp(String.valueOf(item.getOrDefault("focusKp", "")));
                task.setDone(false);
                taskMapper.insert(task);
                dayNo++;
            }
        } catch (Exception e) {
            log.warn("学习计划解析失败: {}", raw);
            planMapper.deleteById(plan.getId());
            throw new BizException("学习计划生成失败，请重试");
        }
        return plan;
    }

    public List<StudyPlan> list(Long userId) {
        return planMapper.selectList(new LambdaQueryWrapper<StudyPlan>()
                .eq(StudyPlan::getUserId, userId)
                .orderByDesc(StudyPlan::getCreatedAt));
    }

    public Map<String, Object> detail(Long userId, Long planId) {
        StudyPlan plan = planMapper.selectById(planId);
        if (plan == null || !plan.getUserId().equals(userId)) {
            throw new BizException("计划不存在");
        }
        List<PlanTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<PlanTask>()
                .eq(PlanTask::getPlanId, planId)
                .orderByAsc(PlanTask::getDayNo));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan", plan);
        m.put("tasks", tasks);
        m.put("doneCount", tasks.stream().filter(PlanTask::getDone).count());
        return m;
    }

    /**
     * 每日打卡。
     */
    public PlanTask checkIn(Long userId, Long taskId) {
        PlanTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException("任务不存在");
        }
        StudyPlan plan = planMapper.selectById(task.getPlanId());
        if (plan == null || !plan.getUserId().equals(userId)) {
            throw new BizException("无权操作该计划");
        }
        task.setDone(!task.getDone());
        task.setDoneAt(task.getDone() ? LocalDateTime.now() : null);
        taskMapper.updateById(task);

        // 全部完成 -> 计划标记完成
        long undone = taskMapper.selectCount(new LambdaQueryWrapper<PlanTask>()
                .eq(PlanTask::getPlanId, plan.getId())
                .eq(PlanTask::getDone, false));
        if (undone == 0 && !"done".equals(plan.getStatus())) {
            plan.setStatus("done");
            planMapper.updateById(plan);
        }
        return task;
    }

    public void delete(Long userId, Long planId) {
        StudyPlan plan = planMapper.selectById(planId);
        if (plan == null || !plan.getUserId().equals(userId)) {
            throw new BizException("计划不存在");
        }
        taskMapper.delete(new LambdaQueryWrapper<PlanTask>().eq(PlanTask::getPlanId, planId));
        planMapper.deleteById(planId);
    }
}
