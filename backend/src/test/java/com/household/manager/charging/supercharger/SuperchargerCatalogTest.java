package com.household.manager.charging.supercharger;

import com.household.manager.charging.ChargingProperties;
import com.household.manager.systemlog.SystemJobMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperchargerCatalogTest {

    @Mock
    private SuperchargeInfoClient client;
    @Mock
    private SystemJobMonitor jobMonitor;

    private ChargingProperties properties;
    private SuperchargerCatalog catalog;

    @BeforeEach
    void setUp() {
        properties = new ChargingProperties();
        catalog = new SuperchargerCatalog(properties, client, jobMonitor);
    }

    private static SuperchargerSite site(String id, String status, Double lat, Double lon) {
        return new SuperchargerSite(id, "Ort, Germany", "Str. 1", "Ort", lat, lon, 8, 250, false, status);
    }

    @Test
    void uebernimmtNurOpenUndExpandingMitGps() throws Exception {
        when(client.fetchSites()).thenReturn(List.of(
                site("1", "OPEN", 50.0, 8.0),
                site("2", "EXPANDING", 50.1, 8.0),
                site("3", "CONSTRUCTION", 50.2, 8.0),
                site("4", "PLAN", 50.3, 8.0),
                site("5", "CLOSED_PERM", 50.4, 8.0),
                site("6", "OPEN", null, null)));

        catalog.refresh();

        assertThat(catalog.sites()).extracting(SuperchargerSite::id).containsExactly("1", "2");
    }

    @Test
    void behaeltBeiFehlerDenAltenStand() throws Exception {
        when(client.fetchSites())
                .thenReturn(List.of(site("1", "OPEN", 50.0, 8.0)))
                .thenThrow(new IOException("down"));

        catalog.refresh();
        assertThatCode(catalog::refresh).doesNotThrowAnyException();

        assertThat(catalog.sites()).extracting(SuperchargerSite::id).containsExactly("1");
        verify(jobMonitor).reportFailure(anyString());
    }

    @Test
    void werfNieAuchBeiRuntimeFehler() throws Exception {
        when(client.fetchSites()).thenThrow(new IllegalStateException("kaputtes JSON"));

        assertThatCode(catalog::refresh).doesNotThrowAnyException();
        assertThat(catalog.sites()).isEmpty();
    }

    @Test
    void deaktiviertLaedtNichtUndMeldetInaktiv() throws Exception {
        properties.getSupercharger().setEnabled(false);

        catalog.refresh();

        verify(client, never()).fetchSites();
        verify(jobMonitor).reportInactive(anyString());
        assertThat(catalog.sites()).isEmpty();
    }
}
