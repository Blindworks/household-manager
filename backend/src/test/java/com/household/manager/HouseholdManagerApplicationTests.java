package com.household.manager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Basic application context test to verify Spring Boot configuration.
 */
@SpringBootTest
@ActiveProfiles("test")
class HouseholdManagerApplicationTests {

    @Test
    void contextLoads() {
        // Verifies that the application context loads successfully
    }

}
