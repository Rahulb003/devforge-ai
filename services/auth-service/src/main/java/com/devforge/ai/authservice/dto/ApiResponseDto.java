package com.devforge.ai.authservice.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiResponseDto<T> {
  private final boolean success;
  private final T data;
  private final String message;
}
