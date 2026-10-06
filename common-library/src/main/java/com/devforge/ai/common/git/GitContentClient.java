package com.devforge.ai.common.git;

import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * Fetches repository content from git-service.
 *
 * <p>Nothing here opens a repository. git-service owns the object database and is the single place
 * repository paths and refs are validated; a second reader would be a second place for that
 * validation to drift, and path validation is exactly where a traversal bug lives.
 *
 * <p>Lives in common-library because review-service and documentation-service both need it, and
 * the parts worth getting right — the fetch bounds, and failing closed when content is
 * unreadable — are exactly the parts that rot when copied.
 *
 * <p>The caller's own bearer token is forwarded, for the same reason as everywhere else: the
 * decision is about the user who asked, not about this service. A service credential would let
 * review-service read every repository on the platform.
 */
@Slf4j
@Component
// Conditional because every service scans com.devforge.ai. Without this, services that never
// read repository content — auth, project, task — would fail to start for want of a property
// they have no reason to set. The same lesson as ProjectAccessClient.
@ConditionalOnProperty("devforge.services.git-service-url")
public class GitContentClient {

  private final RestTemplate restTemplate;
  private final String gitServiceBaseUrl;

  /** Files fetched per review. A review of a 50,000-file repository is not worth the fetch. */
  @Value("${devforge.git-content.max-files:400}")
  private int maxFiles;

  /** Directories walked, as a second bound: a deep tree can hold few files per level. */
  @Value("${devforge.git-content.max-directories:200}")
  private int maxDirectories;

  /** Files above this are recorded but not fetched; their size alone can still raise a finding. */
  @Value("${devforge.git-content.max-file-bytes:262144}")
  private long maxFileBytes;

  public GitContentClient(
      RestTemplateBuilder builder,
      @Value("${devforge.services.git-service-url}") String gitServiceBaseUrl) {
    this.gitServiceBaseUrl = gitServiceBaseUrl;
    this.restTemplate = builder
        .connectTimeout(Duration.ofSeconds(3))
        // Longer than the authorization hop: this transfers file content, not a yes/no.
        .readTimeout(Duration.ofSeconds(20))
        .build();
  }

  /**
   * The files to analyse.
   *
   * <p>With a {@code baseRef}, only what changed between the two refs — which is what a review of a
   * proposed change should look at. Without one, the whole tree at {@code ref}.
   */
  public List<RepositoryFile> filesToAnalyse(
      Context context, String ref, String baseRef, String bearerToken) {

    var paths = baseRef == null
        ? listTree(context, ref, bearerToken)
        : changedPaths(context, baseRef, ref, bearerToken);

    var files = new ArrayList<RepositoryFile>(paths.size());
    for (var path : paths) {
      var file = fetchBlob(context, ref, path, bearerToken);
      if (file != null) {
        files.add(file);
      }
    }
    return files;
  }

  /**
   * The repository's own default branch.
   *
   * <p>Asked for rather than assumed. {@code main} is only a convention, the repository records
   * which branch it actually uses, and a review of a branch that does not exist is a confusing 404
   * rather than an obvious mistake.
   */
  public String defaultBranch(Context context, String bearerToken) {
    var body = get(base(context), bearerToken);
    var branch = body.path("data").path("defaultBranch").asText(null);
    if (branch == null || branch.isBlank()) {
      throw new ResourceNotFoundException("Repository not found");
    }
    return branch;
  }

  /** Paths changed between two refs, excluding deletions: a deleted file has nothing to analyse. */
  private List<String> changedPaths(Context context, String from, String to, String bearerToken) {
    var url = UriComponentsBuilder.fromHttpUrl(base(context) + "/diff")
        .queryParam("from", from)
        .queryParam("to", to)
        .toUriString();

    var body = get(url, bearerToken);
    var paths = new ArrayList<String>();
    for (var entry : body.path("data").path("entries")) {
      if ("DELETE".equals(entry.path("changeType").asText())) {
        continue;
      }
      var newPath = entry.path("newPath");
      if (!newPath.isMissingNode() && !newPath.isNull()) {
        paths.add(newPath.asText());
      }
      if (paths.size() >= maxFiles) {
        log.info("Diff for repository {} exceeded {} files; analysing the first {}",
            context.repositoryId(), maxFiles, maxFiles);
        break;
      }
    }
    return paths;
  }

