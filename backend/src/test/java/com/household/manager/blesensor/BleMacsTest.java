package com.household.manager.blesensor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BleMacsTest {

    @Test
    void normalisiertGrossbuchstabenUndBindestriche() {
        assertThat(BleMacs.normalize("AA:BB:CC:DD:EE:FF")).isEqualTo("aa:bb:cc:dd:ee:ff");
        assertThat(BleMacs.normalize("aa-bb-cc-dd-ee-ff")).isEqualTo("aa:bb:cc:dd:ee:ff");
        assertThat(BleMacs.normalize(" aabbccddeeff ")).isEqualTo("aa:bb:cc:dd:ee:ff");
    }

    @Test
    void lehntUngueltigeSyntaxAb() {
        assertThatThrownBy(() -> BleMacs.normalize("aa:bb:cc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BleMacs.normalize("zz:bb:cc:dd:ee:ff")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BleMacs.normalize(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entityRefIstMacOhneDoppelpunkte() {
        assertThat(BleMacs.entityRef("aa:bb:cc:dd:ee:ff")).isEqualTo("aabbccddeeff");
    }
}
