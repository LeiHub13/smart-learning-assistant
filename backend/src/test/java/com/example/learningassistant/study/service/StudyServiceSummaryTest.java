package com.example.learningassistant.study.service;

import com.example.learningassistant.course.mapper.CourseMapper;
import com.example.learningassistant.security.AuthInterceptor;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.study.entity.StudyLog;
import com.example.learningassistant.study.mapper.StudyLogMapper;
import com.example.learningassistant.web.controller.StudyController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StudyServiceSummaryTest {
    @Test
    void defaultAndYearlyCalendarsKeepTheirOwnRangesAndAggregateTheSameDay() {
        StudyLogMapper logs = mock(StudyLogMapper.class);
        StudyLog first = log(LocalDate.now(), 15);
        StudyLog second = log(LocalDate.now(), 20);
        when(logs.selectList(any())).thenReturn(List.of(first, second));
        StudyService service = new StudyService(logs, mock(CourseMapper.class));

        Map<String, Object> defaultSummary = service.summary(7L);
        Map<String, Object> yearlySummary = service.summary(7L, 365);
        List<?> defaultCalendar = (List<?>) defaultSummary.get("calendar");
        List<?> yearlyCalendar = (List<?>) yearlySummary.get("calendar");

        assertEquals(84, defaultCalendar.size());
        assertEquals(365, yearlyCalendar.size());
        assertEquals(LocalDate.now().minusDays(364).toString(), ((Map<?, ?>) yearlyCalendar.get(0)).get("date"));
        assertEquals(35, ((Map<?, ?>) yearlyCalendar.get(364)).get("minutes"));
        assertEquals(1L, yearlySummary.get("activeDays"));
    }

    @Test
    void requestedRangeIsBounded() {
        StudyLogMapper logs = mock(StudyLogMapper.class);
        when(logs.selectList(any())).thenReturn(List.of());
        StudyService service = new StudyService(logs, mock(CourseMapper.class));
        assertEquals(1, ((List<?>) service.summary(7L, 0).get("calendar")).size());
        assertEquals(366, ((List<?>) service.summary(7L, 10000).get("calendar")).size());
    }

    @Test
    void summaryEndpointKeepsDefaultRangeAndAcceptsYearlyRange() throws Exception {
        StudyService service = mock(StudyService.class);
        when(service.summary(eq(7L), anyInt())).thenReturn(Map.of("calendar", List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StudyController(service)).build();
        AuthUser user = new AuthUser(7L, "learner", "学生", 1L);

        mvc.perform(get("/api/study/summary").requestAttr(AuthInterceptor.ATTR_USER, user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/api/study/summary").param("days", "365").requestAttr(AuthInterceptor.ATTR_USER, user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(service).summary(7L, 84);
        verify(service).summary(7L, 365);
    }

    private static StudyLog log(LocalDate date, int minutes) {
        StudyLog log = new StudyLog();
        log.setUserId(7L);
        log.setCourseId(0L);
        log.setStudyDate(date);
        log.setMinutes(minutes);
        return log;
    }
}
