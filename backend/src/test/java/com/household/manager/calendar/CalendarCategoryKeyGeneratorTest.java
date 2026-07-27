package com.household.manager.calendar;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarCategoryKeyGeneratorTest {

    private final CalendarCategoryKeyGenerator generator = new CalendarCategoryKeyGenerator();

    @Test
    void kleinschreibtEinfacheNamen() {
        assertThat(generator.generate("Arbeit", Set.of())).isEqualTo("arbeit");
    }

    @Test
    void transliteriertUmlauteUndScharfesS() {
        assertThat(generator.generate("Bürogebäude Straße", Set.of()))
                .isEqualTo("buerogebaeude_strasse");
    }

    @Test
    void fasstSonderzeichenZuEinemTrennerZusammen() {
        assertThat(generator.generate("Sport & Freizeit!!", Set.of())).isEqualTo("sport_freizeit");
    }

    @Test
    void kuerztAufFuenfzigZeichen() {
        String lang = "a".repeat(80);
        assertThat(generator.generate(lang, Set.of())).hasSize(50);
    }

    @Test
    void faelltAufKategorieZurueckWennNichtsUebrigBleibt() {
        assertThat(generator.generate("🎉🎉", Set.of())).isEqualTo("kategorie");
    }

    @Test
    void haengtBeiKollisionEineZahlAn() {
        assertThat(generator.generate("Arbeit", Set.of("arbeit"))).isEqualTo("arbeit_2");
        assertThat(generator.generate("Arbeit", Set.of("arbeit", "arbeit_2"))).isEqualTo("arbeit_3");
    }
}
