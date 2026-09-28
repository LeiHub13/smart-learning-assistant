package com.example.learningassistant.user.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.course.entity.Course;
import com.example.learningassistant.course.mapper.CourseMapper;
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
    private final EmailCodeService emailCodeService;
    private final CourseMapper courseMapper;
    private final com.example.learningassistant.admin.service.AdminService adminService;

    public Map<String, String> login(String username, String password) {
        User user = userMapper.findByUsername(username)
                .orElseThrow(() -> new BizException("用户名或密码错误"));
        if (!user.getPassword().equals(hash(password))) {
            throw new BizException("用户名或密码错误");
        }
        return tokens(user);
    }

    /** 注册：邮箱验证码校验通过才建号，邮箱即账号的联系方式（重置密码依赖它） */
    public Map<String, String> register(String username, String password, String nickname, String email, String code) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BizException("用户名与密码不能为空");
        }
        if (email == null || email.isBlank()) {
            throw new BizException("请填写邮箱并完成验证码校验");
        }
        String mail = email.trim();
        if (userMapper.findByUsername(username).isPresent()) {
            throw new BizException("用户名已存在");
        }
        if (userMapper.findByEmail(mail).isPresent()) {
            throw new BizException("该邮箱已被注册");
        }
        emailCodeService.verify(EmailCodeService.SCENE_REGISTER, mail, code);
        User u = new User();
        u.setTenantId(1L);
        u.setUsername(username);
        u.setPassword(hash(password));
        u.setNickname(nickname == null || nickname.isBlank() ? username : nickname);
        u.setEmail(mail);
        u.setCreatedAt(LocalDateTime.now());
        userMapper.insert(u);
        return tokens(u);
    }

    /** 忘记密码：邮箱验证码验证身份后直接重置（无需原密码） */
    public void resetPassword(String email, String code, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new BizException("新密码至少 6 位");
        }
        if (email == null || email.isBlank()) {
            throw new BizException("请填写接收验证码的邮箱");
        }
        User user = userMapper.findByEmail(email.trim())
                .orElseThrow(() -> new BizException("该邮箱未绑定任何账号"));
        emailCodeService.verify(EmailCodeService.SCENE_RESET_PASSWORD, user.getEmail(), code);
        user.setPassword(hash(newPassword));
        userMapper.updateById(user);
    }

    public User me(Long userId) {
        return userMapper.selectById(userId);
    }

    /** 绑定/更新接收通知邮件的邮箱。 */
    public void bindEmail(Long userId, String email) {
        if (email == null || !email.matches("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$")) {
            throw new BizException("邮箱格式不正确");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        // 同一邮箱只能绑定一个账号，否则重置密码时无法唯一定位用户
        userMapper.findByEmail(email.trim())
                .filter(u -> !u.getId().equals(user.getId()))
                .ifPresent(u -> { throw new BizException("该邮箱已被其他账号绑定"); });
        user.setEmail(email.trim());
        userMapper.updateById(user);
    }

    /**
     * 更新昵称。课程表冗余了创建者名快照（t_course.owner_name，课程 Hub 展示用），
     * 这里同步回填，否则改名后历史课程仍显示旧昵称。
     */
    public void updateNickname(Long userId, String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new BizException("昵称不能为空");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        String name = nickname.trim();
        user.setNickname(name);
        userMapper.updateById(user);
        courseMapper.update(null, new LambdaUpdateWrapper<Course>()
                .eq(Course::getOwnerId, userId)
                .set(Course::getOwnerName, name));
    }

    /**
     * 修改用户名：全局唯一（应用层查重 + t_user.uk_username 唯一索引兜底并发）。
     * 管理员账号禁止改名：管理员身份按用户名白名单判定（AdminService.isAdmin），
     * 改名后旧用户名空出会被他人注册从而顶替管理员身份。
     */
    public void updateUsername(Long userId, String newUsername) {
        if (newUsername == null || newUsername.isBlank()) {
            throw new BizException("用户名不能为空");
        }
        String name = newUsername.trim();
        if (name.length() > 32) {
            throw new BizException("用户名不能超过 32 个字符");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        if (adminService.isAdmin(user.getUsername())) {
            throw new BizException("管理员账号不允许修改用户名");
        }
        if (name.equals(user.getUsername())) {
            return;
        }
        userMapper.findByUsername(name)
                .ifPresent(u -> { throw new BizException("用户名已存在"); });
        user.setUsername(name);
        userMapper.updateById(user);
    }

    /** 修改密码：校验原密码，新密码至少 6 位。 */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new BizException("新密码至少 6 位");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        if (!user.getPassword().equals(hash(oldPassword))) {
            throw new BizException("原密码错误");
        }
        user.setPassword(hash(newPassword));
        userMapper.updateById(user);
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
