package com.household.manager.tesla;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TeslaCompositorImageTest {

    @Test
    void nimmtDieAusstattungscodesUndDasModell() {
        String url = TeslaCompositorImage.urlFor(vehicle("model3", "PearlWhite", "AD15,MDL3,PBSB,W40B"));

        assertThat(url).startsWith("https://static-assets.tesla.com/v1/compositor/?model=m3&view=STUD_3QTR")
                .contains("&options=$AD15,$MDL3,$PBSB,$W40B")
                .doesNotContain("PPSW");
    }

    @Test
    void ergaenztDenLackAusDerFarbeWennKeinLackcodeDaIst() {
        String url = TeslaCompositorImage.urlFor(vehicle("modely", "Midnight Silver", "MDLY,W41B"));

        assertThat(url).contains("model=my").contains("&options=$MDLY,$W41B,$PMNG");
    }

    @Test
    void liestCodesOhneKomma() {
        assertThat(TeslaCompositorImage.parseOptionCodes("AD15MDL3PBSB")).containsExactly("AD15", "MDL3", "PBSB");
        assertThat(TeslaCompositorImage.parseOptionCodes("kaputt!")).isEmpty();
    }

    @Test
    void ohneCodesNurDerLack() {
        assertThat(TeslaCompositorImage.urlFor(vehicle("model3", "PearlWhite", null)))
                .endsWith("&bkba_opt=1&options=$PPSW");
    }

    @Test
    void unbekanntesModellGibtKeineAdresse() {
        assertThat(TeslaCompositorImage.urlFor(vehicle(null, "PearlWhite", null))).isNull();
        assertThat(TeslaCompositorImage.urlFor(vehicle("roadster", "PearlWhite", null))).isNull();
        assertThat(TeslaCompositorImage.urlFor(null)).isNull();
    }

    private static TeslaVehicleSnapshot vehicle(String carType, String color, String options) {
        return new TeslaVehicleSnapshot("VIN", "Blitz", "online", 55, 275.7, 80, "Charging", true, 11.0, 95,
                false, null, true, true, false, false, 6.5, 2.0, null, null, null, carType, color, null,
                options, Instant.parse("2026-11-27T07:59:00Z"));
    }
}
