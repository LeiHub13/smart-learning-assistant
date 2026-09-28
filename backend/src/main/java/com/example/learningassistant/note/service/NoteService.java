package com.example.learningassistant.note.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.note.entity.Note;
import com.example.learningassistant.note.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 学习笔记服务：课程维度 CRUD（归属校验）。
 */
@Service
@RequiredArgsConstructor
public class NoteService {

    private final NoteMapper noteMapper;

    public List<Note> list(Long userId, Long courseId) {
        LambdaQueryWrapper<Note> wrapper = new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId);
        if (courseId != null) {
            wrapper.eq(Note::getCourseId, courseId);
        }
        wrapper.orderByDesc(Note::getUpdatedAt);
        return noteMapper.selectList(wrapper);
    }

    public Note create(Long userId, Long courseId, String kpName, String title, String content) {
        validate(courseId, title, content);
        Note n = new Note();
        n.setTenantId(1L);
        n.setUserId(userId);
        n.setCourseId(courseId);
        n.setKpName(kpName == null || kpName.isBlank() ? null : kpName.trim());
        n.setTitle(title.trim());
        n.setContent(content.trim());
        n.setShared(0);
        n.setCreatedAt(LocalDateTime.now());
        n.setUpdatedAt(LocalDateTime.now());
        noteMapper.insert(n);
        return n;
    }

    public Note update(Long userId, Long id, String kpName, String title, String content) {
        validate(null, title, content);
        Note n = requireOwned(id, userId);
        n.setKpName(kpName == null || kpName.isBlank() ? null : kpName.trim());
        n.setTitle(title.trim());
        n.setContent(content.trim());
        n.setUpdatedAt(LocalDateTime.now());
        noteMapper.updateById(n);
        return n;
    }

    public void delete(Long userId, Long id) {
        requireOwned(id, userId);
        noteMapper.deleteById(id);
    }

    /** 共享开关：作者把笔记共享到课程频道学习空间（须已绑定课程），off 恢复私有。 */
    public Note setShared(Long userId, Long id, boolean shared) {
        Note n = requireOwned(id, userId);
        if (shared && n.getCourseId() == null) {
            throw new BizException("未绑定课程的笔记不能共享");
        }
        n.setShared(shared ? 1 : 0);
        n.setUpdatedAt(LocalDateTime.now());
        noteMapper.updateById(n);
        return n;
    }

    private Note requireOwned(Long id, Long userId) {
        Note n = noteMapper.selectById(id);
        if (n == null || !n.getUserId().equals(userId)) {
            throw new BizException("笔记不存在或无权访问");
        }
        return n;
    }

    private void validate(Long courseId, String title, String content) {
        if (title == null || title.isBlank()) {
            throw new BizException("标题不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new BizException("内容不能为空");
        }
        if (title.length() > 100) {
            throw new BizException("标题过长（≤100 字）");
        }
        if (content.length() > 5000) {
            throw new BizException("内容过长（≤5000 字）");
        }
    }
}
