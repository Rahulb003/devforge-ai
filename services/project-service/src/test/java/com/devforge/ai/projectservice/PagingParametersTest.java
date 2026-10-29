package com.devforge.ai.projectservice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The paging parameters every list takes - ?page, ?size, ?sort - are client input. Each of these
 * was a 500 on every paged list in every service; project-service's list stands in for them all,
 * since the handling lives in common-library.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Paging parameters")
class PagingParametersTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  private final UUID owner = UUID.randomUUID();
  private String orgPath;

  private String bearer() {
    return "Bearer " + TestTokens.accessTokenWithEmail(owner, "owner@example.com", true);
  }

  @BeforeEach
  void setUp() throws Exception {
    var json = mockMvc.perform(post("/api/v1/organizations")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("name", "Paging", "slug", "paging-" + owner))))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    orgPath = "/api/v1/organizations/" + objectMapper.readTree(json).path("data").path("id").asText();
  }

  @Test
  @DisplayName("sorting by a field that does not exist is a 400 that does not name the entity")
  void unknownSort() throws Exception {
    mockMvc.perform(get(orgPath + "/projects").param("sort", "password,desc")
            .header(HttpHeaders.AUTHORIZATION, bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Cannot sort by 'password'"));
  }

  @Test
  @DisplayName("a page whose offset would overflow is a 400")
  void pageOutOfRange() throws Exception {
    mockMvc.perform(get(orgPath + "/projects").param("page", "99999999").param("size", "99999999")
            .header(HttpHeaders.AUTHORIZATION, bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("That page is out of range"));
  }

  @Test
  @DisplayName("a huge page size is capped, not obeyed")
  void sizeIsCapped() throws Exception {
    mockMvc.perform(get(orgPath + "/projects").param("size", "5000")
            .header(HttpHeaders.AUTHORIZATION, bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.size").value(100));
  }
}
