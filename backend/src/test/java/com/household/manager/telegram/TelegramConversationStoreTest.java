package com.household.manager.telegram;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TelegramConversationStoreTest {

    private final Instant start = Instant.parse("2026-07-24T10:00:00Z");

    private TelegramProperties props(int maxMessages, long ttlMinutes) {
        TelegramProperties p = new TelegramProperties();
        p.setHistoryMaxMessages(maxMessages);
        p.setHistoryTtlMinutes(ttlMinutes);
        return p;
    }

    @Test
    void storesExchangesAsPlainTextPairs() {
        MutableClock clock = new MutableClock(start);
        TelegramConversationStore store = new TelegramConversationStore(props(20, 30), clock);

        store.appendExchange(1L, "Licht an?", "Ist an.");

        List<AnthropicMessage> history = store.history(1L);
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("assistant", history.get(1).role());
    }

    @Test
    void expiresAfterTtl() {
        MutableClock clock = new MutableClock(start);
        TelegramConversationStore store = new TelegramConversationStore(props(20, 30), clock);
        store.appendExchange(1L, "a", "b");

        clock.advance(Duration.ofMinutes(31));

        assertTrue(store.history(1L).isEmpty());
    }

    @Test
    void trimsToMaxMessagesKeepingTheNewest() {
        MutableClock clock = new MutableClock(start);
        TelegramConversationStore store = new TelegramConversationStore(props(4, 30), clock);
        store.appendExchange(1L, "u1", "a1");
        store.appendExchange(1L, "u2", "a2");
        store.appendExchange(1L, "u3", "a3");

        List<AnthropicMessage> history = store.history(1L);

        assertEquals(4, history.size());
        assertEquals("u2", history.get(0).content().get(0).get("text"));
    }

    @Test
    void chatsAreIsolated() {
        TelegramConversationStore store = new TelegramConversationStore(props(20, 30), new MutableClock(start));
        store.appendExchange(1L, "a", "b");

        assertTrue(store.history(2L).isEmpty());
    }

    /** Verstellbare Uhr für TTL-Tests. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
