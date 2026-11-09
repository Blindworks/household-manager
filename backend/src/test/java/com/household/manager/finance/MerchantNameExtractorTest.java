package com.household.manager.finance;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MerchantNameExtractorTest {

    private final MerchantNameExtractor extractor = new MerchantNameExtractor();

    @Test
    void extractsShellFromRealPaypalPurpose() {
        assertEquals(Optional.of("SHELL DEUTSCHLAND"), extractor.extract(PaymentIntermediary.PAYPAL,
                "1053795742551/. SHELL DEUTSCHLAND GmbH, Ihr Einkauf bei SHELL DEUTSCHLAND GmbH"));
        assertEquals(Optional.of("SHELL DEUTSCHLAND"), extractor.extract(PaymentIntermediary.PAYPAL,
                "1053839646834/. SHELL DEUTSCHLAND GmbH, Ihr Einkauf bei SHELL DEUTSCHLAND GmbH"));
    }

    @Test
    void stripsStackedLegalForms() {
        assertEquals(Optional.of("MUSTER"),
                extractor.extract(PaymentIntermediary.PAYPAL, "Ihr Einkauf bei Muster GmbH & Co. KG"));
    }

    @Test
    void paypalWithoutMerchantPhraseReturnsEmpty() {
        assertEquals(Optional.empty(), extractor.extract(PaymentIntermediary.PAYPAL, "1053795742551/. Referenz 4711"));
        assertEquals(Optional.empty(), extractor.extract(PaymentIntermediary.PAYPAL, null));
    }

    @Test
    void amazonAlwaysEmpty() {
        assertEquals(Optional.empty(),
                extractor.extract(PaymentIntermediary.AMAZON, "Ihr Einkauf bei Foo GmbH"));
    }
}
