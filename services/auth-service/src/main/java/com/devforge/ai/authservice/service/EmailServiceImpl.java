package com.devforge.ai.authservice.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

  private static final Logger logger = LoggerFactory.getLogger(EmailServiceImpl.class);
  private final JavaMailSender mailSender;

  @Override
  public void sendEmailVerification(String email, String token) {
    var message = new SimpleMailMessage();
    message.setTo(email);
    message.setSubject("Verify your DevForge AI email");
    message.setText("Please verify your email by visiting: https://app.devforge.ai/auth/verify?token=" + token);
    mailSender.send(message);
    logger.info("Sent verification email to {}", email);
  }

  @Override
  public void sendPasswordReset(String email, String token) {
    var message = new SimpleMailMessage();
    message.setTo(email);
    message.setSubject("Reset your DevForge AI password");
    message.setText("Reset your password by visiting: https://app.devforge.ai/auth/reset-password?token=" + token);
    mailSender.send(message);
    logger.info("Sent password reset email to {}", email);
  }

  @Override
  public void sendWelcomeEmail(String email) {
    var message = new SimpleMailMessage();
    message.setTo(email);
    message.setSubject("Welcome to DevForge AI");
    message.setText("Welcome to DevForge AI. Your account was created successfully.");
    mailSender.send(message);
    logger.info("Sent welcome email to {}", email);
  }
}
