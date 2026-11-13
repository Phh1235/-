package com.phh.procure.controller;

import com.phh.procure.common.ApiResponse;
import com.phh.procure.dto.LoginRequest;
import com.phh.procure.dto.LoginResponse;
import com.phh.procure.dto.UserView;
import com.phh.procure.security.SecurityUtils;
import com.phh.procure.security.ratelimit.RateLimit;
import com.phh.procure.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "登录并获取 JWT")
    @RateLimit(name = "login", limit = 10, windowSeconds = 60)
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @Operation(summary = "当前登录用户")
    @GetMapping("/me")
    public ApiResponse<UserView> me() {
        return ApiResponse.ok(authService.me(SecurityUtils.currentUserId()));
    }
}
