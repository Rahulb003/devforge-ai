package com.devforge.ai.authservice.dto;

/**
 * Outcome of a password login.
 *
 * <p>When MFA is enabled the access token is withheld and a short-lived challenge token is
 * returned instead, so a correct password alone never yields API access.
 */
public record LoginResult(
    boolean mfaRequired,
    String accessToken,
    String challengeToken) {

  public static LoginResult authenticated(String accessToken) {
    return new LoginResult(false, accessToken, null);
  }

  public static LoginResult mfaChallenge(String challengeToken) {
    return new LoginResult(true, null, challengeToken);
  }
}
