package com.household.manager.flowengine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateComparatorTest {

    @Test
    void numericComparisonsParseStates() {
        assertTrue(StateComparator.matches("4.5", "<", "5"));
        assertFalse(StateComparator.matches("5.5", "<", "5"));
        assertTrue(StateComparator.matches("-150", "<", "-100"));
        assertTrue(StateComparator.matches("21.5", ">=", "21.5"));
        assertTrue(StateComparator.matches("7", ">", "5"));
        assertTrue(StateComparator.matches("5", "<=", "5"));
    }

    @Test
    void equalityWorksForStringsAndNumbers() {
        assertTrue(StateComparator.matches("on", "==", "on"));
        assertFalse(StateComparator.matches("on", "==", "off"));
        assertTrue(StateComparator.matches("5.0", "==", "5"));
        assertTrue(StateComparator.matches("on", "!=", "off"));
    }

    @Test
    void unavailableAndNonNumericNeverMatchNumericOperators() {
        assertFalse(StateComparator.matches("unavailable", "<", "5"));
        assertFalse(StateComparator.matches("unknown", ">", "5"));
        assertFalse(StateComparator.matches("on", "<", "5"));
        assertFalse(StateComparator.matches(null, "<", "5"));
    }

    @Test
    void unknownOperatorNeverMatches() {
        assertFalse(StateComparator.matches("5", "~", "5"));
    }
}
