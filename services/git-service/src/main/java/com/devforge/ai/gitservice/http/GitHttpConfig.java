package com.devforge.ai.gitservice.http;

import com.devforge.ai.common.events.EventTypes;
import com.devforge.ai.common.events.KafkaTopics;
import com.devforge.ai.common.events.outbox.OutboxEventRecorder;
import com.devforge.ai.common.exception.ResourceNotFoundException;
import com.devforge.ai.common.security.AuthenticatedUser;
import com.devforge.ai.common.security.client.ProjectAccessClient;
import com.devforge.ai.gitservice.entity.RepositoryEntity;
import com.devforge.ai.gitservice.git.GitPaths;
import com.devforge.ai.gitservice.git.RepositoryStorage;
import com.devforge.ai.gitservice.repository.GitRepositoryRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.HashMap;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Git's smart HTTP protocol: {@code git clone https://<host>/api/v1/git/<org>/<project>/<repo>.git}.
 *
 * <p>The same rules as the REST API, enforced at the same authority. Cloning needs read access to
 * the project and pushing needs write access, both decided by project-service with the caller's
 * own (exchanged) token. A repository outside the caller's projects is 404, never 403.
 *
 * <p>Pushes get rules the UI already follows implicitly: the default branch cannot be deleted or
 * rewritten, and while a repository requires approvals its default branch only changes through a
 * merged pull request. Without that, a direct push would be a way around the merge rule.
 *
 * <p>Only the smart protocol is served. The "dumb" protocol hands out the repository's files
 * directly - config, hooks, everything - and is switched off.
 */
@Slf4j
@Configuration
public class GitHttpConfig {

  static final String PATH = "/api/v1/git";
  private static final String REPOSITORY_ATTRIBUTE = GitHttpConfig.class.getName() + ".repository";

  @Bean
  public FilterRegistrationBean<GitBasicAuthenticationFilter> gitBasicAuthenticationFilter(
      PersonalTokenExchangeClient exchangeClient) {
    var registration = new FilterRegistrationBean<>(new GitBasicAuthenticationFilter(exchangeClient));
    registration.addUrlPatterns(PATH + "/*");
    // Ahead of Spring Security (-100) and the request-context filter (-105), so both see the
    // rewritten request with its bearer token.
    registration.setOrder(-200);
    return registration;
  }

  @Bean
  public ServletRegistrationBean<GitServlet> gitServlet(
      GitRepositoryRepository repositories,
      RepositoryStorage storage,
      ProjectAccessClient projectAccess,
      OutboxEventRecorder outbox,
      TransactionTemplate transactionTemplate,
      @Value("${devforge.git.max-push-bytes:104857600}") long maxPushBytes,
      @Value("${devforge.git.max-object-bytes:52428800}") long maxObjectBytes) {

    var servlet = new GitServlet();
    servlet.setAsIsFileService(null);

    servlet.setRepositoryResolver((HttpServletRequest request, String name) -> {
      var entity = resolve(request, name, repositories, projectAccess);
      request.setAttribute(REPOSITORY_ATTRIBUTE, entity);
      try {
        return new FileRepositoryBuilder()
            .setGitDir(storage.directoryFor(entity.getOrganizationId(), entity.getId()).toFile())
            .setMustExist(true)
            .build();
      } catch (IOException ex) {
        // The row exists but the storage does not: the documented aftermath of a crash mid-create.
        throw new RepositoryNotFoundException(name, ex);
      }
    });

    servlet.setUploadPackFactory((request, db) -> {
      var upload = new UploadPack(db);
      upload.setTimeout(120);
      return upload;
    });

    servlet.setReceivePackFactory((request, db) -> {
      var entity = (RepositoryEntity) request.getAttribute(REPOSITORY_ATTRIBUTE);
      try {
        projectAccess.requireProjectAccess(entity.getOrganizationId(), entity.getProjectId(),
            request.getHeader("Authorization"), ProjectAccessClient.Access.WRITE);
      } catch (AccessDeniedException ex) {
        // 403, not 401: the credentials are fine, so git must not ask for different ones.
        throw new ServiceNotEnabledException("Your role in this project is read-only");
      }
      var user = currentUser();

      var receive = new ReceivePack(db);
      receive.setTimeout(120);
      receive.setMaxPackSizeLimit(maxPushBytes);
      receive.setMaxObjectSizeLimit(maxObjectBytes);
      // fsck on everything received: a malformed object would otherwise sit in the store and break
      // every later read of the repository, through the UI as well as git.
      receive.setCheckReceivedObjects(true);
      receive.setAllowNonFastForwards(true);
      receive.setRefLogIdent(new PersonIdent(user.username(), user.email()));
      receive.setPreReceiveHook((rp, commands) -> commands.forEach(c -> check(c, db, entity)));
      receive.setPostReceiveHook((rp, commands) -> transactionTemplate.executeWithoutResult(
          status -> commands.stream()
              .filter(c -> c.getResult() == ReceiveCommand.Result.OK)
              .filter(c -> c.getType() != ReceiveCommand.Type.DELETE)
              .filter(c -> c.getRefName().startsWith("refs/heads/"))
              .forEach(c -> recordPush(c, db, entity, user, outbox))));
      return receive;
    });

    var registration = new ServletRegistrationBean<>(servlet, PATH + "/*");
    registration.setName("git");
    return registration;
  }

