package com.devforge.ai.authservice.controller;

import com.devforge.ai.authservice.service.PersonalAccessTokenService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exchanges a personal access token for a short-lived access token. Called by git-service only.
 *
 * <p>Exposing it would turn a git-only credential into one for the whole API, because the access
 * token it returns is accepted everywhere. Two things keep it internal. It is under
 * {@code /internal}, which neither the gateway nor nginx routes. And it refuses any request that
 * passed through either of them: both add forwarding headers, and a client can add such a header
 * but cannot remove one a proxy adds, so their presence is a sound reason to refuse. That second
 * check is what holds if a path trick ever got a request routed here, e.g.
 * {@code /api/v1/auth/../../internal/...} on a container that did not normalise it.
 */
@RestController
@RequiredArgsConstructor
public class TokenExchangeController {

  private static final List<String> PROXY_HEADERS =
      List.of("X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "Forwarded", "X-Real-IP");

  private final PersonalAccessTokenService tokens;

  public record ExchangeRequest(String token) {}

  @PostMapping("/internal/v1/tokens/exchange")
  public ResponseEntity<Map<String, String>> exchange(
      @RequestBody ExchangeRequest request, HttpServletRequest http) {
    if (PROXY_HEADERS.stream().anyMatch(name -> http.getHeader(name) != null)) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
    // One answer for every failure: unknown, revoked, expired and disabled are not told apart.
    return tokens.exchange(request.token())
        .map(accessToken -> ResponseEntity.ok(Map.of("accessToken", accessToken)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
  }
}
