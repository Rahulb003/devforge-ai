package com.devforge.ai.aiservice.git;

import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.git.GitContentClient.GitServiceUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Reads a pull request and its changes from git-service, as the caller.
 *
 * <p>The same rules as the shared content client: the caller's own token is forwarded, a refusal is
 * reported as not-found, and an unreachable git-service fails the request rather than producing a
 * review of nothing.
 */
@Component
public class PullRequestReader {

  private final RestTemplate restTemplate;
  private final String gitServiceUrl;

  public PullRequestReader(
      RestTemplateBuilder builder, @Value("${devforge.services.git-service-url}") String gitServiceUrl) {
    this.gitServiceUrl = gitServiceUrl;
    this.restTemplate = builder.connectTimeout(Duration.ofSeconds(2)).readTimeout(Duration.ofSeconds(15)).build();
  }

  public record ChangedFile(String path, String changeType, String diff, boolean binary, boolean truncated) {}

  public record PullRequest(int number, String title, String description, String sourceBranch,
      String targetBranch, List<ChangedFile> files, int totalFiles) {}

  /** At most {@code maxFiles} files' diffs are fetched; the count of all of them is kept. */
  public PullRequest read(UUID organizationId, UUID projectId, UUID repositoryId, int number,
      int maxFiles, String bearerToken) {
    var base = "%s/api/v1/organizations/%s/projects/%s/repositories/%s/pull-requests/%d"
        .formatted(gitServiceUrl, organizationId, projectId, repositoryId, number);
    var pr = get(base, bearerToken).path("data");
    var entries = get(base + "/diff", bearerToken).path("data").path("entries");
    var files = new ArrayList<ChangedFile>();
    for (var entry : entries) {
      if (files.size() >= maxFiles) {
        break;
      }
      var path = "DELETE".equals(entry.path("changeType").asText())
          ? entry.path("oldPath").asText() : entry.path("newPath").asText();
      var url = UriComponentsBuilder.fromHttpUrl(base + "/diff/file").queryParam("path", path).toUriString();
      var fileDiff = get(url, bearerToken).path("data");
      files.add(new ChangedFile(path, entry.path("changeType").asText(), unified(fileDiff),
          fileDiff.path("binary").asBoolean(), fileDiff.path("truncated").asBoolean()));
    }
    return new PullRequest(pr.path("number").asInt(), pr.path("title").asText(),
        pr.path("description").isNull() ? "" : pr.path("description").asText(),
        pr.path("sourceBranch").asText(), pr.path("targetBranch").asText(), files, entries.size());
  }

  /** The hunks as a unified diff body: " " context, "+" added, "-" removed. */
  static String unified(JsonNode fileDiff) {
    var out = new StringBuilder();
    for (var hunk : fileDiff.path("hunks")) {
      out.append("@@ -").append(hunk.path("oldStart").asInt()).append(" +")
          .append(hunk.path("newStart").asInt()).append(" @@\n");
      for (var line : hunk.path("lines")) {
        var prefix = switch (line.path("type").asText()) {
          case "ADD", "ADDED", "+" -> "+";
          case "DELETE", "DELETED", "REMOVED", "-" -> "-";
          default -> " ";
        };
        out.append(prefix).append(line.path("text").asText()).append('\n');
      }
    }
    return out.toString();
  }

  private JsonNode get(String url, String bearerToken) {
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
    try {
      var body = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getBody();
      if (body == null) {
        throw new GitServiceUnavailableException("git-service returned an empty response", null);
      }
      return body;
    } catch (HttpClientErrorException ex) {
      var status = ex.getStatusCode().value();
      if (status == 401 || status == 403 || status == 404) {
        throw new ResourceNotFoundException("Pull request not found");
      }
      throw ex;
    } catch (ResourceAccessException ex) {
      throw new GitServiceUnavailableException("Cannot read the repository right now. Please try again.", ex);
    }
  }
}
