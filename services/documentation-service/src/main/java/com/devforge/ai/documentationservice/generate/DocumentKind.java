package com.devforge.ai.documentationservice.generate;

/**
 * The kinds of document this service can produce.
 *
 * <p>Deliberately a closed set rather than a free string: a client links to a document by kind, and
 * a typo in a kind would be a broken link that only shows up when someone follows it.
 */
public enum DocumentKind {
  /** Languages, size, structure and build tooling — what this repository is. */
  OVERVIEW,
  /** HTTP endpoints found in the source, by framework. */
  API_SURFACE,
  /** How much of the public surface carries a doc comment. */
  DOC_COVERAGE
}
