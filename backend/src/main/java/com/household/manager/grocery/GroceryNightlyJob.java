package com.household.manager.grocery;

import com.household.manager.exception.TooManyRequestsException;
import com.household.manager.grocery.dto.RunResultDto;
import com.household.manager.systemlog.SystemJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Nächtlicher Lauf der Supermarkt-Angebote um 04:00; meldet gestörte Quellen und den Claude-Deckel. Wirft nie. */
@Slf4j
@Component
public class GroceryNightlyJob {

    private final GroceryRunService runService;
    private final GroceryProperties properties;
    private final SystemJobMonitor jobMonitor;

    public GroceryNightlyJob(GroceryRunService runService, GroceryProperties properties, SystemJobMonitor jobMonitor) {
        this.runService = runService;
        this.properties = properties;
        this.jobMonitor = jobMonitor;
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Europe/Berlin")
    public void run() {
        if (!properties.isEnabled()) {
            jobMonitor.reportInactive("deaktiviert");
            return;
        }
        try {
            String problems = problems(runService.run());
            if (!problems.isEmpty()) {
                jobMonitor.reportFailure(problems);
            }
        } catch (TooManyRequestsException e) {
            log.info("Nächtlicher Supermarkt-Lauf übersprungen: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Nächtlicher Supermarkt-Lauf fehlgeschlagen: {}", e.getMessage(), e);
            jobMonitor.reportFailure(e.getMessage());
        }
    }

    static String problems(RunResultDto result) {
        List<String> parts = new ArrayList<>();
        if (!result.sourceFailures().isEmpty()) {
            parts.add("Quellen gestört: " + result.sourceFailures().entrySet().stream()
                    .map(e -> e.getKey() + " (" + e.getValue() + ")")
                    .collect(Collectors.joining(", ")));
        }
        if (result.deferred() > 0) {
            parts.add("Claude-Deckel erreicht, " + result.deferred() + " Produkte verschoben");
        }
        return String.join("; ", parts);
    }
}
