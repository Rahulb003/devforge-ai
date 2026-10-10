package com.devforge.ai.projectservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devforge.ai.common.events.outbox.OutboxEventRepository;
import com.devforge.ai.projectservice.repository.OrganizationInvitationRepository;
import com.devforge.ai.projectservice.repository.OrganizationMemberRepository;
import com.devforge.ai.projectservice.repository.OrganizationRepository;
import com.devforge.ai.projectservice.repository.ProjectMemberRepository;
import com.devforge.ai.projectservice.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Joining an organization by invitation, and managing who is in it. */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Organization membership")
class MembershipTest {

  private static final String ORGS = "/api/v1/organizations";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrganizationMemberRepository members;
  @Autowired private OrganizationInvitationRepository invitations;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectMemberRepository projectMembers;
  @Autowired private OutboxEventRepository outbox;

  private final UUID owner = UUID.randomUUID();
  private final UUID grace = UUID.randomUUID();
  private final UUID mallory = UUID.randomUUID();
  private String ownerToken;
  private String graceToken;
  private UUID org;

  @BeforeEach
  void setUp() throws Exception {
    outbox.deleteAll();
    invitations.deleteAll();
    projectMembers.deleteAll();
    projects.deleteAll();
    members.deleteAll();
    organizations.deleteAll();

    ownerToken = TestTokens.accessTokenWithEmail(owner, "owner@example.com", true);
    graceToken = TestTokens.accessTokenWithEmail(grace, "grace@example.com", true);
    org = UUID.fromString(data(send(post(ORGS), ownerToken,
        Map.of("name", "Acme", "slug", "acme-" + UUID.randomUUID().toString().substring(0, 8))))
        .path("id").asText());
  }

