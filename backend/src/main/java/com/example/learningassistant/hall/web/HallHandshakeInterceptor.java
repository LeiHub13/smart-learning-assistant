package com.example.learningassistant.hall.web;

import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.JwtTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 对话厅握手鉴权：浏览器 WebSocket API 带不了 Authorization 头，
 * 约定从握手 URL 的 ?token=<JWT> 取凭据（也兼容 Authorization 头，便于非浏览器客户端）。
 * 校验失败直接 401 拒绝升级。
 */
@Component
@RequiredArgsConstructor
public class HallHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtTokenService jwtTokenService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = request.getURI().getQuery();
        String jwt = null;
        if (token != null) {
            for (String pair : token.split("&")) {
                int i = pair.indexOf('=');
                if (i > 0 && "token".equals(pair.substring(0, i))) {
                    jwt = urlDecode(pair.substring(i + 1));
                    break;
                }
            }
        }
        if (jwt == null || jwt.isBlank()) {
            String header = request.getHeaders().getFirst("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                jwt = header.substring(7);
            }
        }
        AuthUser user = jwt == null ? null : jwtTokenService.parse(jwt);
        if (user == null) {
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(HallWebSocketHandler.ATTR_USER, user);
        return true;
    }

    private static String urlDecode(String raw) {
        return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