  /**
   * Every file at a ref, found by walking the tree one level at a time.
   *
   * <p>Breadth-first with explicit bounds rather than recursion: a repository is untrusted input,
   * and a deeply nested tree would otherwise decide this service's stack depth.
   */
  private List<String> listTree(Context context, String ref, String bearerToken) {
    var files = new ArrayList<String>();
    var queue = new ArrayDeque<String>();
    queue.add("");
    var directoriesVisited = 0;

    while (!queue.isEmpty() && files.size() < maxFiles && directoriesVisited < maxDirectories) {
      var directory = queue.poll();
      directoriesVisited++;

      var builder = UriComponentsBuilder.fromHttpUrl(base(context) + "/tree")
          .queryParam("ref", ref);
      if (!directory.isEmpty()) {
        builder.queryParam("path", directory);
      }

      JsonNode body;
      try {
        body = get(builder.toUriString(), bearerToken);
      } catch (ResourceNotFoundException ex) {
        // A directory that vanished between listings is not a reason to fail the review.
        log.debug("Tree listing for {} is no longer available", directory);
        continue;
      }

      for (var entry : body.path("data")) {
        var path = entry.path("path").asText();
        if ("DIRECTORY".equals(entry.path("type").asText())) {
          queue.add(path);
        } else {
          files.add(path);
          if (files.size() >= maxFiles) {
            break;
          }
        }
      }
    }

    if (!queue.isEmpty()) {
      log.info("Repository {} is larger than the review limits ({} files, {} directories); "
              + "analysed a subset", context.repositoryId(), maxFiles, maxDirectories);
    }
    return files;
  }

  private RepositoryFile fetchBlob(Context context, String ref, String path, String bearerToken) {
    var url = UriComponentsBuilder.fromHttpUrl(base(context) + "/blob")
        .queryParam("ref", ref)
        .queryParam("path", path)
        .toUriString();

    try {
      var data = get(url, bearerToken).path("data");
      var size = data.path("size").asLong();
      var binary = data.path("binary").asBoolean();

      if (size > maxFileBytes) {
        // Recorded without content: the size itself can raise a finding, and fetching a 200 MB
        // blob to run regexes over it is not a good trade.
        return RepositoryFile.of(path, size, true, binary, "");
      }

      var content = data.path("content").isNull() ? "" : data.path("content").asText();
      return RepositoryFile.of(path, size, data.path("truncated").asBoolean(), binary, content);
    } catch (ResourceNotFoundException ex) {
      // Listed a moment ago, gone now — a concurrent push. Skipped rather than failing the review.
      log.debug("File {} was not found at {}", path, ref);
      return null;
    }
  }

  private String base(Context context) {
    return "%s/api/v1/organizations/%s/projects/%s/repositories/%s".formatted(
        gitServiceBaseUrl, context.organizationId(), context.projectId(), context.repositoryId());
  }

  private JsonNode get(String url, String bearerToken) {
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, bearerToken);

    try {
      var response = restTemplate.exchange(
          url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
      var body = response.getBody();
      if (body == null) {
        throw new GitServiceUnavailableException("git-service returned an empty response", null);
      }
      return body;
    } catch (HttpClientErrorException.NotFound ex) {
      throw new ResourceNotFoundException("Not found in the repository");
    } catch (HttpClientErrorException ex) {
      var status = ex.getStatusCode().value();
      if (status == 401 || status == 403) {
        // The caller cannot read this repository. Reported as not-found, matching git-service, so
        // this does not become a way to learn that a repository exists.
        throw new ResourceNotFoundException("Repository not found");
      }
      throw ex;
    } catch (ResourceAccessException ex) {
      // Fails closed: a review that cannot read the code must not report "no problems found",
      // which is indistinguishable from a clean repository and far worse than an error.
      throw new GitServiceUnavailableException(
          "Cannot read the repository right now. Please try again.", ex);
    }
  }

  /** Where the content lives. Grouped so the three ids travel together. */
  public record Context(UUID organizationId, UUID projectId, UUID repositoryId) {}

  /** Raised when repository content cannot be read. Maps to 503. */
  public static class GitServiceUnavailableException extends RuntimeException {
    public GitServiceUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
