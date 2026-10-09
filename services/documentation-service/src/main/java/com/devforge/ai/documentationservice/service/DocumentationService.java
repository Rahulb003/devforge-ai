package com.devforge.ai.documentationservice.service;

import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.git.GitContentClient;
import com.devforge.ai.common.git.RepositoryFile;
import com.devforge.ai.common.security.AuthenticatedUser;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.documentationservice.dto.DocDtos.DocSetResponse;
import com.devforge.ai.documentationservice.dto.DocDtos.DocumentResponse;
import com.devforge.ai.documentationservice.dto.DocDtos.GenerateRequest;
import com.devforge.ai.documentationservice.entity.DocSetEntity;
import com.devforge.ai.documentationservice.entity.DocumentEntity;
import com.devforge.ai.documentationservice.generate.DocumentGenerator;
import com.devforge.ai.documentationservice.generate.DocumentKind;
import com.devforge.ai.documentationservice.model.DocSetStatus;
import com.devforge.ai.documentationservice.repository.DocSetRepository;
import com.devforge.ai.documentationservice.repository.DocumentRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Generates documentation from a repository, and serves what was generated.
 *
 * <p>Generation is synchronous and bounded by the shared fetch limits, so a caller gets a result
 * rather than a job to poll.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentationService {

  private final DocSetRepository docSets;
  private final DocumentRepository documents;
  private final ProjectAccessClient projectAccess;
  private final GitContentClient gitContent;
  private final List<DocumentGenerator> generators;
  private final DocSetFailureRecorder failureRecorder;

  @Transactional
  public DocSetResponse generate(
      UUID organizationId, UUID projectId, UUID repositoryId, GenerateRequest request) {

    requireProjectAccess(organizationId, projectId, ProjectAccessClient.Access.WRITE);
    var user = requireCurrentUser();
    var token = currentBearerToken();

    var context = new GitContentClient.Context(organizationId, projectId, repositoryId);
    var ref = request.ref() == null || request.ref().isBlank()
        // git-service owns the repository's default branch; "main" is only a convention.
        ? gitContent.defaultBranch(context, token)
        : request.ref().trim();

    var docSet = docSets.save(DocSetEntity.builder()
        .projectId(projectId)
        .organizationId(organizationId)
        .repositoryId(repositoryId)
        .ref(ref)
        .status(DocSetStatus.RUNNING)
        .generatedBy(user.id())
        .build());

    List<RepositoryFile> files;
    try {
      files = gitContent.filesToAnalyse(context, ref, null, token);
    } catch (GitContentClient.GitServiceUnavailableException ex) {
      // Recorded through a separate bean in its own transaction: saving it here and rethrowing
      // would roll the row back with everything else, leaving an outage with no trace.
      failureRecorder.recordFailure(
          organizationId, projectId, repositoryId, ref, user.id(), ex.getMessage());
      throw ex;
    }

    var produced = 0;
    for (var generator : generators) {
      String content;
      try {
        content = generator.generate(files);
      } catch (RuntimeException ex) {
        // Repository content is hostile input. One generator failing must not deny the caller the
        // others — a bug to fix, not a reason to produce nothing.
        log.error("Generator {} failed for repository {}: {}",
            generator.kind(), repositoryId, ex.getMessage(), ex);
        continue;
      }
      if (content == null || content.isBlank()) {
        // Nothing to say about this repository for this kind. A document saying nothing is worse
        // than its absence, because a reader cannot tell it apart from one that failed.
        continue;
      }
      documents.save(DocumentEntity.builder()
          .docSetId(docSet.getId())
          .kind(generator.kind())
          .title(generator.title())
          .content(content)
          .build());
      produced++;
    }

    docSet.setStatus(DocSetStatus.COMPLETED);
    docSet.setFilesScanned(files.size());
    docSet.setCompletedAt(Instant.now());

    log.info("Generated {} document(s) for repository {} at {} from {} file(s)",
        produced, repositoryId, ref, files.size());

    return DocSetResponse.from(docSets.save(docSet));
  }

  @Transactional(readOnly = true)
  public Page<DocSetResponse> list(
      UUID organizationId, UUID projectId, UUID repositoryId, Pageable pageable) {
    requireProjectAccess(organizationId, projectId, ProjectAccessClient.Access.READ);
    return docSets
        .findByRepositoryIdAndProjectIdOrderByCreatedAtDescIdAsc(repositoryId, projectId, pageable)
        .map(DocSetResponse::from);
  }

  @Transactional(readOnly = true)
  public DocSetResponse latest(UUID organizationId, UUID projectId, UUID repositoryId) {
    requireProjectAccess(organizationId, projectId, ProjectAccessClient.Access.READ);
    return docSets
        .findFirstByRepositoryIdAndProjectIdOrderByCreatedAtDescIdAsc(repositoryId, projectId)
        .map(DocSetResponse::from)
        .orElseThrow(() ->
            new ResourceNotFoundException("No documentation has been generated for this repository"));
  }

  @Transactional(readOnly = true)
  public List<DocumentResponse> documents(UUID organizationId, UUID projectId, UUID docSetId) {
    var docSet = load(organizationId, projectId, docSetId);
    return documents.findByDocSetIdOrderByKindAsc(docSet.getId()).stream()
        // The list omits content: a set of three documents is a lot of Markdown to send when the
        // caller only wants to know which exist.
        .map(DocumentResponse::summary)
        .toList();
  }

  @Transactional(readOnly = true)
  public DocumentResponse document(
      UUID organizationId, UUID projectId, UUID docSetId, DocumentKind kind) {
    var docSet = load(organizationId, projectId, docSetId);
    return documents.findByDocSetIdAndKind(docSet.getId(), kind)
        .map(DocumentResponse::full)
        .orElseThrow(() -> new ResourceNotFoundException(
            "This documentation set has no " + kind + " document"));
  }

  // ---------------------------------------------------------------- helpers

  private DocSetEntity load(UUID organizationId, UUID projectId, UUID docSetId) {
    requireProjectAccess(organizationId, projectId, ProjectAccessClient.Access.READ);
    return docSets.findByIdAndProjectId(docSetId, projectId)
        // 404 rather than 403 for a set in another project: a 403 would confirm the id exists.
        .orElseThrow(() -> new ResourceNotFoundException("Documentation set not found"));
  }

  private void requireProjectAccess(UUID organizationId, UUID projectId, ProjectAccessClient.Access level) {
    projectAccess.requireProjectAccess(organizationId, projectId, currentBearerToken(), level);
  }

  private AuthenticatedUser requireCurrentUser() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof AuthenticatedUser user) {
      return user;
    }
    throw new AccessDeniedException("Not authenticated");
  }

  private String currentBearerToken() {
    var attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servletAttributes) {
      HttpServletRequest request = servletAttributes.getRequest();
      var header = request.getHeader(HttpHeaders.AUTHORIZATION);
      if (header != null && !header.isBlank()) {
        return header;
      }
    }
    throw new AccessDeniedException("No bearer token on the current request");
  }
}
