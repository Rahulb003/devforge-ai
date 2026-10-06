package com.devforge.ai.documentationservice.generate;

import com.devforge.ai.common.git.RepositoryFile;
import java.util.List;

/**
 * Produces one document from a repository's files.
 *
 * <p>Generators are Spring beans, so adding one is adding a class — nothing has to be registered in
 * a second place, which is the usual way a new generator silently never runs.
 *
 * <p>Everything here describes what is <em>actually in the repository</em>. Nothing is inferred,
 * guessed, or written on the model's say-so: a generated document that confidently describes an
 * endpoint that does not exist is worse than no document, because the reader has no way to tell.
 * When a generator cannot determine something, it says so in the output rather than omitting it.
 */
public interface DocumentGenerator {

  /** Stable identifier, used as the document's kind and in its URL. */
  DocumentKind kind();

  /** Human-facing title. */
  String title();

  /** Rendered Markdown, or null when there is nothing to say about this repository. */
  String generate(List<RepositoryFile> files);
}
