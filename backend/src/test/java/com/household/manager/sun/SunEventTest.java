package com.household.manager.sun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SunEventTest {

    @Test
    void fromKeyAcceptsTheFourKeywordsCaseInsensitiveAndTrimmed() {
        assertEquals(Optional.of(SunEvent.DAWN), SunEvent.fromKey("dawn"));
        assertEquals(Optional.of(SunEvent.SUNRISE), SunEvent.fromKey(" sunrise "));
        assertEquals(Optional.of(SunEvent.SUNSET), SunEvent.fromKey("SUNSET"));
        assertEquals(Optional.of(SunEvent.DUSK), SunEvent.fromKey("dusk"));
    }

    @Test
    void fromKeyRejectsUnknownAndNull() {
        assertTrue(SunEvent.fromKey("noon").isEmpty());
        assertTrue(SunEvent.fromKey("").isEmpty());
        assertTrue(SunEvent.fromKey(null).isEmpty());
    }

    @Test
    void keysListsAllFourInStableOrder() {
        assertEquals(List.of("dawn", "sunrise", "sunset", "dusk"), SunEvent.keys());
    }
}
