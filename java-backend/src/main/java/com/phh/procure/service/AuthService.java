package com.phh.procure.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.phh.procure.common.BizException;
import com.phh.procure.common.ErrorCode;
import com.phh.procure.dto.LoginRequest;
import com.phh.procure.dto.LoginResponse;
import com.phh.procure.dto.UserView;
import com.phh.procure.entity.SysUser;
import com.phh.procure.mapper.SysUserMapper;
import com.phh.procure.security.JwtService;
import com.phh.procure.security.LoginUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(SysUserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, request.username()));
        // 统一提示，避免暴露"用户是否存在"
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
        return new LoginResponse(jwtService.issue(loginUser), jwtService.getExpireSeconds(), toView(user));
    }

    public UserView me(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户不存在或已被禁用");
        }
        return toView(user);
    }

    private UserView toView(SysUser user) {
        return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
    }
}
