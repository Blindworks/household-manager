package com.household.manager.dto.alexa;

/** status ist einer von OK, MFA_REQUIRED, CAPTCHA_REQUIRED, FAILED. */
public record AlexaLoginResponse(String status, String captchaImageUrl, String message) {}
