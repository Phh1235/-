package com.phh.procure.security;

/**
 * 登录态用户信息，作为 Spring Security 的 principal。
 */
public record LoginUser(Long id, String username, String displayName, String role) {
}
