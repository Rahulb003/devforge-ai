package com.devforge.ai.gitservice.webhook;

import java.util.UUID;

/**
 * A branch moved: a commit from the editor, a push over git, or a merged pull request. Published
 * inside the change's transaction; webhooks are delivered only once it has committed.
 */
public record BranchChanged(
    UUID organizationId, UUID projectId, UUID repositoryId, String branch, String commitId,
    String actor, String via) {}
