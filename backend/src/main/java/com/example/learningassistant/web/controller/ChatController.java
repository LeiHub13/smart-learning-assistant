package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * 本地直连 8080 的旧客户端兼容转发；生产 Nginx 和 Vite 将答疑请求直接路由到 Python。
 * 会话、消息、鉴权后的业务流程和生成都由 Python 答疑域处理。
 */
@RestController
@RequestMapping("/api/chat/sessions")
public class ChatController {
    private final String pythonBaseUrl;

    public ChatController(@Value("${app.model.python-base-url:http://localhost:8000}") String pythonBaseUrl) {
        this.pythonBaseUrl = pythonBaseUrl.replaceAll("/+$", "");
    }

    @RequestMapping(value = {"", "/**"}, method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<StreamingResponseBody> proxy(HttpServletRequest request) {
        HttpURLConnection connection = null;
        try {
            String path = request.getRequestURI();
            if (!path.equals("/api/chat/sessions")
                    && !path.matches("/api/chat/sessions/[0-9]+(?:/(?:messages|stream))?")) {
                throw new BizException("不支持的答疑接口");
            }
            if (request.getQueryString() != null) path += "?" + request.getQueryString();
            connection = (HttpURLConnection) URI.create(pythonBaseUrl + path).toURL().openConnection();
            connection.setRequestMethod(request.getMethod());
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(300000);
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null) connection.setRequestProperty(HttpHeaders.AUTHORIZATION, authorization);
            String contentType = request.getContentType();
            if (contentType != null) connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, contentType);
            if ("POST".equals(request.getMethod()) || "PUT".equals(request.getMethod())) {
                connection.setDoOutput(true);
                try (var output = connection.getOutputStream()) {
                    request.getInputStream().transferTo(output);
                }
            }
            int status = connection.getResponseCode();
            String upstreamType = connection.getContentType();
            InputStream upstream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            HttpURLConnection active = connection;
            StreamingResponseBody body = output -> {
                try (InputStream input = upstream) {
                    if (input != null) {
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                            output.flush();
                        }
                    }
                } finally {
                    active.disconnect();
                }
            };
            ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache");
            if (upstreamType != null) response.header(HttpHeaders.CONTENT_TYPE, upstreamType);
            return response.body(body);
        } catch (IOException | IllegalArgumentException e) {
            if (connection != null) connection.disconnect();
            throw new BizException("答疑服务暂时不可用，请稍后重试");
        }
    }
}
