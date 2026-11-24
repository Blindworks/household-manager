package com.household.manager.communicator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/** Laeuft gegen ALLE echten Controller der Anwendung, nicht gegen eine Beispielliste. */
class ApiCatalogTest {

    private static ApiCatalog catalog;

    @BeforeAll
    static void setUp() {
        GenericWebApplicationContext context = new GenericWebApplicationContext(new MockServletContext());
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition definition : scanner.findCandidateComponents("com.household.manager")) {
            definition.setLazyInit(true);
            context.registerBeanDefinition(definition.getBeanClassName(), definition);
        }
        context.refresh();
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();
        catalog = new ApiCatalog(new ObjectProvider<>() {
            @Override
            public RequestMappingHandlerMapping getObject() {
                return mapping;
            }
        }, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void kenntLesendeUndSchreibendeEndpunkteOhneEintragVonHand() {
        System.out.println("Verzeichnis: " + catalog.endpoints().size() + " Endpunkte, "
                + catalog.index().length() + " Zeichen");
        assertThat(catalog.endpoints()).hasSizeGreaterThan(250);
        assertThat(catalog.index()).contains(
                "GET /v1/tractive/pets/{trackerId}/walks?days walks -> [{start,end,durationMinutes,distanceMeters}]",
                "PUT /v1/thermostats/{id}/target", "POST /v1/switches/{entityId}/toggle");
    }

    @Test
    void zugangsdatenMedienUndSichSelbstBietetEsNichtAn() {
        assertThat(catalog.endpoints()).noneMatch(e -> e.path().contains("/auth/") || e.path().contains("login")
                || e.path().contains("service-tokens") || e.path().contains("/communicator")
                || e.path().endsWith("/thumbnail"));
        assertThat(catalog.find("POST", "/v1/admin/users")).isEmpty();
    }

    @Test
    void findetKonkretePfadeUndWeistUmgehungenAb() {
        assertThat(catalog.find("GET", "/v1/tractive/pets/ABC/walks")).isPresent();
        assertThat(catalog.find("GET", "/v1/meter-readings/series").orElseThrow().path())
                .isEqualTo("/v1/meter-readings/series");
        assertThat(catalog.find("DELETE", "/v1/tractive/pets/ABC/walks")).isEmpty();
        assertThat(catalog.find("GET", "/v1/calendar/../auth/me")).isEmpty();
        assertThat(catalog.find("GET", "/v1/calendar/events?x=1")).isEmpty();
        assertThat(catalog.find("GET", "/v1/%61uth/me")).isEmpty();
        assertThat(catalog.find("GET", "http://example.org/v1/switches")).isEmpty();
    }

    @Test
    void beschreibtBodyMitTypen() {
        assertThat(catalog.describe("PUT", "/v1/thermostats/{id}/mode")).contains("mode: MANUAL|FROST_PROTECTION|OFF");
    }
}
