package com.devforge.ai.authservice.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Time-based one-time passwords, RFC 6238 over HMAC-SHA1 with a 30-second step.
 *
 * <p>Implemented directly rather than pulled in as a dependency: the algorithm is about thirty
 * lines, and it is verified here against the published RFC test vectors, which is stronger
 * evidence than a version number. Those parameters are what every authenticator app assumes.
 */
@Slf4j
@Service
public class TotpService {

  /** RFC 6238 default step. Changing it breaks compatibility with authenticator apps. */
  private static final int TIME_STEP_SECONDS = 30;

  private static final int DIGITS = 6;
  private static final String HMAC_ALGORITHM = "HmacSHA1";
  private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final int SECRET_BYTES = 20;

  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * How many steps either side of now are accepted.
   *
   * <p>One step (±30s) absorbs ordinary clock drift between the server and the user's phone.
   * Widening this materially enlarges the window an intercepted code stays usable in, so it is
   * kept small and configurable rather than generous by default.
   */
  @Value("${security.mfa.totp-window-steps:1}")
  private int windowSteps;

  /** Generates a new Base32 shared secret for enrolment. */
  public String generateSecret() {
    var bytes = new byte[SECRET_BYTES];
    secureRandom.nextBytes(bytes);
    return base32Encode(bytes);
  }

  /**
   * The {@code otpauth://} URI an authenticator app consumes, usually via QR code.
   *
   * <p>Contains the shared secret, so it may only be shown once during enrolment and must never
   * be logged.
   */
  public String provisioningUri(String secret, String accountName, String issuer) {
    return "otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=%d&period=%d".formatted(
        urlEncode(issuer), urlEncode(accountName), secret, urlEncode(issuer), DIGITS,
        TIME_STEP_SECONDS);
  }

  /** Verifies a submitted code against the secret, allowing the configured drift window. */
  public boolean verify(String secret, String code) {
    return verifyAt(secret, code, Instant.now());
  }

  /** Verification at an explicit instant. Exposed so tests can pin RFC vectors to a timestamp. */
  boolean verifyAt(String secret, String code, Instant at) {
    if (secret == null || code == null) {
      return false;
    }
    var normalised = code.trim().replace(" ", "");
    if (normalised.length() != DIGITS || !normalised.chars().allMatch(Character::isDigit)) {
      return false;
    }

    var currentStep = at.getEpochSecond() / TIME_STEP_SECONDS;
    for (int offset = -windowSteps; offset <= windowSteps; offset++) {
      if (constantTimeEquals(generateCode(secret, currentStep + offset), normalised)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The code for a given counter value.
   *
   * <p>Public so callers and tests can drive specific counters, including the RFC vectors.
   * This discloses nothing extra: producing a code already requires the shared secret, which
   * is itself the credential.
   */
  public String generateCode(String base32Secret, long counter) {
    try {
      var key = base32Decode(base32Secret);
      var data = new byte[8];
      var value = counter;
      for (int i = 7; i >= 0; i--) {
        data[i] = (byte) (value & 0xFF);
        value >>>= 8;
      }

      var mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
      var hash = mac.doFinal(data);

      // Dynamic truncation, RFC 4226 section 5.3.
      int offset = hash[hash.length - 1] & 0x0F;
      int binary = ((hash[offset] & 0x7F) << 24)
          | ((hash[offset + 1] & 0xFF) << 16)
          | ((hash[offset + 2] & 0xFF) << 8)
          | (hash[offset + 3] & 0xFF);

      int otp = binary % (int) Math.pow(10, DIGITS);
      return String.format(Locale.ROOT, "%0" + DIGITS + "d", otp);
    } catch (Exception ex) {
      // Never surface the secret or the cause to the caller.
      log.error("TOTP generation failed", ex);
      throw new IllegalStateException("Unable to generate TOTP code");
    }
  }

  /**
   * Length-independent, value-constant comparison.
   *
   * <p>{@code String.equals} short-circuits on the first differing character, which leaks how
   * much of a guessed code was correct through response timing.
   */
  private boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null || a.length() != b.length()) {
      return false;
    }
    int result = 0;
    for (int i = 0; i < a.length(); i++) {
      result |= a.charAt(i) ^ b.charAt(i);
    }
    return result == 0;
  }

  static String base32Encode(byte[] data) {
    var output = new StringBuilder();
    int buffer = 0;
    int bitsLeft = 0;
    for (byte b : data) {
      buffer = (buffer << 8) | (b & 0xFF);
      bitsLeft += 8;
      while (bitsLeft >= 5) {
        output.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
        bitsLeft -= 5;
      }
    }
    if (bitsLeft > 0) {
      output.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
    }
    return output.toString();
  }

  static byte[] base32Decode(String encoded) {
    var cleaned = encoded.trim().replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
    var output = new java.io.ByteArrayOutputStream();
    int buffer = 0;
    int bitsLeft = 0;
    for (char c : cleaned.toCharArray()) {
      int index = BASE32_ALPHABET.indexOf(c);
      if (index < 0) {
        throw new IllegalArgumentException("Invalid Base32 character");
      }
      buffer = (buffer << 5) | index;
      bitsLeft += 5;
      if (bitsLeft >= 8) {
        output.write((buffer >> (bitsLeft - 8)) & 0xFF);
        bitsLeft -= 8;
      }
    }
    return output.toByteArray();
  }

  private String urlEncode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
