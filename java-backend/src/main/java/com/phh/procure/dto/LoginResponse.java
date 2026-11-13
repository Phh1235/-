package com.phh.procure.dto;

public record LoginResponse(String token, long expiresIn, UserView user) {
}
