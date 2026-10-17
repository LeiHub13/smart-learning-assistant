package com.example.learningassistant.user.service;

import com.example.learningassistant.admin.service.AdminService;
import com.example.learningassistant.common.BizException;
import com.example.learningassistant.user.entity.User;
import com.example.learningassistant.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceReservedUsernameTest {

    private UserMapper userMapper;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        AdminService adminService = new AdminService(null, null, null, null);
        ReflectionTestUtils.setField(adminService, "adminUsernames", " Admin , Ops ");
        authService = new AuthService(userMapper, null, null, null, adminService);
    }

    @Test
    void registrationRejectsAllConfiguredAdminNamesBeforeCreatingUser() {
        for (String username : new String[]{"ADMIN", "  oPs  "}) {
            assertThrows(BizException.class,
                    () -> authService.register(username, "valid-password", "Student", "student@example.com", "code"));
        }
        verifyNoInteractions(userMapper);
    }

    @Test
    void renameCannotClaimConfiguredAdminName() {
        User user = new User();
        user.setUsername("student");
        when(userMapper.selectById(7L)).thenReturn(user);

        assertThrows(BizException.class, () -> authService.updateUsername(7L, "  oPs  "));

        verify(userMapper, never()).updateById(any(User.class));
    }
}
