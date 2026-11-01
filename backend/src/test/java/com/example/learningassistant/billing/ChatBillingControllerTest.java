package com.example.learningassistant.billing;

import com.example.learningassistant.agent.service.AgentActionService;
import com.example.learningassistant.chat.service.ChatService;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.common.GlobalExceptionHandler;
import com.example.learningassistant.security.AuthInterceptor;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.web.controller.ChatController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatBillingControllerTest {
    private final ChatService chat = mock(ChatService.class);
    private final AgentActionService actions = mock(AgentActionService.class);
    private final BillingService billing = mock(BillingService.class);
    private MockMvc mvc;
    private final AuthUser user = new AuthUser(42L, "learner", "学生", 1L);

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(chat, actions, billing))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void exhaustedQuotaReturnsReadableJsonBeforeStartingAStream() throws Exception {
        when(billing.reserve(42L, "chat")).thenThrow(new BizException("AI 次数不足，请前往个人中心购买次数包"));
        mvc.perform(post("/api/chat/sessions/9/stream").requestAttr(AuthInterceptor.ATTR_USER, user)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message", containsString("次数不足")));
        verify(chat, never()).streamMessage(anyLong(), anyString(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void streamFailureReleasesReservationAndInformsClient() throws Exception {
        when(billing.reserve(42L, "chat")).thenReturn("reservation");
        doAnswer(invocation -> {
            Consumer<Throwable> onError = invocation.getArgument(6);
            onError.accept(new IllegalStateException("模型不可用"));
            return null;
        }).when(chat).streamMessage(eq(9L), anyString(), eq(42L), eq("reservation"), any(), any(), any());
        MvcResult result = mvc.perform(post("/api/chat/sessions/9/stream").requestAttr(AuthInterceptor.ATTR_USER, user)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult response = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getContentAsString(StandardCharsets.UTF_8), containsString("生成失败"));
        verify(billing, times(1)).release("reservation");
    }
}
