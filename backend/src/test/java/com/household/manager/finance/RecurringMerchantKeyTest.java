package com.household.manager.finance;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RecurringMerchantKeyTest {

    private final RecurringMerchantKey key =
            new RecurringMerchantKey(new CounterpartyNameNormalizer(), new MerchantNameExtractor());

    @Test
    void paypalUsesMerchantFromPurpose() {
        String purpose = ". Spotify AB, Ihr Einkauf bei Spotify AB";
        String merchant = new MerchantNameExtractor().extract(PaymentIntermediary.PAYPAL, purpose).get();

        assertEquals(Optional.of("PAYPAL:" + merchant),
                key.keyFor("PayPal Europe S.a.r.l. et Cie S.C.A", purpose));
    }

    @Test
    void paypalWithoutMerchantIsSkipped() {
        assertEquals(Optional.empty(), key.keyFor("PayPal Europe S.a.r.l. et Cie S.C.A", ". , Ihr Einkauf bei "));
    }

    @Test
    void plainCounterpartyIsNormalized() {
        assertEquals(Optional.of("DRILLISCH ONLINE GMBH"), key.keyFor("DRILLISCH ONLINE GMBH", "C8645003 U8377 Mobilfunk"));
        assertEquals(Optional.of("ANTHROPIC. CLAUDE SUB"), key.keyFor("ANTHROPIC. CLAUDE SUB//ANTHROPIC.COM/US/3", null));
    }

    @Test
    void blankCounterpartyIsSkipped() {
        assertEquals(Optional.empty(), key.keyFor(null, null));
        assertEquals(Optional.empty(), key.keyFor("   ", "x"));
    }

    @Test
    void keyIsCappedAt255() {
        assertEquals(255, key.keyFor("A".repeat(300), null).orElseThrow().length());
    }

    @Test
    void whitespaceInMerchantCollapsed() {
        String k = key.keyFor("PayPal Europe", "Ihr Einkauf bei Foo   Bar").orElseThrow();
        assertFalse(k.contains("  "));
        assertEquals("PAYPAL:FOO BAR", k);
    }
}
