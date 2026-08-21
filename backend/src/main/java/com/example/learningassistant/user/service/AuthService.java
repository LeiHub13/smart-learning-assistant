package com.example.learningassistant.user.service;

import com.example.learningassistant.common.BizException;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.JwtTokenService;
import com.example.learningassistant.user.entity.User;
import com.example.learningassistant.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

/**
 * 认证服务：登录 / 注册 / 当前用户。
 * 密码存储骨架阶段使用 SHA-256 加盐；生产环境切换 BCrypt（spring-security-crypto）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final JwtTokenService jwtTokenService;

    public Map<String, String> login(String username, String password) {
        User user = userMapper.findByUsername(username)
                .orElseThrow(() -> new BizException("用户名或密码错误"));
        if (!user.getPassword().equals(hash(password))) {
            throw new BizException("用户名或密码错误");
        }
        return tokens(user);
    }

    public Map<String, String> register(String username, String password, String nickname) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BizException("用户名与密码不能为空");
        }
        if (userMapper.findByUsername(username).isPresent()) {
            throw new BizException("用户名已存在");
        }
        User u = new User();
        u.setTenantId(1L);
        u.setUsername(username);
        u.setPassword(hash(password));
        u.setNickname(nickname == null || nickname.isBlank() ? username : nickname);
        u.setCreatedAt(LocalDateTime.now());
        userMapper.insert(u);
        return tokens(u);
    }

    public User me(Long userId) {
        return userMapper.selectById(userId);
    }

    private Map<String, String> tokens(User user) {
        AuthUser au = new AuthUser(user.getId(), user.getUsername(), user.getNickname(), user.getTenantId());
        return Map.of(
                "accessToken", jwtTokenService.createAccessToken(au),
                "refreshToken", jwtTokenService.createRefreshToken(au));
    }

    /** SHA-256 加盐哈希（demo）；生产替换 BCrypt */
    public static String hash(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(("la-salt-" + password).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
