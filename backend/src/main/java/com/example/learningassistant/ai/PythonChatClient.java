package com.example.learningassistant.ai;

import com.example.learningassistant.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Java 消费 Python 答疑域的内部接口，不直接访问会话和消息表。 */
@Component
public class PythonChatClient {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private final RestTemplate http;
    private final String baseUrl;
    private final String token;

    public PythonChatClient(@Value("${app.model.python-base-url:http://localhost:8000}") String baseUrl,
                            @Value("${app.internal-tool-token:internal-tool-token}") String token) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.http = new RestTemplate(factory);
    }

    public record Message(String role, String content) {}

    public List<Message> messages(Long userId, Long sessionId) {
        Object data = call(HttpMethod.GET, "/ai/internal/chat/sessions/" + sessionId + "/messages?userId=" + userId);
        if (!(data instanceof List<?> rows)) throw new BizException("答疑服务返回了无效消息");
        List<Message> messages = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> values)) throw new BizException("答疑服务返回了无效消息");
            messages.add(new Message(String.valueOf(values.get("role")), String.valueOf(values.get("content"))));
        }
        return messages;
    }

    public void deleteCourseSessions(Long courseId) {
        call(HttpMethod.DELETE, "/ai/internal/chat/courses/" + courseId + "/sessions");
    }

    private Object call(HttpMethod method, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", token);
        try {
            ResponseEntity<Map<String, Object>> response = http.exchange(baseUrl + path, method,
                    new HttpEntity<>(headers), MAP);
            Map<String, Object> body = response.getBody();
            if (body == null || !(body.get("code") instanceof Number code)) {
                throw new BizException("答疑服务返回了无效响应");
            }
            if (code.intValue() != 0) throw new BizException(String.valueOf(body.get("message")));
            return body.get("data");
        } catch (RestClientException e) {
            throw new BizException("答疑服务暂时不可用，请稍后重试");
        }
    }
}
