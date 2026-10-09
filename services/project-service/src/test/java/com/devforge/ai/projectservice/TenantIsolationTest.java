package com.devforge.ai.projectservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.projectservice.repository.OrganizationMemberRepository;
import com.devforge.ai.projectservice.repository.OrganizationRepository;
import com.devforge.ai.projectservice.repository.ProjectMemberRepository;
import com.devforge.ai.projectservice.repository.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Tenant isolation and IDOR/BOLA coverage.
 *
 * <p>Two unrelated users each own an organization with a project. Every assertion here is a
 * variation on one question: can user A reach anything belonging to user B? A failure in this
 * class is a cross-tenant data breach, not a cosmetic bug.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantIsolationTest {

  private static final String ORGS = "/api/v1/organizations";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OrganizationMemberRepository organizationMemberRepository;
  @Autowired private ProjectRepository projectRepository;
  @Autowired private ProjectMemberRepository projectMemberRepository;
  @Autowired private com.devforge.ai.common.events.outbox.OutboxEventRepository outboxEvents;

  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  private String aliceToken;
  private String bobToken;
  private UUID aliceOrgId;
  private UUID bobOrgId;
  private UUID aliceProjectId;
  private UUID bobProjectId;

  @BeforeEach
  void setUp() throws Exception {
    outboxEvents.deleteAll();
    projectMemberRepository.deleteAll();
    projectRepository.deleteAll();
    organizationMemberRepository.deleteAll();
    organizationRepository.deleteAll();

    aliceToken = TestTokens.accessToken(alice);
    bobToken = TestTokens.accessToken(bob);

    aliceOrgId = createOrganization(aliceToken, "Alice Corp", "alice-corp");
    bobOrgId = createOrganization(bobToken, "Bob Industries", "bob-industries");
    aliceProjectId = createProject(aliceToken, aliceOrgId, "Alice Project", "ALICE");
    bobProjectId = createProject(bobToken, bobOrgId, "Bob Project", "BOB");
  }

  private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder builder, String token) {
    return builder.header("Authorization", "Bearer " + token);
  }

  private UUID createOrganization(String token, String name, String slug) throws Exception {
    var body = objectMapper.writeValueAsString(
        Map.of("name", name, "slug", slug, "description", "test"));
    var result = mockMvc.perform(asUser(post(ORGS), token)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andReturn();
    return UUID.fromString(
        objectMapper.readTree(result.getResponse().getContentAsString())
            .get("data").get("id").asText());
  }

  private UUID createProject(String token, UUID orgId, String name, String key) throws Exception {
    var body = objectMapper.writeValueAsString(
        Map.of("name", name, "projectKey", key, "description", "test"));
    var result = mockMvc.perform(asUser(post(ORGS + "/" + orgId + "/projects"), token)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andReturn();
    return UUID.fromString(
        objectMapper.readTree(result.getResponse().getContentAsString())
            .get("data").get("id").asText());
  }

  // --- Authentication -----------------------------------------------------

  @Nested
  @DisplayName("Authentication")
  class Authentication {

    @Test
    @DisplayName("an unauthenticated request is refused")
    void anonymousIsRejected() throws Exception {
      mockMvc.perform(get(ORGS)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a forged token signed with another key is refused")
    void forgedSignatureIsRejected() throws Exception {
      mockMvc.perform(asUser(get(ORGS), TestTokens.wronglySignedToken(alice)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token from another issuer is refused")
    void wrongIssuerIsRejected() throws Exception {
      mockMvc.perform(asUser(get(ORGS), TestTokens.wrongIssuerToken(alice)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an expired token is refused")
    void expiredTokenIsRejected() throws Exception {
      mockMvc.perform(asUser(get(ORGS), TestTokens.expiredToken(alice)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a refresh token cannot be used as an API credential")
    void refreshTokenIsRejected() throws Exception {
      // A 14-day refresh token presented as a bearer credential must not authenticate.
      mockMvc.perform(asUser(get(ORGS), TestTokens.refreshToken(alice)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a structurally invalid token is refused")
    void garbageTokenIsRejected() throws Exception {
      mockMvc.perform(asUser(get(ORGS), "not-a-jwt")).andExpect(status().isUnauthorized());
    }
  }

  // --- Cross-tenant reads -------------------------------------------------

  @Nested
  @DisplayName("Cross-tenant reads")
  class CrossTenantReads {

    @Test
    @DisplayName("listing returns only the caller's own organizations")
    void listingIsScopedToCaller() throws Exception {
      mockMvc.perform(asUser(get(ORGS), aliceToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.length()").value(1))
          .andExpect(jsonPath("$.data[0].id").value(aliceOrgId.toString()));
    }

    @Test
    @DisplayName("another tenant's organization reports 404, not 403")
    void foreignOrganizationIsNotFound() throws Exception {
      // 404 rather than 403 on purpose: 403 would confirm the id is real.
      mockMvc.perform(asUser(get(ORGS + "/" + bobOrgId), aliceToken))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("another tenant's project cannot be read by id")
    void foreignProjectIsNotFound() throws Exception {
      mockMvc.perform(asUser(get(ORGS + "/" + bobOrgId + "/projects/" + bobProjectId), aliceToken))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a foreign project id cannot be smuggled in under the caller's own organization")
    void foreignProjectIdUnderOwnOrgIsNotFound() throws Exception {
      // The classic IDOR attempt: keep the tenant you are allowed to use, swap in a
      // resource id from another tenant.
      mockMvc.perform(
              asUser(get(ORGS + "/" + aliceOrgId + "/projects/" + bobProjectId), aliceToken))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("another tenant's project list is not readable")
    void foreignProjectListIsNotFound() throws Exception {
      mockMvc.perform(asUser(get(ORGS + "/" + bobOrgId + "/projects"), aliceToken))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("another tenant's project membership is not readable")
    void foreignProjectMembersAreNotFound() throws Exception {
      mockMvc.perform(
              asUser(get(ORGS + "/" + bobOrgId + "/projects/" + bobProjectId + "/members"), aliceToken))
          .andExpect(status().isNotFound());
    }
  }

  // --- Cross-tenant writes ------------------------------------------------

  @Nested
  @DisplayName("Cross-tenant writes")
  class CrossTenantWrites {

    @Test
    @DisplayName("another tenant's project cannot be updated")
    void foreignProjectCannotBeUpdated() throws Exception {
      var body = objectMapper.writeValueAsString(Map.of("name", "Hijacked"));
      mockMvc.perform(asUser(patch(ORGS + "/" + bobOrgId + "/projects/" + bobProjectId), aliceToken)
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isNotFound());

      // Confirm at the database level, not just by status code.
      assertThat(projectRepository.findById(bobProjectId).orElseThrow().getName())
          .isEqualTo("Bob Project");
    }

    @Test
    @DisplayName("another tenant's project cannot be deleted")
    void foreignProjectCannotBeDeleted() throws Exception {
      mockMvc.perform(asUser(delete(ORGS + "/" + bobOrgId + "/projects/" + bobProjectId), aliceToken))
          .andExpect(status().isNotFound());

      assertThat(projectRepository.findById(bobProjectId)).isPresent();
    }

    @Test
    @DisplayName("another tenant's organization cannot be deleted")
    void foreignOrganizationCannotBeDeleted() throws Exception {
      mockMvc.perform(asUser(delete(ORGS + "/" + bobOrgId), aliceToken))
          .andExpect(status().isNotFound());

      assertThat(organizationRepository.findById(bobOrgId)).isPresent();
    }

    @Test
    @DisplayName("a project cannot be created inside another tenant's organization")
    void cannotCreateProjectInForeignOrganization() throws Exception {
      var body = objectMapper.writeValueAsString(
          Map.of("name", "Intruder", "projectKey", "INTRUDE", "description", ""));
      mockMvc.perform(asUser(post(ORGS + "/" + bobOrgId + "/projects"), aliceToken)
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isNotFound());

      assertThat(projectRepository.findByOrganizationId(bobOrgId,
          org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("a user cannot add themselves to another tenant's project")
    void cannotAddSelfToForeignProject() throws Exception {
      var body = objectMapper.writeValueAsString(
          Map.of("userId", alice.toString(), "role", "ADMIN"));
      mockMvc.perform(
              asUser(post(ORGS + "/" + bobOrgId + "/projects/" + bobProjectId + "/members"), aliceToken)
                  .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isNotFound());

      assertThat(projectMemberRepository.existsByProjectIdAndUserId(bobProjectId, alice)).isFalse();
    }
  }

  // --- Happy path ---------------------------------------------------------

  @Nested
  @DisplayName("Own tenant")
  class OwnTenant {

    @Test
    @DisplayName("creating, granting and revoking access are each published for the audit trail")
    void changesArePublished() throws Exception {
      // setUp created two organizations and two projects.
      assertThat(outboxEvents.findAll())
          .filteredOn(row -> row.getEventType().equals("ProjectCreated"))
          .hasSize(2)
          .allSatisfy(row -> assertThat(row.getTopic()).isEqualTo("devforge.projects.v1"));
      outboxEvents.deleteAll();

      // Bob joins Alice's organization; Alice then adds him to her project as a VIEWER.
      organizationMemberRepository.save(
          com.devforge.ai.projectservice.entity.OrganizationMemberEntity.builder()
              .organization(organizationRepository.findById(aliceOrgId).orElseThrow())
              .userId(bob)
              .role(com.devforge.ai.projectservice.model.OrganizationRole.MEMBER)
              .build());
      var members = ORGS + "/" + aliceOrgId + "/projects/" + aliceProjectId + "/members";
      mockMvc.perform(asUser(post(members), aliceToken)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(
                  Map.of("userId", bob.toString(), "role", "VIEWER"))))
          .andExpect(status().isCreated());
      mockMvc.perform(asUser(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
              .delete(members + "/" + bob), aliceToken))
          .andExpect(status().is2xxSuccessful());

      var events = outboxEvents.findAll();
      assertThat(events).extracting(row -> row.getEventType())
          .containsExactlyInAnyOrder("ProjectMemberAdded", "ProjectMemberRemoved");
      assertThat(events).allSatisfy(row -> {
        assertThat(row.getPayload()).contains(bob.toString(), "\"role\":\"VIEWER\"");
        assertThat(row.getPartitionKey()).isEqualTo(aliceOrgId.toString());
      });
    }

    @Test
    @DisplayName("the owner can read their own project")
    void ownerReadsOwnProject() throws Exception {
      mockMvc.perform(asUser(get(ORGS + "/" + aliceOrgId + "/projects/" + aliceProjectId), aliceToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.projectKey").value("ALICE"))
          .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    @DisplayName("the owner can update their own project")
    void ownerUpdatesOwnProject() throws Exception {
      var body = objectMapper.writeValueAsString(Map.of("name", "Renamed"));
      mockMvc.perform(asUser(patch(ORGS + "/" + aliceOrgId + "/projects/" + aliceProjectId), aliceToken)
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.name").value("Renamed"));
    }

    @Test
    @DisplayName("a duplicate project key within the organization is rejected")
    void duplicateProjectKeyRejected() throws Exception {
      var body = objectMapper.writeValueAsString(
          Map.of("name", "Another", "projectKey", "ALICE", "description", ""));
      mockMvc.perform(asUser(post(ORGS + "/" + aliceOrgId + "/projects"), aliceToken)
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("the same project key is allowed in a different organization")
    void sameKeyAllowedAcrossOrganizations() throws Exception {
      // Keys are unique per tenant, not globally: two customers may both want "CORE".
      var body = objectMapper.writeValueAsString(
          Map.of("name", "Bob's Alice", "projectKey", "ALICE", "description", ""));
      mockMvc.perform(asUser(post(ORGS + "/" + bobOrgId + "/projects"), bobToken)
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a duplicate organization slug is rejected")
    void duplicateSlugRejected() throws Exception {
      var body = objectMapper.writeValueAsString(
          Map.of("name", "Impostor", "slug", "alice-corp", "description", ""));
      mockMvc.perform(asUser(post(ORGS), bobToken)
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an invalid slug is rejected by validation")
    void invalidSlugRejected() throws Exception {
      var body = objectMapper.writeValueAsString(
          Map.of("name", "Bad", "slug", "Not A Slug!", "description", ""));
      mockMvc.perform(asUser(post(ORGS), aliceToken)
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    }
  }
}
