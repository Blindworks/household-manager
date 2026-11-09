package com.household.manager.finance;

import com.household.manager.dto.RuleConditionDto;
import com.household.manager.model.entity.RuleMatchField;
import com.household.manager.model.entity.RuleMatchType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaymentIntermediaryTest {

    private static RuleConditionDto cond(RuleMatchField f, String p) {
        return RuleConditionDto.builder().field(f).matchType(RuleMatchType.CONTAINS).pattern(p).build();
    }

    @Test
    void detectsPaypal() {
        assertEquals(PaymentIntermediary.PAYPAL,
                PaymentIntermediary.detect("PayPal Europe S.a.r.l. et Cie S.C.A").orElseThrow());
    }

    @Test
    void detectsAmazonByAmznToken() {
        assertEquals(PaymentIntermediary.AMAZON, PaymentIntermediary.detect("AMZN Mktp DE").orElseThrow());
        assertEquals("AMZN", PaymentIntermediary.matchedToken("AMZN Mktp DE"));
    }

    @Test
    void ignoresOtherAndNull() {
        assertTrue(PaymentIntermediary.detect("REWE").isEmpty());
        assertTrue(PaymentIntermediary.detect(null).isEmpty());
        assertNull(PaymentIntermediary.matchedToken("REWE"));
    }

    @Test
    void warnsOnlyWhenAllConditionsAreCounterpartyNameWithToken() {
        assertEquals("Diese Regel trifft alle PayPal-Zahlungen.",
                PaymentIntermediary.broadRuleWarning(List.of(cond(RuleMatchField.COUNTERPARTY_NAME, "PAYPAL"))));
        assertNull(PaymentIntermediary.broadRuleWarning(List.of(
                cond(RuleMatchField.COUNTERPARTY_NAME, "PAYPAL"), cond(RuleMatchField.PURPOSE, "shell"))));
        assertNull(PaymentIntermediary.broadRuleWarning(List.of(cond(RuleMatchField.COUNTERPARTY_NAME, "REWE"))));
    }
}
