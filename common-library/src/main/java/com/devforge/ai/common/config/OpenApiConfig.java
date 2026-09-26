package com.devforge.ai.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI openAPI() {
    return new OpenAPI()
        .components(new Components())
        .info(new Info()
            .title("DevForge AI API Gateway")
            .description("Enterprise AI developer workspace API gateway and management services")
            .version("0.1.0")
            .contact(new Contact().name("DevForge AI Platform Team").email("platform@devforge.ai"))
            .license(new License().name("Proprietary")));
  }
}
