package com.devforge.ai.authservice.dto;

import jakarta.validation.constraints.NotBlank;

/** A TOTP or recovery code, used to confirm enrolment or authorise disabling MFA. */
public record MfaCodeRequest(@NotBlank String code) {}
