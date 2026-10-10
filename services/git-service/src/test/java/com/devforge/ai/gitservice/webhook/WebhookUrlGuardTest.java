package com.devforge.ai.gitservice.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Where a webhook may not go: the addresses that turn a webhook into a request forgery. */
@DisplayName("Webhook URL guard")
class WebhookUrlGuardTest {

  private final WebhookUrlGuard guard = new WebhookUrlGuard(false, false);

  @ParameterizedTest(name = "{0} is refused")
  @ValueSource(strings = {
      "https://127.0.0.1/hook",              // loopback
      "https://localhost:9001/internal",     // a service's own port
      "https://10.0.0.5/hook",               // private
      "https://192.168.1.10/hook",
      "https://172.16.0.1/hook",
      "https://169.254.169.254/latest/meta-data/", // cloud metadata
      "https://[::1]/hook",
      "https://[fd00::1]/hook",              // IPv6 unique local
      "https://100.64.1.1/hook",             // carrier-grade NAT
      "https://0.0.0.0/hook"})
  void internalAddressesAreRefused(String url) {
    assertThatThrownBy(() -> guard.check(url)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("plain http, credentials in the URL and nonsense are refused")
  void otherRefusals() {
    assertThatThrownBy(() -> guard.check("http://93.184.216.34/hook")).hasMessageContaining("https");
    assertThatThrownBy(() -> guard.check("https://user:pass@93.184.216.34/hook")).hasMessageContaining("credentials");
    assertThatThrownBy(() -> guard.check("not a url")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> guard.check("ftp://93.184.216.34/")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a public address over https is allowed")
  void publicHttpsIsAllowed() {
    assertThat(guard.check("https://93.184.216.34/hook").getHost()).isEqualTo("93.184.216.34");
  }
}
