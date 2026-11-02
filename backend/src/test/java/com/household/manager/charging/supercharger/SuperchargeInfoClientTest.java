package com.household.manager.charging.supercharger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuperchargeInfoClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static String fixture() {
        try (var in = SuperchargeInfoClientTest.class.getResourceAsStream("/charging/supercharge-info-sites.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    void httpClientIstAufHttp11Festgelegt() {
        assertThat(SuperchargeInfoClient.createHttpClient(5000).version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    }

    @Test
    void parstDieFelderUndIgnoriertUnbekannte() {
        List<SuperchargerSite> sites = SuperchargeInfoClient.parseSites(mapper, fixture());

        assertThat(sites).hasSize(4);
        SuperchargerSite first = sites.get(0);
        assertThat(first.id()).isEqualTo("1001");
        assertThat(first.name()).isEqualTo("Pfungstadt, Germany");
        assertThat(first.street()).isEqualTo("Hahnstr. 1");
        assertThat(first.city()).isEqualTo("Pfungstadt");
        assertThat(first.lat()).isEqualTo(50.02);
        assertThat(first.lon()).isEqualTo(8.0);
        assertThat(first.stallCount()).isEqualTo(15);
        assertThat(first.powerKw()).isEqualTo(250);
        assertThat(first.otherEvs()).isFalse();
        assertThat(first.status()).isEqualTo("OPEN");
    }

    @Test
    void fehlendeLeistungBleibtNull() {
        List<SuperchargerSite> sites = SuperchargeInfoClient.parseSites(mapper, fixture());

        assertThat(sites.get(2).powerKw()).isNull();
        assertThat(sites.get(2).otherEvs()).isTrue();
    }

    @Test
    void eintragOhneGpsHatNullKoordinaten() {
        List<SuperchargerSite> sites = SuperchargeInfoClient.parseSites(mapper,
                "[{\"id\":1,\"name\":\"X, Germany\",\"status\":\"OPEN\",\"stallCount\":2}]");

        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).lat()).isNull();
        assertThat(sites.get(0).lon()).isNull();
    }
}
