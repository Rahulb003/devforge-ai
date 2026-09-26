package com.devforge.ai.common.web;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ApiResponse<T> {
  private final boolean success;
  private final T data;
  private final String message;
}
