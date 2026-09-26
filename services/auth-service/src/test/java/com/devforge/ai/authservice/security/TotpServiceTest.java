package com.devforge.ai.authservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies the TOTP implementation against the published RFC 6238 test vectors.
 *
 * <p>This is the reason the algorithm is implemented in-repo rather than pulled from a library:
 * correctness is demonstrated here, against the specification's own numbers, instead of assumed
 * from a dependency version. If these pass, every standard authenticator app will interoperate.
 */
class TotpServiceTest {

  /**
   * The RFC 6238 SHA-1 seed, "12345678901234567890" in ASCII, Base32-encoded.
   */
  private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

  private TotpService newService(int windowSteps) {
    var service = new TotpService();
    ReflectionTestUtils.setField(service, "windowSteps", windowSteps);
    return service;
  }

  /**
   * RFC 6238 Appendix B, SHA-1 column, truncated to the 6 digits this implementation emits.
   * The counter is floor(unixTime / 30).
   */
  @ParameterizedTest(name = "T={0} -> counter {1} -> {2}")
  @CsvSource({
      "59,          1,         287082",
      "1111111109,  37037036,  081804",
      "1111111111,  37037037,  050471",
      "1234567890,  41152263,  005924",
      "2000000000,  66666666,  279037",
      "20000000000, 666666666, 353130",
  })
  @DisplayName("matches the RFC 6238 reference vectors")
  void matchesRfcVectors(long unixTime, long counter, String expectedCode) {
    var service = newService(0);

    // Confirm the counter derivation itself, not just the HMAC.
    assertThat(unixTime / 30).isEqualTo(counter);
    assertThat(service.generateCode(RFC_SECRET, counter)).isEqualTo(expectedCode);
  }

  @Test
  @DisplayName("verifies the code valid at a given instant")
  void verifiesCurrentCode() {
    var service = newService(0);
    var at = Instant.ofEpochSecond(1111111109L);
    assertThat(service.verifyAt(RFC_SECRET, "081804", at)).isTrue();
  }

  @Test
  @DisplayName("rejects the code from a neighbouring step when the window is zero")
  void rejectsNeighbouringStepWithoutWindow() {
    var service = newService(0);
    var at = Instant.ofEpochSecond(1111111109L);
    // 050471 belongs to the next step.
    assertThat(service.verifyAt(RFC_SECRET, "050471", at)).isFalse();
  }

  @Test
  @DisplayName("accepts one step of drift when the window allows it")
  void acceptsOneStepDrift() {
    var service = newService(1);
    var at = Instant.ofEpochSecond(1111111109L);
    // The next step's code is accepted, absorbing clock skew between server and phone.
    assertThat(service.verifyAt(RFC_SECRET, "050471", at)).isTrue();
  }

  @Test
  @DisplayName("rejects a code two steps away even with a one-step window")
  void rejectsBeyondWindow() {
    var service = newService(1);
    var at = Instant.ofEpochSecond(1111111109L);
    var twoStepsLater = service.generateCode(RFC_SECRET, (1111111109L / 30) + 2);
    assertThat(service.verifyAt(RFC_SECRET, twoStepsLater, at)).isFalse();
  }

  @Test
  @DisplayName("rejects malformed input rather than throwing")
  void rejectsMalformedInput() {
    var service = newService(1);
    assertThat(service.verify(RFC_SECRET, null)).isFalse();
    assertThat(service.verify(RFC_SECRET, "")).isFalse();
    assertThat(service.verify(RFC_SECRET, "12345")).isFalse();
    assertThat(service.verify(RFC_SECRET, "1234567")).isFalse();
    assertThat(service.verify(RFC_SECRET, "abcdef")).isFalse();
    assertThat(service.verify(null, "123456")).isFalse();
  }

  @Test
  @DisplayName("generated secrets are distinct and decodable")
  void generatesUsableSecrets() {
    var service = newService(1);
    var first = service.generateSecret();
    var second = service.generateSecret();

    assertThat(first).isNotEqualTo(second);
    // 20 random bytes encode to 32 Base32 characters.
    assertThat(first).hasSize(32).matches("[A-Z2-7]+");
    assertThat(TotpService.base32Decode(first)).hasSize(20);
  }

  @Test
  @DisplayName("Base32 round-trips")
  void base32RoundTrips() {
    var original = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(TotpService.base32Encode(original)).isEqualTo(RFC_SECRET);
    assertThat(TotpService.base32Decode(RFC_SECRET)).isEqualTo(original);
  }

  @Test
  @DisplayName("the provisioning URI carries the parameters an authenticator needs")
  void provisioningUriIsWellFormed() {
    var service = newService(1);
    var uri = service.provisioningUri(RFC_SECRET, "ada@example.com", "DevForge AI");

    assertThat(uri)
        .startsWith("otpauth://totp/")
        .contains("secret=" + RFC_SECRET)
        .contains("algorithm=SHA1")
        .contains("digits=6")
        .contains("period=30")
        // Spaces must be percent-encoded, not left raw or turned into "+".
        .contains("DevForge%20AI")
        .doesNotContain(" ");
  }
}
