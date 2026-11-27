package com.household.manager.tesla;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TeslaCompositorImageTest {

    @Test
    void nimmtDieAusstattungscodesUndDasModell() {
        String url = TeslaCompositorImage.urlFor(vehicle("model3", "PearlWhite", "AD15,MDL3,PBSB,W40B"));

        assertThat(url).startsWith("https://static-assets.tesla.com/v1/compositor/?model=m3&view=STUD_3QTR")
                .contains("&options=$AD15,$MDL3,$PBSB,$W38B")
                .doesNotContain("PPSW");
    }

    @Test
    void ergaenztDenLackAusDerFarbeWennKeinLackcodeDaIst() {
        String url = TeslaCompositorImage.urlFor(vehicle("modely", "Midnight Silver", "MDLY,W41B"));

        assertThat(url).contains("model=my").contains("&options=$MDLY,$WY19B,$PMNG");
    }

    @Test
    void liestCodesOhneKomma() {
        assertThat(TeslaCompositorImage.parseOptionCodes("AD15MDL3PBSB")).containsExactly("AD15", "MDL3", "PBSB");
        assertThat(TeslaCompositorImage.parseOptionCodes("kaputt!")).isEmpty();
    }

    @Test
    void ohneCodesNurDerLack() {
        assertThat(TeslaCompositorImage.urlFor(vehicle("model3", "PearlWhite", null)))
                .endsWith("&bkba_opt=1&options=$W38B,$PPSW");
    }

    @Test
    void wheelComesFromWheelTypeAndReplacesStaleWheelCodes() {
        String url = TeslaCompositorImage.urlFor(vehicle("model3", "MidnightSilver", "MDL3,W40B,PMNG", "Stiletto19"));

        assertThat(url).contains("&options=$MDL3,$PMNG,$W39B").doesNotContain("W40B");
    }

    @Test
    void missingWheelCodeFallsBackToModelDefault() {
        assertThat(TeslaCompositorImage.urlFor(vehicle("model3", "MidnightSilver", "MDL3,PMNG", "SomethingNew")))
                .contains("$W38B");
        assertThat(TeslaCompositorImage.urlFor(vehicle("modely", "PearlWhite", null, null))).contains("$WY19B");
    }

    @Test
    void wheelCodesThatRenderWithoutWheelsAreReplaced() {
        assertThat(TeslaCompositorImage.urlFor(vehicle("model3", "PearlWhite", "MDL3,PPSW", "Photon18")))
                .contains("$W38B").doesNotContain("W38A");
        assertThat(TeslaCompositorImage.urlFor(vehicle("model3", "PearlWhite", "MDL3,PPSW", "Nova19")))
                .contains("$W39B").doesNotContain("W39S");
        assertThat(TeslaCompositorImage.urlFor(vehicle("models", "PearlWhite", "MDLS,WS90", null)))
                .contains("$WT19").doesNotContain("WS90");
        assertThat(TeslaCompositorImage.urlFor(vehicle("modelx", "PearlWhite", "MDLX,WX00", "Turbine22")))
                .contains("$WT22").doesNotContain("WX00");
    }

    @Test
    void recognisesWheelCodesOnly() {
        assertThat(TeslaCompositorImage.isWheelCode("W38B")).isTrue();
        assertThat(TeslaCompositorImage.isWheelCode("WY19B")).isTrue();
        assertThat(TeslaCompositorImage.isWheelCode("WY0S")).isTrue();
        assertThat(TeslaCompositorImage.isWheelCode("WT19")).isTrue();
        assertThat(TeslaCompositorImage.isWheelCode("WR00")).isFalse();
        assertThat(TeslaCompositorImage.isWheelCode("TW00")).isFalse();
    }

    @Test
    void unbekanntesModellGibtKeineAdresse() {
        assertThat(TeslaCompositorImage.urlFor(vehicle(null, "PearlWhite", null))).isNull();
        assertThat(TeslaCompositorImage.urlFor(vehicle("roadster", "PearlWhite", null))).isNull();
        assertThat(TeslaCompositorImage.urlFor(null)).isNull();
    }

    private static TeslaVehicleSnapshot vehicle(String carType, String color, String options) {
        return vehicle(carType, color, options, null);
    }

    private static TeslaVehicleSnapshot vehicle(String carType, String color, String options, String wheelType) {
        return new TeslaVehicleSnapshot("VIN", "Blitz", "online", 55, 275.7, 80, "Charging", true, 11.0, 95,
                false, null, true, true, false, false, 6.5, 2.0, null, null, null, carType, color, wheelType,
                options, Instant.parse("2026-11-27T07:59:00Z"));
    }
}
