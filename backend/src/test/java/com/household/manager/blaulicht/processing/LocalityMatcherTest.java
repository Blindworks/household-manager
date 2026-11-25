package com.household.manager.blaulicht.processing;

import com.household.manager.blaulicht.settings.BlaulichtSettings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalityMatcherTest {

    private final LocalityMatcher matcher = new LocalityMatcher(BlaulichtSettings.defaults().localities());

    @Test
    void hauptortMitOrtsteilImText() {
        assertThat(matcher.match("Bad Vilbel", "Einbruch", "… ein Einfamilienhaus in Massenheim ein."))
                .contains(new LocalityMatch("Massenheim", 100, false));
    }

    @Test
    void hauptortOhneOrtsteil() {
        assertThat(matcher.match("Bad Vilbel", "Einbruch", "Frankfurter Straße in Bad Vilbel"))
                .contains(new LocalityMatch("Bad Vilbel", 100, false));
    }

    @Test
    void strassennameMitOrtsteilZaehltNicht() {
        assertThat(matcher.match("Bad Vilbel", "Unfall", "in der Dortelweiler Straße"))
                .contains(new LocalityMatch("Bad Vilbel", 100, false));
    }

    @Test
    void nachbarortMitNiedrigerRelevanz() {
        assertThat(matcher.match("Karben", "Diebstähle auf Baustellen", "… Polizeistation Bad Vilbel …"))
                .contains(new LocalityMatch("Karben", 30, true));
    }

    @Test
    void fremderOrtWirdVerworfenAuchWennDieWacheInBadVilbelSitzt() {
        assertThat(matcher.match("Butzbach", "Tipps", "Hinweise an die Polizeistation Bad Vilbel")).isEmpty();
    }

    @Test
    void ohneKopfzeileZaehltNurDerHauptortNichtDerOrtsteilAllein() {
        assertThat(matcher.match(null, "Unfall", "In Bad Vilbel-Heilsberg krachte es"))
                .contains(new LocalityMatch("Heilsberg", 100, false));
        assertThat(matcher.match(null, "Unfall", "In Gronau krachte es")).isEmpty();
    }

    @Test
    void schreibweiseMitBindestrichOderLeerzeichen() {
        assertThat(matcher.match("Frankfurt Berkersheim", "Brand", "")).isPresent();
        assertThat(LocalityMatcher.containsWord("Bad-Vilbel", "Bad Vilbel")).isTrue();
        assertThat(LocalityMatcher.containsWord("Bad Vilbeler Höhenweg", "Bad Vilbel")).isFalse();
    }
}
