package com.household.manager.flowengine;

import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.model.entity.Flow;
import com.household.manager.repository.FlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Verdrahtet Engine<->Registry (Zirkularität) und lädt beim Start alle
 * deployten, aktiven Flows aus der DB in die Registry.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FlowEngineBootstrap {

    private final FlowRegistry registry;
    private final FlowEngine engine;
    private final FlowRepository flowRepository;
    private final FlowDefinitionParser parser;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        registry.setEngine(engine);
        for (Flow flow : flowRepository.findByEnabledTrueAndDeployedDefinitionNotNull()) {
            try {
                registry.deploy(flow.getId(), parser.parse(flow.getDeployedDefinition()));
            } catch (Exception ex) {
                log.error("Flow {} ({}) konnte beim Start nicht geladen werden: {}",
                        flow.getId(), flow.getName(), ex.getMessage());
            }
        }
        log.info("Flow engine started");
    }
}
