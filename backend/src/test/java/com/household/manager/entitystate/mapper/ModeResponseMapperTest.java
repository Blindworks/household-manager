package com.household.manager.entitystate.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.mode.ModeQuickAccessResolver;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModeResponseMapperTest {

    private static final String NACHTMODUS = "input_boolean.manual_nachtmodus";

    @Mock
    private ModeQuickAccessResolver quickAccessResolver;

    private ModeResponseMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ModeResponseMapper(new EntityStateResponseMapper(new ObjectMapper()), quickAccessResolver);
    }

    private EntityState nachtmodus() {
        return EntityState.builder()
                .entityId(NACHTMODUS)
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef("nachtmodus")
                .friendlyName("Nachtmodus")
                .state("off")
                .attributes("{\"mode\":true,\"icon\":\"nights_stay\"}")
                .build();
    }

    @Test
    void markiertEinenFaelligenModusAlsSchnellzugriff() {
        when(quickAccessResolver.dueEntityIds()).thenReturn(Set.of(NACHTMODUS));

        ModeResponse response = mapper.toResponse(nachtmodus());

        assertThat(response.quickAccess()).isTrue();
        assertThat(response.entityId()).isEqualTo(NACHTMODUS);
        assertThat(response.icon()).isEqualTo("nights_stay");
    }

    @Test
    void laesstEinenNichtFaelligenModusOhneSchnellzugriff() {
        when(quickAccessResolver.dueEntityIds()).thenReturn(Set.of());

        assertThat(mapper.toResponse(nachtmodus()).quickAccess()).isFalse();
    }

    /**
     * Die Batch-Ueberladung bekommt die Fenster von aussen gereicht, damit ein Listenabruf
     * sie nur einmal laedt statt einmal je Modus.
     */
    @Test
    void nutztDieUebergebeneFenstermengeOhneDenResolverZuFragen() {
        ModeResponse response = mapper.toResponse(nachtmodus(), Set.of(NACHTMODUS));

        assertThat(response.quickAccess()).isTrue();
        // Ohne diese Pruefung liesse ein leeres Default-Set des ungestubbten Resolvers eine
        // Implementierung durchgehen, die beide Quellen kombiniert (dueEntityIds.contains(...)
        // || quickAccessResolver.dueEntityIds().contains(...)) — genau das Doppelberechnen,
        // das diese Ueberladung ausschliessen soll.
        verify(quickAccessResolver, never()).dueEntityIds();
    }
}
