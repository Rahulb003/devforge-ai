package com.devforge.ai.aiservice.git;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Pull request diff rendering")
class PullRequestReaderTest {

  @Test
  @DisplayName("git-service's hunks become a unified diff, with its line types mapped to + - and space")
  void rendersUnifiedDiff() throws Exception {
    var fileDiff = new ObjectMapper().readTree("""
        {"path":"a.ts","changeType":"MODIFY","binary":false,"truncated":false,"hunks":[
          {"oldStart":3,"newStart":3,"lines":[
            {"type":"CONTEXT","oldLine":3,"newLine":3,"text":"const a = 1;"},
            {"type":"DELETE","oldLine":4,"newLine":null,"text":"const b = 2;"},
            {"type":"ADD","oldLine":null,"newLine":4,"text":"const b = 3;"}]}]}""");
    assertThat(PullRequestReader.unified(fileDiff))
        .isEqualTo("@@ -3 +3 @@\n const a = 1;\n-const b = 2;\n+const b = 3;\n");
  }
}