  private ResultActions send(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
    request.header("Authorization", "Bearer " + token);
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }
    return mockMvc.perform(request);
  }

  private JsonNode data(ResultActions result) throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
  }

  private UUID invite(String email, String role) throws Exception {
    return UUID.fromString(data(send(post(ORGS + "/" + org + "/invitations"), ownerToken,
        Map.of("email", email, "role", role)).andExpect(status().isCreated())).path("id").asText());
  }

  /** Grace, invited and joined as the given role. */
  private void graceJoins(String role) throws Exception {
    var id = invite("grace@example.com", role);
    send(post("/api/v1/invitations/" + id + "/accept"), graceToken, null).andExpect(status().isOk());
  }

  @Nested
  @DisplayName("joining by invitation")
  class Joining {

    @Test
    @DisplayName("the invited address sees the invitation, accepts it, and is a member with its name")
    void inviteAndAccept() throws Exception {
      // Mixed case on the way in: addresses are compared without case.
      var id = invite("Grace@Example.com", "MEMBER");

      send(get("/api/v1/invitations"), graceToken, null)
          .andExpect(jsonPath("$.data.length()").value(1))
          .andExpect(jsonPath("$.data[0].organizationName").value("Acme"))
          .andExpect(jsonPath("$.data[0].role").value("MEMBER"));

      send(post("/api/v1/invitations/" + id + "/accept"), graceToken, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.role").value("MEMBER"));

      // Grace now sees the organization, and everyone sees her by name.
      send(get(ORGS), graceToken, null).andExpect(jsonPath("$.data[0].name").value("Acme"));
      send(get(ORGS + "/" + org + "/members"), ownerToken, null)
          .andExpect(jsonPath("$.data.length()").value(2))
          .andExpect(jsonPath("$.data[?(@.email == 'grace@example.com')].username")
              .value("user-" + grace.toString().substring(0, 8)));
      send(get("/api/v1/invitations"), graceToken, null).andExpect(jsonPath("$.data.length()").value(0));
      assertThat(outbox.findAll()).anyMatch(e -> e.getEventType().equals("OrganizationMemberAdded"));
    }

    @Test
    @DisplayName("an unverified account under the invited address cannot see or take the invitation")
    void unverifiedAddressIsNotEnough() throws Exception {
      var id = invite("grace@example.com", "ADMIN");
      // Anyone can register an account under someone else's address; only verifying proves it.
      var impostor = TestTokens.accessTokenWithEmail(mallory, "grace@example.com", false);

      send(get("/api/v1/invitations"), impostor, null).andExpect(jsonPath("$.data.length()").value(0));
      send(post("/api/v1/invitations/" + id + "/accept"), impostor, null).andExpect(status().isNotFound());
      assertThat(members.existsByOrganizationIdAndUserId(org, mallory)).isFalse();
    }

    @Test
    @DisplayName("an invitation for someone else is not found, even with its id")
    void otherAddressesCannotAccept() throws Exception {
      var id = invite("grace@example.com", "MEMBER");
      var other = TestTokens.accessTokenWithEmail(mallory, "mallory@example.com", true);
      send(post("/api/v1/invitations/" + id + "/accept"), other, null).andExpect(status().isNotFound());
      send(post("/api/v1/invitations/" + id + "/decline"), other, null).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a revoked or declined invitation can no longer be accepted")
    void revokedAndDeclined() throws Exception {
      var revoked = invite("grace@example.com", "MEMBER");
      send(delete(ORGS + "/" + org + "/invitations/" + revoked), ownerToken, null).andExpect(status().isOk());
      send(post("/api/v1/invitations/" + revoked + "/accept"), graceToken, null).andExpect(status().isNotFound());

      var declined = invite("grace@example.com", "MEMBER");
      send(post("/api/v1/invitations/" + declined + "/decline"), graceToken, null).andExpect(status().isOk());
      send(post("/api/v1/invitations/" + declined + "/accept"), graceToken, null).andExpect(status().isNotFound());
      assertThat(members.existsByOrganizationIdAndUserId(org, grace)).isFalse();
    }

    @Test
    @DisplayName("invitations are validated, and only managers send them")
    void inviteRules() throws Exception {
      var path = ORGS + "/" + org + "/invitations";
      send(post(path), ownerToken, Map.of("email", "not-an-address")).andExpect(status().isBadRequest());
      send(post(path), ownerToken, Map.of("email", "x@example.com", "role", "OWNER")).andExpect(status().isBadRequest());
      send(post(path), ownerToken, Map.of("email", "owner@example.com")).andExpect(status().isConflict());
      invite("dup@example.com", "MEMBER");
      send(post(path), ownerToken, Map.of("email", "DUP@example.com")).andExpect(status().isConflict());

      graceJoins("MEMBER");
      // A plain member cannot invite; someone outside the organization cannot tell it exists.
      send(post(path), graceToken, Map.of("email", "new@example.com")).andExpect(status().isForbidden());
      var outsider = TestTokens.accessTokenWithEmail(mallory, "mallory@example.com", true);
      send(post(path), outsider, Map.of("email", "new@example.com")).andExpect(status().isNotFound());
      send(get(ORGS + "/" + org + "/members"), outsider, null).andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("managing members")
  class Managing {

    @Test
    @DisplayName("an admin cannot make an owner; an owner can")
    void ownersAreMadeByOwners() throws Exception {
      graceJoins("ADMIN");
      send(patch(ORGS + "/" + org + "/members/" + grace), graceToken, Map.of("role", "OWNER"))
          .andExpect(status().isForbidden());
      send(patch(ORGS + "/" + org + "/members/" + owner), graceToken, Map.of("role", "MEMBER"))
          .andExpect(status().isForbidden());
      send(patch(ORGS + "/" + org + "/members/" + grace), ownerToken, Map.of("role", "OWNER"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.role").value("OWNER"));
    }

    @Test
    @DisplayName("the last owner can be neither demoted nor removed, and cannot leave")
    void lastOwnerStays() throws Exception {
      send(patch(ORGS + "/" + org + "/members/" + owner), ownerToken, Map.of("role", "ADMIN"))
          .andExpect(status().isConflict());
      send(delete(ORGS + "/" + org + "/members/" + owner), ownerToken, null).andExpect(status().isConflict());

      // With a second owner, the first may step down and leave.
      graceJoins("ADMIN");
      send(patch(ORGS + "/" + org + "/members/" + grace), ownerToken, Map.of("role", "OWNER")).andExpect(status().isOk());
      send(delete(ORGS + "/" + org + "/members/" + owner), ownerToken, null).andExpect(status().isOk());
      assertThat(members.existsByOrganizationIdAndUserId(org, owner)).isFalse();
    }

    @Test
    @DisplayName("removing someone takes away their projects in the organization too")
    void removalRevokesProjectAccess() throws Exception {
      graceJoins("MEMBER");
      var project = UUID.fromString(data(send(post(ORGS + "/" + org + "/projects"), ownerToken,
          Map.of("name", "Apollo", "projectKey", "APL"))).path("id").asText());
      send(post(ORGS + "/" + org + "/projects/" + project + "/members"), ownerToken,
          Map.of("userId", grace.toString(), "role", "DEVELOPER")).andExpect(status().isCreated());
      send(get(ORGS + "/" + org + "/projects/" + project), graceToken, null).andExpect(status().isOk());

      send(delete(ORGS + "/" + org + "/members/" + grace), ownerToken, null).andExpect(status().isOk());

      assertThat(projectMembers.existsByProjectIdAndUserId(project, grace)).isFalse();
      send(get(ORGS + "/" + org + "/projects/" + project), graceToken, null).andExpect(status().isNotFound());
      assertThat(outbox.findAll()).anyMatch(e -> e.getEventType().equals("OrganizationMemberRemoved"));
    }

    @Test
    @DisplayName("a member can leave on their own, but cannot remove anyone else")
    void leaving() throws Exception {
      graceJoins("MEMBER");
      send(delete(ORGS + "/" + org + "/members/" + owner), graceToken, null).andExpect(status().isForbidden());
      send(delete(ORGS + "/" + org + "/members/" + grace), graceToken, null).andExpect(status().isOk());
      send(get(ORGS), graceToken, null).andExpect(jsonPath("$.data.length()").value(0));
    }
  }

  @Nested
  @DisplayName("leaving everything, before an account is deleted")
  class LeavingEverything {

    private static final String MINE = "/api/v1/memberships/mine";

    @Test
    @DisplayName("removes every organization and project membership the caller holds")
    void leavesEverything() throws Exception {
      graceJoins("MEMBER");
      var project = UUID.fromString(data(send(post(ORGS + "/" + org + "/projects"), ownerToken,
          Map.of("name", "Apollo", "projectKey", "APL"))).path("id").asText());
      send(post(ORGS + "/" + org + "/projects/" + project + "/members"), ownerToken,
          Map.of("userId", grace.toString(), "role", "DEVELOPER")).andExpect(status().isCreated());

      send(delete(MINE), graceToken, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.organizationsLeft").value(1));

      assertThat(members.existsByOrganizationIdAndUserId(org, grace)).isFalse();
      assertThat(projectMembers.existsByProjectIdAndUserId(project, grace)).isFalse();
      assertThat(members.existsByOrganizationIdAndUserId(org, owner)).isTrue();
      assertThat(outbox.findAll()).anyMatch(e -> e.getEventType().equals("OrganizationMemberRemoved")
          && e.getPayload().contains("account-deleted"));
    }

    @Test
    @DisplayName("refuses, changing nothing, while the caller is the only owner of an organization")
    void soleOwnerIsRefused() throws Exception {
      graceJoins("MEMBER");
      var second = UUID.fromString(data(send(post(ORGS), graceToken,
          Map.of("name", "Grace Labs", "slug", "labs-" + UUID.randomUUID().toString().substring(0, 8))))
          .path("id").asText());

      send(delete(MINE), ownerToken, null)
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.message").value(containsString("Acme")));
      send(delete(MINE), graceToken, null)
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.message").value(containsString("Grace Labs")));
      // All or nothing: Grace is still in Acme, where she is not an owner.
      assertThat(members.existsByOrganizationIdAndUserId(org, grace)).isTrue();
      assertThat(members.existsByOrganizationIdAndUserId(second, grace)).isTrue();
    }

    @Test
    @DisplayName("is allowed once another owner exists")
    void anotherOwnerUnblocks() throws Exception {
      graceJoins("ADMIN");
      send(patch(ORGS + "/" + org + "/members/" + grace), ownerToken, Map.of("role", "OWNER"))
          .andExpect(status().isOk());
      send(delete(MINE), ownerToken, null).andExpect(status().isOk());
      assertThat(members.existsByOrganizationIdAndUserId(org, owner)).isFalse();
    }
  }
}
