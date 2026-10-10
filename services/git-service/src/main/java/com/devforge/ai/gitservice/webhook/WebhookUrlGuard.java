package com.devforge.ai.gitservice.webhook;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether a webhook may be sent to a URL.
 *
 * <p>A webhook makes this service send a request wherever a repository admin says, from inside the
 * deployment. Without a guard that is server-side request forgery: a URL pointing at
 * {@code http://169.254.169.254/} reads cloud credentials, one pointing at {@code postgres:5432} or
 * {@code localhost:9001} probes services that trust their network. So every address the host
 * resolves to must be public, checked when the webhook is saved and again before each delivery
 * (DNS can change in between). HTTPS only, unless a deployment allows plain HTTP.
 */
@Component
public class WebhookUrlGuard {

  private final boolean allowPrivate;
  private final boolean allowHttp;

  public WebhookUrlGuard(
      @Value("${devforge.webhooks.allow-private-addresses:false}") boolean allowPrivate,
      @Value("${devforge.webhooks.allow-http:false}") boolean allowHttp) {
    this.allowPrivate = allowPrivate;
    this.allowHttp = allowHttp;
  }

  /** The URL, checked and resolved; throws with a message for the admin when it is not allowed. */
  public URI check(String url) {
    if (url == null || url.isBlank() || url.length() > 2000) {
      throw new IllegalArgumentException("A webhook URL of up to 2000 characters is required");
    }
    URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("That is not a valid URL");
    }
    var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) {
      throw new IllegalArgumentException(allowHttp ? "Webhook URLs must be http or https" : "Webhook URLs must use https");
    }
    if (uri.getHost() == null || uri.getRawUserInfo() != null) {
      throw new IllegalArgumentException("A webhook URL needs a host, and no credentials in it");
    }
    if (!allowPrivate) {
      InetAddress[] addresses;
      try {
        addresses = InetAddress.getAllByName(uri.getHost());
      } catch (UnknownHostException ex) {
        throw new IllegalArgumentException("The webhook's host does not resolve");
      }
      for (var address : addresses) {
        if (isInternal(address)) {
          throw new IllegalArgumentException("Webhooks cannot be sent to private, loopback or link-local addresses");
        }
      }
    }
    return uri;
  }

  static boolean isInternal(InetAddress address) {
    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
      return true;
    }
    var bytes = address.getAddress();
    if (bytes.length == 16) {
      // Unique local fc00::/7 - IPv6's private range, which isSiteLocalAddress does not cover.
      return (bytes[0] & 0xfe) == 0xfc;
    }
    // 100.64.0.0/10, carrier-grade NAT, used inside some cloud networks.
    return (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
  }
}
