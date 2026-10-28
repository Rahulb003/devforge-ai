package com.devforge.ai.chatservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devforge.ai.common.security.client.ProjectAccessClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Resource servers hold no password users. Boot's fallback - an in-memory "user" with a generated
 * password, printed to the log at every start - must not appear in any service built on
 * common-security; chat-service stands in for all of them.
 */
@SpringBootTest
@DisplayName("No password users")
class NoPasswordUsersTest {

  @Autowired private ApplicationContext context;
  @Autowired private UserDetailsService users;
  @MockitoBean private ProjectAccessClient projectAccessClient;

  @Test
  @DisplayName("Boot generates no in-memory user, and the store refuses every name")
  void noGeneratedUser() {
    assertThat(context.getBeansOfType(InMemoryUserDetailsManager.class)).isEmpty();
    assertThatThrownBy(() -> users.loadUserByUsername("user"))
        .isInstanceOf(UsernameNotFoundException.class);
  }
}