  /** {@code <org>/<project>/<repo>.git} to the repository row, if the caller may read it. */
  private static RepositoryEntity resolve(
      HttpServletRequest request, String name, GitRepositoryRepository repositories,
      ProjectAccessClient projectAccess) throws RepositoryNotFoundException {
    var parts = name.split("/");
    if (parts.length != 3) {
      throw new RepositoryNotFoundException(name);
    }
    UUID organizationId;
    UUID projectId;
    UUID repositoryId;
    try {
      organizationId = UUID.fromString(parts[0]);
      projectId = UUID.fromString(parts[1]);
      repositoryId = UUID.fromString(parts[2].endsWith(".git")
          ? parts[2].substring(0, parts[2].length() - 4) : parts[2]);
    } catch (IllegalArgumentException ex) {
      throw new RepositoryNotFoundException(name);
    }
    try {
      // Membership first, so a repository in someone else's project is indistinguishable from one
      // that does not exist.
      projectAccess.requireProjectAccess(organizationId, projectId,
          request.getHeader("Authorization"), ProjectAccessClient.Access.READ);
    } catch (ResourceNotFoundException ex) {
      throw new RepositoryNotFoundException(name);
    }
    return repositories.findByIdAndProjectId(repositoryId, projectId)
        .filter(r -> r.getOrganizationId().equals(organizationId))
        .orElseThrow(() -> new RepositoryNotFoundException(name));
  }

  /** Refuses a ref update the rules do not allow; the rest of the push still applies. */
  static void check(ReceiveCommand command, Repository db, RepositoryEntity entity) {
    var ref = command.getRefName();
    if (ref.startsWith("refs/tags/")) {
      return;
    }
    if (!ref.startsWith("refs/heads/")) {
      command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "only branches and tags may be pushed");
      return;
    }
    var branch = ref.substring("refs/heads/".length());
    try {
      GitPaths.requireValidBranchName(branch);
    } catch (IllegalArgumentException ex) {
      command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, ex.getMessage());
      return;
    }
    if (!branch.equals(entity.getDefaultBranch())) {
      return;
    }
    switch (command.getType()) {
      case DELETE -> command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON,
          "the default branch cannot be deleted");
      case UPDATE_NONFASTFORWARD -> command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON,
          "the default branch cannot be rewritten; force-push to another branch instead");
      // Creating the default branch is the first push into an empty repository: there is no
      // history yet for a pull request to merge into.
      case CREATE -> { }
      default -> {
        if (entity.getRequiredApprovals() > 0) {
          command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON,
              "%s needs %d approval(s) to change: push a branch and open a pull request"
                  .formatted(branch, entity.getRequiredApprovals()));
        }
      }
    }
  }

  private static void recordPush(
      ReceiveCommand command, Repository db, RepositoryEntity entity, AuthenticatedUser user,
      OutboxEventRecorder outbox) {
    var payload = new HashMap<String, Object>();
    payload.put("repositoryId", entity.getId().toString());
    payload.put("projectId", entity.getProjectId().toString());
    payload.put("branch", command.getRefName().substring("refs/heads/".length()));
    payload.put("commitId", command.getNewId().name());
    payload.put("commitCount", newCommits(db, command.getOldId(), command.getNewId()));
    payload.put("via", "git");
    outbox.record(KafkaTopics.REPOSITORIES, EventTypes.REPOSITORY_PUSHED,
        entity.getOrganizationId(), user.id(), MDC.get("correlationId"), payload);
  }

  /** Commits the push added to the branch, capped so a huge import cannot stall the response. */
  static int newCommits(Repository db, ObjectId oldId, ObjectId newId) {
    try (var walk = new RevWalk(db)) {
      walk.markStart(walk.parseCommit(newId));
      if (!oldId.equals(ObjectId.zeroId())) {
        walk.markUninteresting(walk.parseCommit(oldId));
      }
      int count = 0;
      for (var ignored : walk) {
        if (++count >= 10_000) {
          break;
        }
      }
      return count;
    } catch (IOException ex) {
      log.warn("Could not count the commits in a push to {}", db.getDirectory(), ex);
      return 1;
    }
  }

  private static AuthenticatedUser currentUser() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
      return user;
    }
    throw new IllegalStateException("A git request reached the servlet unauthenticated");
  }
}
