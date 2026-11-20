package com.household.manager.grocery;

import com.household.manager.grocery.dto.DealsView;
import com.household.manager.grocery.dto.GrocerySummaryDto;
import com.household.manager.grocery.dto.GrocerySummaryDto.SummaryItem;
import com.household.manager.grocery.dto.ProductDto;
import com.household.manager.grocery.pricing.BaseUnit;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.security.ServiceTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GroceryController.class)
@AutoConfigureMockMvc(addFilters = false)
class GroceryControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private GroceryProductService productService;
    @MockitoBean
    private GroceryDealQueryService dealQueryService;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private ServiceTokenService serviceTokenService;

    private static final String BODY = "{\"name\":\"Cola\",\"baseUnit\":\"LITER\",\"searchWords\":[\"cola\"]}";

    @Test
    void foreignProductChangesGive403() throws Exception {
        when(productService.update(eq(1L), any())).thenThrow(new AccessDeniedException("fremd"));
        doThrow(new AccessDeniedException("fremd")).when(productService).delete(1L);
        doThrow(new AccessDeniedException("fremd")).when(productService).verdict(eq(1L), any());

        mockMvc.perform(put("/v1/groceries/products/1").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/v1/groceries/products/1")).andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/groceries/products/1/verdicts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fingerprint\":\"fp\",\"fits\":false}")).andExpect(status().isForbidden());
    }

    @Test
    void confirmOnNonMaybeGives409() throws Exception {
        doThrow(new GroceryConflictException("Nur unsichere Treffer lassen sich bestätigen"))
                .when(productService).verdict(eq(1L), any());

        mockMvc.perform(post("/v1/groceries/products/1/verdicts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fingerprint\":\"fp\",\"fits\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Nur unsichere Treffer lassen sich bestätigen"));
    }

    @Test
    void serviceTokenWithoutUserGives400() throws Exception {
        when(productService.create(any())).thenThrow(new IllegalStateException("Nutzer-Session nötig"));

        mockMvc.perform(post("/v1/groceries/products").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturnsProduct() throws Exception {
        when(productService.create(any())).thenReturn(new ProductDto(42L, 3L, "Benedikt", "Cola", BaseUnit.LITER,
                List.of("cola"), List.of(), List.of(), null, null, true, true));

        mockMvc.perform(post("/v1/groceries/products").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    void dealsPassOwnerFilter() throws Exception {
        when(dealQueryService.deals("all")).thenReturn(new DealsView(LocalDateTime.of(2026, 11, 15, 4, 12), List.of()));

        mockMvc.perform(get("/v1/groceries/deals").param("owner", "all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deals").isArray());
        verify(dealQueryService).deals("all");
    }

    @Test
    void summaryListsItems() throws Exception {
        when(dealQueryService.summary()).thenReturn(new GrocerySummaryDto(List.of(new SummaryItem("Cola Zero", null,
                "Haushalt", Chain.PENNY, "Penny", new BigDecimal("1.430"), BaseUnit.LITER, 23, true, false, null))));

        mockMvc.perform(get("/v1/groceries/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productName").value("Cola Zero"))
                .andExpect(jsonPath("$.items[0].savingPercent").value(23))
                .andExpect(jsonPath("$.items[0].appOnly").value(true))
                .andExpect(jsonPath("$.items[0].warmingUp").value(false));
    }

    @Test
    void unknownProductGives404() throws Exception {
        when(dealQueryService.offers(anyLong())).thenThrow(
                new com.household.manager.exception.ResourceNotFoundException("Produkt", "id", 5L));

        mockMvc.perform(get("/v1/groceries/products/5/offers")).andExpect(status().isNotFound());
    }
}
