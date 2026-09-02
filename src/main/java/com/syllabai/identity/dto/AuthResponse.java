package com.syllabai.identity.dto;

public record AuthResponse(String accessToken, String tokenType, UserView user) {

    public AuthResponse {
        tokenType = "Bearer";
    }
}
