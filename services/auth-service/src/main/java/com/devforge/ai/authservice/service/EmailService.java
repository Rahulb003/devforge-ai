package com.devforge.ai.authservice.service;

public interface EmailService {
  void sendEmailVerification(String email, String token);
  void sendPasswordReset(String email, String token);
  void sendWelcomeEmail(String email);
}
