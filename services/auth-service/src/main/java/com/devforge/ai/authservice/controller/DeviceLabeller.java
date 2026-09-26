package com.devforge.ai.authservice.controller;

import java.util.Locale;

/**
 * Turns a User-Agent string into a short label for the session list.
 *
 * <p>Deliberately crude. The label is a human hint for "is this session mine?", not an
 * identifier, and nothing is authorised based on it — so a wrong or spoofed guess is cosmetic.
 * A full UA-parsing dependency would be a large attack surface for a cosmetic feature.
 */
final class DeviceLabeller {

  private DeviceLabeller() {}

  static String describe(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return "Unknown device";
    }
    var ua = userAgent.toLowerCase(Locale.ROOT);
    return "%s on %s".formatted(browser(ua), platform(ua));
  }

  private static String browser(String ua) {
    // Order matters: Edge and Chrome both claim "chrome"/"safari" in their UA strings.
    if (ua.contains("edg/")) {
      return "Edge";
    }
    if (ua.contains("opr/") || ua.contains("opera")) {
      return "Opera";
    }
    if (ua.contains("firefox")) {
      return "Firefox";
    }
    if (ua.contains("chrome") || ua.contains("chromium")) {
      return "Chrome";
    }
    if (ua.contains("safari")) {
      return "Safari";
    }
    return "Unknown browser";
  }

  private static String platform(String ua) {
    if (ua.contains("windows")) {
      return "Windows";
    }
    if (ua.contains("android")) {
      return "Android";
    }
    // Checked before "mac": iOS UA strings contain "like Mac OS X".
    if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ios")) {
      return "iOS";
    }
    if (ua.contains("mac os") || ua.contains("macintosh")) {
      return "macOS";
    }
    if (ua.contains("linux")) {
      return "Linux";
    }
    return "Unknown OS";
  }
}
