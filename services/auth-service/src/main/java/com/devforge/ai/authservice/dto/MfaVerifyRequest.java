package com.devforge.ai.authservice.dto;

import jakarta.validation.constraints.NotBlank;

/** Second step of login: the MFA challenge token plus a TOTP or recovery code. */
public record MfaVerifyRequest(
    @NotBlank String challengeToken,
    @NotBlank String code) {}
