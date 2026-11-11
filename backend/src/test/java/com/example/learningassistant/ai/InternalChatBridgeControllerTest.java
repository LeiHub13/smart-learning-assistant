package com.example.learningassistant.ai;

import com.example.learningassistant.agent.service.AgentActionService;
import com.example.learningassistant.billing.BillingService;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.course.entity.Course;
import com.example.learningassistant.course.mapper.CourseMapper;
import com.example.learningassistant.course.mapper.CourseUserMapper;
import com.example.learningassistant.kb.entity.KnowledgeBase;
import com.example.learningassistant.kb.mapper.KnowledgeBaseMapper;
import com.example.learningassistant.progress.mapper.KnowledgeMasteryMapper;
import com.example.learningassistant.web.controller.InternalChatBridgeController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InternalChatBridgeControllerTest {
    private final CourseMapper courses = mock(CourseMapper.class);
    private final CourseUserMapper memberships = mock(CourseUserMapper.class);
    private final KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
    private final KnowledgeMasteryMapper mastery = mock(KnowledgeMasteryMapper.class);
    private final AgentActionService actions = mock(AgentActionService.class);
    private final BillingService billing = mock(BillingService.class);
    private InternalChatBridgeController bridge;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        bridge = new InternalChatBridgeController(courses, memberships, knowledgeBases, mastery, actions, billing);
        ReflectionTestUtils.setField(bridge, "internalToken", "test-internal-token");
        request = new MockHttpServletRequest();
        request.addHeader("X-Internal-Token", "test-internal-token");
    }

    @Test
    void requiresInternalTokenBeforeAccessingBusinessData() {
        request.removeHeader("X-Internal-Token");
        assertThrows(BizException.class, () -> bridge.context(request, 1L, 7L, null, null));
        verifyNoInteractions(courses, memberships, knowledgeBases, mastery);
    }

    @Test
    void rejectsCourseAccessByNonMemberAndMismatchedKnowledgeBase() {
        Course course = new Course();
        course.setId(7L);
        course.setOwnerId(1L);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(8L);
        kb.setCourseId(7L);
        when(knowledgeBases.selectById(8L)).thenReturn(kb);
        when(courses.selectById(7L)).thenReturn(course);
        when(memberships.selectCount(any())).thenReturn(0L);
        assertThrows(BizException.class, () -> bridge.context(request, 2L, 7L, 8L, "single"));
        assertThrows(BizException.class, () -> bridge.context(request, 1L, 9L, 8L, "single"));
    }

    @Test
    void ownerGetsOnlyKnowledgeBasesFromTheAuthorizedCourse() {
        Course course = new Course();
        course.setId(7L);
        course.setOwnerId(1L);
        KnowledgeBase selected = new KnowledgeBase();
        selected.setId(8L);
        selected.setCourseId(7L);
        selected.setName("资料");
        KnowledgeBase second = new KnowledgeBase();
        second.setId(9L);
        second.setCourseId(7L);
        when(knowledgeBases.selectById(8L)).thenReturn(selected);
        when(courses.selectById(7L)).thenReturn(course);
        when(memberships.selectCount(any())).thenReturn(0L);
        when(knowledgeBases.selectList(any())).thenReturn(List.of(selected, second));
        when(mastery.selectList(any())).thenReturn(List.of());
        var context = bridge.context(request, 1L, 7L, 8L, "course").getData();
        assertEquals(7L, context.get("courseId"));
        assertEquals("资料", context.get("kbName"));
        assertEquals(List.of(8L, 9L), context.get("kbIds"));
    }
}
