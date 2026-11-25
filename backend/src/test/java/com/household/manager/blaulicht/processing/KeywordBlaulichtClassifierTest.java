package com.household.manager.blaulicht.processing;

import com.household.manager.blaulicht.BlaulichtCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordBlaulichtClassifierTest {

    private final KeywordBlaulichtClassifier classifier = new KeywordBlaulichtClassifier();

    @Test
    void ueberschriftEntscheidetZuerst() {
        assertThat(classifier.classify("In Restaurant eingebrochen", "stahlen eine Geldkassette"))
                .contains(BlaulichtCategory.EINBRUCH);
        assertThat(classifier.classify("Landstraße nach Zusammenstoß gesperrt", "")).contains(BlaulichtCategory.UNFALL);
        assertThat(classifier.classify("BMW Z1 gestohlen", "Autodiebe schlugen zu")).contains(BlaulichtCategory.DIEBSTAHL);
    }

    @Test
    void diebstahlInDerUeberschriftMitEinbruchImTextIstEinbruch() {
        assertThat(classifier.classify("Schmuck gestohlen", "Die Täter hebelten ein Fenster auf und drangen ein. "
                + "Sie waren eingedrungen und hatten das Fenster aufgehebelt."))
                .contains(BlaulichtCategory.EINBRUCH);
    }

    /** Synthetischer Fall — in den aufgezeichneten Meldungen kam kein Brand in Bad Vilbel vor. */
    @Test
    void brandAuchAlsZusammensetzung() {
        assertThat(classifier.classify("Wohnungsbrand in Dortelweil", "")).contains(BlaulichtCategory.BRAND);
        assertThat(classifier.classify("Vier Autos brennen ab", "Flammen schlugen aus dem Motorraum"))
                .contains(BlaulichtCategory.BRAND);
        assertThat(classifier.classify("Mülltonne in Brand gesetzt", "")).contains(BlaulichtCategory.BRAND);
    }

    @Test
    void strassennameUndFeuerwehrSindKeinBrand() {
        assertThat(classifier.classify("Diebstähle auf Baustellen", "in der Brandström-Straße"))
                .contains(BlaulichtCategory.DIEBSTAHL);
        assertThat(classifier.classify("Ölspur", "Die Feuerwehr streute ab. Kennzeichen aus Brandenburg."))
                .contains(BlaulichtCategory.SONSTIGES);
        assertThat(classifier.classify("Edelstahl-Geländer beschädigt", "")).contains(BlaulichtCategory.SONSTIGES);
    }

    @Test
    void betrugMitErbeutetIstSonstiges() {
        assertThat(classifier.classify("Betrüger erbeuten Bankkarte", "Falsche Bankmitarbeiter holten die Karte ab"))
                .contains(BlaulichtCategory.SONSTIGES);
    }

    @Test
    void reinerRettungsdiensteinsatzWirdVerworfen() {
        assertThat(classifier.classify("Rettungsdienst im Einsatz", "Ein Mann stürzte.")).isEmpty();
        assertThat(classifier.classify("Rettungswagen touchiert und geflüchtet", ""))
                .contains(BlaulichtCategory.UNFALL);
    }
}
