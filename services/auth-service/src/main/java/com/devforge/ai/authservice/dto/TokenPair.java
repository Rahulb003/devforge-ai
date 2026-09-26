package com.devforge.ai.authservice.dto;

/**
 * An access token together with the refresh token that should replace the caller's current one.
 *
 * <p>Refresh is a rotation, so both values change on every exchange and must be returned together.
 */
public record TokenPair(String accessToken, String refreshToken) {}
