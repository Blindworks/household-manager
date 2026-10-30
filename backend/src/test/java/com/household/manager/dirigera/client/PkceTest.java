package com.household.manager.dirigera.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PkceTest {

    @Test
    void challengeNachRfc7636() {
        // Testvektor aus RFC 7636, Anhang B
        assertThat(Pkce.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    void generateLiefert128ZeichenAusErlaubtemAlphabet() {
        Pkce pkce = Pkce.generate();

        assertThat(pkce.verifier()).matches("[A-Za-z0-9._~-]{128}");
        assertThat(pkce.challenge()).isEqualTo(Pkce.challengeOf(pkce.verifier()));
    }

    @Test
    void generateLiefertJedesMalEinenAnderenVerifier() {
        assertThat(Pkce.generate().verifier()).isNotEqualTo(Pkce.generate().verifier());
    }
}
