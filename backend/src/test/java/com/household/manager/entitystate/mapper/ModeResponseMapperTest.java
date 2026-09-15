package com.household.manager.entitystate.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModeResponseMapperTest {

    private static final String NACHTMODUS = "input_boolean.manual_nachtmodus";

    private final ModeResponseMapper mapper =
            new ModeResponseMapper(new EntityStateResponseMapper(new ObjectMapper()));

    private EntityState nachtmodus(String attributes) {
        return EntityState.builder()
                .entityId(NACHTMODUS)
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef("nachtmodus")
                .friendlyName("Nachtmodus")
                .state("off")
                .attributes(attributes)
                .build();
    }

    @Test
    void bildetIdNameIconUndZustandAb() {
        ModeResponse response = mapper.toResponse(nachtmodus("{\"mode\":true,\"icon\":\"nights_stay\"}"));

        assertThat(response.entityId()).isEqualTo(NACHTMODUS);
        assertThat(response.displayName()).isEqualTo("Nachtmodus");
        assertThat(response.icon()).isEqualTo("nights_stay");
        assertThat(response.state()).isEqualTo("off");
    }

    /** Ein Helfer ohne Icon-Attribut bekommt das Standard-Icon, kein leeres Symbol. */
    @Test
    void faelltOhneIconAufDasStandardIconZurueck() {
        assertThat(mapper.toResponse(nachtmodus("{}")).icon()).isEqualTo("flag");
        assertThat(mapper.toResponse(nachtmodus("{\"icon\":\"  \"}")).icon()).isEqualTo("flag");
    }
}
