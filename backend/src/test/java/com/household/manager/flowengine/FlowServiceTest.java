package com.household.manager.flowengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.model.entity.Flow;
import com.household.manager.repository.FlowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlowServiceTest {

    private static class TestTriggerHandler implements TriggerNodeHandler {
        public String type() { return "test-trigger"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public Optional<String> watchedEntityId(NodeConfig config) { return Optional.empty(); }
    }

    private static final String VALID_DEF = """
            { "nodes": [ { "id": "t", "type": "test-trigger", "config": {} } ], "wires": [] }
            """;

    @Mock
    private FlowRepository flowRepository;
    @Mock
    private FlowEngine engine;

    private FlowRegistry registry;
    private FlowService service;

    @BeforeEach
    void setUp() {
        registry = new FlowRegistry(List.of(new TestTriggerHandler()));
        registry.setEngine(engine);
        FlowDefinitionParser parser = new FlowDefinitionParser(new ObjectMapper());
        FlowValidator validator = new FlowValidator(List.of(new TestTriggerHandler()),
                mock(com.household.manager.entitystate.EntityStateService.class));
        service = new FlowService(flowRepository, parser, validator, registry, engine);
        lenient().when(flowRepository.save(any(Flow.class))).thenAnswer(inv -> inv.getArgument(0));
        // undeploy() clears the flow's debug buffer via engine.debugBuffer() -- stub with a
        // real instance so FlowRegistry.undeploy doesn't NPE on the mocked FlowEngine.
        lenient().when(engine.debugBuffer()).thenReturn(new DebugBuffer());
    }

    private Flow flow(Long id, String draft, String deployed, boolean enabled) {
        return Flow.builder().id(id).name("f").enabled(enabled)
                .draftDefinition(draft).deployedDefinition(deployed).build();
    }

    @Test
    void deployValidDraftCopiesToDeployedAndRegisters() {
        Flow entity = flow(1L, VALID_DEF, null, true);
        when(flowRepository.findById(1L)).thenReturn(Optional.of(entity));

        ValidationResult result = service.deploy(1L);

        assertTrue(result.valid());
        assertEquals(VALID_DEF, entity.getDeployedDefinition());
        assertNotNull(entity.getDeployedAt());
        assertTrue(registry.graph(1L).isPresent());
    }

    @Test
    void deployInvalidDraftReturnsErrorsAndDoesNotRegister() {
        Flow entity = flow(1L, "{ \"nodes\": [ { \"id\": \"x\", \"type\": \"nope\", \"config\": {} } ], \"wires\": [] }", null, true);
        when(flowRepository.findById(1L)).thenReturn(Optional.of(entity));

        ValidationResult result = service.deploy(1L);

        assertFalse(result.valid());
        assertNull(entity.getDeployedDefinition());
        assertTrue(registry.graph(1L).isEmpty());
    }

    @Test
    void disableUndeploysEnableRedeploys() {
        Flow entity = flow(1L, VALID_DEF, VALID_DEF, true);
        when(flowRepository.findById(1L)).thenReturn(Optional.of(entity));

        service.deploy(1L);
        assertTrue(registry.graph(1L).isPresent());

        service.setEnabled(1L, false);
        assertFalse(entity.isEnabled());
        assertTrue(registry.graph(1L).isEmpty());

        service.setEnabled(1L, true);
        assertTrue(registry.graph(1L).isPresent());
    }

    @Test
    void deleteUndeploysAndDeletes() {
        Flow entity = flow(1L, VALID_DEF, VALID_DEF, true);
        when(flowRepository.findById(1L)).thenReturn(Optional.of(entity));
        service.deploy(1L);

        service.delete(1L);

        assertTrue(registry.graph(1L).isEmpty());
        verify(flowRepository).deleteById(1L);
    }

    @Test
    void injectFiresTriggerNodeWithMergedPayload() {
        Flow entity = flow(1L, VALID_DEF, VALID_DEF, true);
        when(flowRepository.findById(1L)).thenReturn(Optional.of(entity));
        service.deploy(1L);

        service.inject(1L, "t", Map.of("newState", "5"));

        verify(engine).emitAsync(eq(1L), eq("t"), eq(0), argThat(msg ->
                "5".equals(msg.get("newState")) && "t".equals(msg.get("triggerNodeId"))));
    }

    @Test
    void injectOnUnknownNodeThrows() {
        Flow entity = flow(1L, VALID_DEF, VALID_DEF, true);
        when(flowRepository.findById(1L)).thenReturn(Optional.of(entity));
        service.deploy(1L);

        assertThrows(IllegalArgumentException.class, () -> service.inject(1L, "ghost", Map.of()));
    }
}
