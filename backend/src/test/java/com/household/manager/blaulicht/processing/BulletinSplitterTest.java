package com.household.manager.blaulicht.processing;

import com.household.manager.blaulicht.BlaulichtFixtures;
import com.household.manager.blaulicht.source.PresseportalDetailParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BulletinSplitterTest {

    @Test
    void zerlegtSammelmeldungInFaelleJeKopfzeile() {
        PresseportalDetailParser.Detail detail = BlaulichtFixtures.detail("6382768");

        List<BulletinCase> cases = BulletinSplitter.split(detail.title(), detail.paragraphs());

        assertThat(cases).extracting(BulletinCase::placeHeader).containsExactly("Bad Vilbel", "Büdingen",
                "Butzbach", "Echzell", "Friedberg", "Münzenberg", "Ortenberg", "Wölfersheim");
        assertThat(cases.get(0).headline()).isEqualTo("In Restaurant eingebrochen");
        assertThat(cases.get(0).body()).contains("Frankfurter Straße in Bad Vilbel")
                .doesNotContain("Pressesprecher").doesNotContain("Rückfragen");
    }

    @Test
    void erkenntOrtMitDoppelOrtUndStrassennummer() {
        assertThat(BulletinSplitter.split("t", BlaulichtFixtures.detail("6376795").paragraphs()))
                .extracting(BulletinCase::placeHeader).containsExactly("Bad Vilbel", "Rockenberg / Wölfersheim");
        assertThat(BulletinSplitter.split("t", BlaulichtFixtures.detail("6392554").paragraphs()))
                .extracting(BulletinCase::placeHeader).containsExactly("Butzbach - A 5");
    }

    @Test
    void satzMitDoppelpunktIstKeineKopfzeile() {
        // 6381252 enthält „Die Polizei empfiehlt:“ als Absatz im Fall Butzbach.
        List<BulletinCase> cases = BulletinSplitter.split("t", BlaulichtFixtures.detail("6381252").paragraphs());

        assertThat(cases).extracting(BulletinCase::placeHeader).containsExactly("Butzbach", "Karben", "Niddatal");
    }

    @Test
    void meldungOhneKopfzeileIstEinFallMitBereinigtemTitel() {
        List<BulletinCase> cases = BulletinSplitter.split("Mittelhessen: Kirchhain: Vier Autos brennen ab",
                List.of("Giessen (ots) - In der Nacht brannten vier Autos.", "Rückfragen bitte an: Pressestelle"));

        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).placeHeader()).isNull();
        assertThat(cases.get(0).headline()).isEqualTo("Kirchhain: Vier Autos brennen ab");
        assertThat(cases.get(0).body()).isEqualTo("In der Nacht brannten vier Autos.");
    }

    @Test
    void bereinigtTitelVonPraefixUndPlusKlammern() {
        assertThat(BulletinSplitter.cleanTitle("Mittelhessen: WETTERAUKREIS: + Einbruch + Diebstahl +"))
                .isEqualTo("Einbruch · Diebstahl");
    }
}
