package com.household.manager.controller;

import com.household.manager.exception.GlobalExceptionHandler;
import com.household.manager.flowengine.*;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.model.entity.Flow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class FlowControllerTest {

    private static class TestTriggerHandler implements TriggerNodeHandler {
        public String type() { return "test-trigger"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public Optional<String> watchedEntityId(NodeConfig config) { return Optional.empty(); }
        public java.util.List<com.household.manager.flowengine.NodeFieldDescriptor> fields() {
            return java.util.List.of(com.household.manager.flowengine.NodeFieldDescriptor.field(
                    "k", "Feld K", com.household.manager.flowengine.NodeFieldType.STRING, true));
        }
        public java.util.List<String> portLabels() { return java.util.List.of("Ausgang"); }
    }

    @Mock
    private FlowService flowService;
    @Mock
    private DebugBuffer debugBuffer;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        FlowController controller = new FlowController(flowService, debugBuffer,
                List.of(new TestTriggerHandler()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Flow flow() {
        return Flow.builder().id(1L).name("Test").enabled(true)
                .draftDefinition("{ \"nodes\": [], \"wires\": [] }").build();
    }

    @Test
    void listsFlows() throws Exception {
        when(flowService.getAll()).thenReturn(List.of(flow()));

        mockMvc.perform(get("/v1/flows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].deployed").value(false));
    }

    @Test
    void createsFlow() throws Exception {
        when(flowService.create("Neu", "Desc")).thenReturn(flow());

        mockMvc.perform(post("/v1/flows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Neu\",\"description\":\"Desc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test"));
    }

    @Test
    void getReturns404ForUnknownFlow() throws Exception {
        when(flowService.getById(9L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/flows/9")).andExpect(status().isNotFound());
    }

    @Test
    void deployReturns400WithErrorsWhenInvalid() throws Exception {
        when(flowService.deploy(1L)).thenReturn(new ValidationResult(List.of("kaputt"), List.of()));

        mockMvc.perform(post("/v1/flows/1/deploy"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("kaputt"));
    }

    @Test
    void deployReturns200WithWarningsWhenValid() throws Exception {
        when(flowService.deploy(1L)).thenReturn(new ValidationResult(List.of(), List.of("warnung")));

        mockMvc.perform(post("/v1/flows/1/deploy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings[0]").value("warnung"));
    }

    @Test
    void injectDelegatesToService() throws Exception {
        mockMvc.perform(post("/v1/flows/1/nodes/t/inject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"newState\":\"5\"}}"))
                .andExpect(status().isAccepted());

        verify(flowService).inject(eq(1L), eq("t"), any());
    }

    @Test
    void mutatingEndpointReturns404ForUnknownFlow() throws Exception {
        when(flowService.deploy(99L))
                .thenThrow(new com.household.manager.exception.ResourceNotFoundException("Flow not found with ID: 99"));

        mockMvc.perform(post("/v1/flows/99/deploy"))
                .andExpect(status().isNotFound());
    }

    @Test
    void importDelegatesToServiceAndMapsResponse() throws Exception {
        Flow saved = Flow.builder().id(7L).name("Imported").description("desc")
                .enabled(false).draftDefinition("{\"nodes\":[],\"wires\":[]}").build();
        when(flowService.importFlow(eq(1), eq("Imported"), eq("desc"), any())).thenReturn(saved);

        mockMvc.perform(post("/v1/flows/import").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":1,\"name\":\"Imported\",\"description\":\"desc\","
                                + "\"definition\":{\"nodes\":[],\"wires\":[]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("Imported"))
                .andExpect(jsonPath("$.enabled").value(false));

        verify(flowService).importFlow(eq(1), eq("Imported"), eq("desc"), eq("{\"nodes\":[],\"wires\":[]}"));
    }

    @Test
    void listsNodeTypes() throws Exception {
        mockMvc.perform(get("/v1/flows/node-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("test-trigger"))
                .andExpect(jsonPath("$[0].trigger").value(true))
                .andExpect(jsonPath("$[0].outputPorts").value(1))
                .andExpect(jsonPath("$[0].portLabels[0]").value("Ausgang"))
                .andExpect(jsonPath("$[0].fields[0].key").value("k"))
                .andExpect(jsonPath("$[0].fields[0].type").value("STRING"))
                .andExpect(jsonPath("$[0].fields[0].required").value(true));
    }
}
