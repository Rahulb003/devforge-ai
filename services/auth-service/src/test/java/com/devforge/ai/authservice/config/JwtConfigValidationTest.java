package com.devforge.ai.authservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the startup validation of the JWT signing key.
 *
 * <p>Without it, a missing or too-short key is only discovered when the first token is signed:
 * the service starts, reports healthy, accepts traffic, and then fails every login.
 */
class JwtConfigValidationTest {

  private JwtConfig configWith(String signingKey) {
    var config = new JwtConfig();
    config.setIssuer("devforge-ai");
    config.setSigningKey(signingKey);
    return config;
  }

  @Test
  @DisplayName("a key of at least 256 bits is accepted")
  void acceptsSufficientlyLongKey() {
    // Exactly 32 bytes / 256 bits, the HS256 minimum.
    var key = "0123456789abcdef0123456789abcdef";
    assertThat(key).hasSize(32);

    assertThatCode(() -> configWith(key).validate()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a key shorter than 256 bits is rejected at startup")
  void rejectsShortKey() {
    assertThatThrownBy(() -> configWith("too-short").validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("too short")
        .hasMessageContaining("minimum 32");
  }

  @Test
  @DisplayName("a 31-byte key is rejected: the boundary is exclusive below 32")
  void rejectsKeyOneByteUnderTheLimit() {
    var key = "0123456789abcdef0123456789abcde";
    assertThat(key).hasSize(31);

    assertThatThrownBy(() -> configWith(key).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("31 bytes");
  }

  @Test
  @DisplayName("a missing key is rejected with actionable guidance")
  void rejectsMissingKey() {
    assertThatThrownBy(() -> configWith(null).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DEVFORGE_JWT_SIGNING_KEY");
  }

  @Test
  @DisplayName("a blank key is rejected rather than treated as present")
  void rejectsBlankKey() {
    assertThatThrownBy(() -> configWith("   ").validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is not set");
  }

  @Test
  @DisplayName("a missing issuer is rejected, since tokens are issuer-bound")
  void rejectsMissingIssuer() {
    var config = new JwtConfig();
    config.setSigningKey("0123456789abcdef0123456789abcdef");
    config.setIssuer(null);

    assertThatThrownBy(config::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("issuer");
  }
}
