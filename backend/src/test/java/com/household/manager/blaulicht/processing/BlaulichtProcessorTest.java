package com.household.manager.blaulicht.processing;

import com.household.manager.blaulicht.BlaulichtCategory;
import com.household.manager.blaulicht.BlaulichtFixtures;
import com.household.manager.blaulicht.settings.TestBlaulichtSettings;
import com.household.manager.blaulicht.BlaulichtSourceKey;
import com.household.manager.blaulicht.source.PresseportalDetailParser;
import com.household.manager.blaulicht.source.RawReport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class BlaulichtProcessorTest {

    private final BlaulichtProcessor processor =
            new BlaulichtProcessor(TestBlaulichtSettings.defaults(), new KeywordBlaulichtClassifier());

    private List<ProcessedCase> process(String id) {
        PresseportalDetailParser.Detail detail = BlaulichtFixtures.detail(id);
        return processor.process(new RawReport(BlaulichtSourceKey.PRESSEPORTAL, "pm/" + id, detail.title(),
                detail.paragraphs(), "https://www.presseportal.de/blaulicht/pm/154582/" + id, detail.publishedAt(),
                null, null));
    }

    @Test
    void sammelmeldungMitEinemBadVilbelerFall() {
        assertThat(process("6382768"))
                .extracting(ProcessedCase::externalId, ProcessedCase::category, ProcessedCase::locality,
                        ProcessedCase::nearby, ProcessedCase::relevance, ProcessedCase::occurredAt)
                .containsExactly(tuple("pm/6382768#1", BlaulichtCategory.EINBRUCH, "Bad Vilbel", false, 115,
                        LocalDateTime.of(2026, 10, 30, 21, 50)));
    }

    @Test
    void ortsteilAusDemText() {
        assertThat(process("6376795")).singleElement()
                .satisfies(c -> {
                    assertThat(c.locality()).isEqualTo("Massenheim");
                    assertThat(c.title()).isEqualTo("Einbruch in unbewohntes Haus");
                    assertThat(c.occurredUntil()).isEqualTo(LocalDateTime.of(2026, 10, 22, 8, 0));
                });
    }

    @Test
    void nachbarortKarbenMitMehrerenFaellen() {
        assertThat(process("6387159"))
                .extracting(ProcessedCase::externalId, ProcessedCase::category, ProcessedCase::nearby)
                .containsExactly(
                        tuple("pm/6387159#3", BlaulichtCategory.EINBRUCH, true),
                        tuple("pm/6387159#4", BlaulichtCategory.DIEBSTAHL, true),
                        tuple("pm/6387159#5", BlaulichtCategory.EINBRUCH, true));
        assertThat(process("6383497")).extracting(ProcessedCase::category)
                .containsExactly(BlaulichtCategory.SONSTIGES, BlaulichtCategory.UNFALL);
    }

    @Test
    void meldungOhneBadVilbelErgibtNichts() {
        assertThat(process("6385787")).isEmpty();
        assertThat(process("6392554")).isEmpty();
        assertThat(process("6396365")).isEmpty();
    }

    @Test
    void ninaWarnungBleibtEinFallMitVorgabe() {
        List<ProcessedCase> cases = processor.process(new RawReport(BlaulichtSourceKey.NINA, "mow.DE-HE-1",
                "Großbrand: Fenster und Türen schließen", List.of("Starke Rauchentwicklung"), "u",
                LocalDateTime.of(2026, 11, 25, 3, 0), BlaulichtCategory.WARNUNG, "Bad Vilbel"));

        assertThat(cases).singleElement().satisfies(c -> {
            assertThat(c.externalId()).isEqualTo("mow.DE-HE-1");
            assertThat(c.category()).isEqualTo(BlaulichtCategory.WARNUNG);
            assertThat(c.relevance()).isEqualTo(120);
        });
    }

    @Test
    void fingerabdruckIstUnabhaengigVonGuidUndLeerzeichen() {
        assertThat(ContentHasher.hash("Einbruch", "Text  hier.", "Bad Vilbel"))
                .isEqualTo(ContentHasher.hash("Einbruch", "Text hier", "Bad Vilbel"))
                .isNotEqualTo(ContentHasher.hash("Einbruch", "Text hier", "Karben"));
    }
}
