package com.household.manager.dto.alexa;

public record AlexaAuthStatusResponse(boolean loggedIn, String accountName, boolean reauthRequired,
                                      String loginError) {}
