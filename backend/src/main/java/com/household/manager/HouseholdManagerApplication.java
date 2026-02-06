package com.household.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Main entry point for the Household Manager Backend application.
 * This application provides REST APIs for managing household data including meter readings.
 */
@Slf4j
@SpringBootApplication
public class HouseholdManagerApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(HouseholdManagerApplication.class, args);
        logApplicationStartup(context.getEnvironment());
    }

    private static void logApplicationStartup(Environment env) {
        String protocol = "http";
        String serverPort = env.getProperty("server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "");

        log.info("""

                ----------------------------------------------------------
                Application '{}' is running!
                Access URLs:
                    Local:      {}://localhost:{}{}
                    Health:     {}://localhost:{}{}{}
                Profile(s):     {}
                ----------------------------------------------------------
                """,
                env.getProperty("spring.application.name"),
                protocol, serverPort, contextPath,
                protocol, serverPort, contextPath, "/management/health",
                env.getActiveProfiles().length == 0 ? "default" : String.join(", ", env.getActiveProfiles())
        );
    }
}
