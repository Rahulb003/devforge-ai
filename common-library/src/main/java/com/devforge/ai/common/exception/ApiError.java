package com.devforge.ai.common.exception;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiError {
  private final Instant timestamp;
  private final int status;
  private final String error;
  private final String message;
  private final String path;
  private final List<String> details;
}
