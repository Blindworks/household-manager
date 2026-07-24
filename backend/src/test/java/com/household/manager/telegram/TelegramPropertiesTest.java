package com.household.manager.telegram;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TelegramPropertiesTest {

    private TelegramProperties configured() {
        TelegramProperties props = new TelegramProperties();
        props.setBotToken("123:abc");
        props.setAnthropicApiKey("sk-test");
        props.setAllowedChatIds(List.of(42L));
        return props;
    }

    @Test
    void configuredOnlyWithTokenKeyAndAllowlist() {
        assertTrue(configured().isConfigured());
    }

    @Test
    void notConfiguredWhenAnythingMissing() {
        TelegramProperties noToken = configured();
        noToken.setBotToken(" ");
        assertFalse(noToken.isConfigured());

        TelegramProperties noKey = configured();
        noKey.setAnthropicApiKey("");
        assertFalse(noKey.isConfigured());

        TelegramProperties noChats = configured();
        noChats.setAllowedChatIds(List.of());
        assertFalse(noChats.isConfigured());

        TelegramProperties disabled = configured();
        disabled.setEnabled(false);
        assertFalse(disabled.isConfigured());
    }
}
