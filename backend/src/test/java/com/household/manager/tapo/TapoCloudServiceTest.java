package com.household.manager.tapo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TapoCloudServiceTest {

    private final TapoCloudService service = new TapoCloudService(new ObjectMapper(), new TapoProperties());

    @Test
    void decodeAliasShouldDecodeBase64Utf8() {
        assertThat(service.decodeAlias("U3Rlcm4=")).isEqualTo("Stern");
    }

    @Test
    void decodeAliasShouldReturnOriginalForPlainText() {
        assertThat(service.decodeAlias("Wohnzimmer")).isEqualTo("Wohnzimmer");
    }
}
