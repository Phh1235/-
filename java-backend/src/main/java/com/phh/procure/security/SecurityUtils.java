package com.phh.procure.security;

import com.phh.procure.common.BizException;
import com.phh.procure.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户读取工具。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static LoginUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser user) {
            return user;
        }
        throw new BizException(ErrorCode.UNAUTHORIZED);
    }

    public static Long currentUserId() {
        return currentUser().id();
    }
}
