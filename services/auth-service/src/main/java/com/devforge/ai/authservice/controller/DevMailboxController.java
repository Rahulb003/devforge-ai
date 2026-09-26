package com.devforge.ai.authservice.controller;

import com.devforge.ai.authservice.dto.ApiResponseDto;
import com.devforge.ai.authservice.service.DevMailbox;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads the development mailbox, so a verification link can be opened from the browser.
 *
 * <p><b>This endpoint hands out single-use account credentials.</b> Verification and reset tokens
 * are precisely what is needed to take over an account, so it is gated twice:
 *
 * <ol>
 *   <li>{@code devforge.mail.dev-mailbox-enabled=true}, which only the standalone profile sets and
 *       which defaults to false everywhere else, including when it is simply unset.
 *   <li>The bean it reads, {@link DevMailbox}, only exists when the development mail provider is
 *       active — so even forcing the flag on in a deployment that sends real mail leaves nothing
 *       to serve.
 * </ol>
 *
 * <p>It is also deliberately unauthenticated: the whole point is to complete a signup before an
 * account exists to authenticate with. That is exactly why the gating above matters.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dev/mailbox")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "devforge.mail.dev-mailbox-enabled", havingValue = "true")
public class DevMailboxController {

  private final DevMailbox mailbox;

  @GetMapping
  public ResponseEntity<ApiResponseDto<List<DevMailbox.CapturedMessage>>> list() {
    return ResponseEntity.ok(ApiResponseDto.<List<DevMailbox.CapturedMessage>>builder()
        .success(true)
        .data(mailbox.all())
        .message("Development mailbox. These messages were never delivered.")
        .build());
  }

  @DeleteMapping
  public ResponseEntity<ApiResponseDto<Void>> clear() {
    mailbox.clear();
    return ResponseEntity.ok(ApiResponseDto.<Void>builder()
        .success(true).message("Development mailbox cleared").build());
  }
}
