package com.devforge.ai.common.git;

import java.util.List;

/**
 * One file's content, prepared for analysis.
 *
 * <p>Lines are split once here rather than by each consumer, because splitting a large file
 * repeatedly is the kind of waste that only shows up on a big repository.
 *
 * <p>Shared rather than owned by one service: review-service and documentation-service both read
 * repository content, and a second copy of this plus its client would be a second place the
 * size limits and the fail-closed behaviour could drift.
 *
 * @param path repository-relative, as git reports it.
 * @param size the real size in bytes, which may exceed the content actually fetched.
 * @param truncated true when only part of the file was fetched. Consumers still work on what
 *     arrived; a secret in the first kilobyte is worth reporting even if the rest was not read.
 * @param binary true when the content is not decodable text. Line-based consumers skip these —
 *     running text patterns over a PNG produces noise, not results.
 */
public record RepositoryFile(
    String path, long size, boolean truncated, boolean binary, String content, List<String> lines) {

  public static RepositoryFile of(
      String path, long size, boolean truncated, boolean binary, String content) {
    var text = content == null ? "" : content;
    // -1 keeps trailing empty lines, so a reported line number matches what an editor shows.
    var lines = binary ? List.<String>of() : List.of(text.split("\n", -1));
    return new RepositoryFile(path, size, truncated, binary, text, lines);
  }

  /** Name only, for consumers that judge a file by what it is called. */
  public String fileName() {
    var slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  public String extension() {
    var name = fileName();
    var dot = name.lastIndexOf('.');
    return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
  }
}
