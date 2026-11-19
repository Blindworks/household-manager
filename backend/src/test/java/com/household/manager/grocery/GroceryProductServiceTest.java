package com.household.manager.grocery;

import com.household.manager.audit.AuditService;
import com.household.manager.grocery.dto.ProductDto;
import com.household.manager.grocery.dto.ProductRequest;
import com.household.manager.grocery.dto.VerdictRequest;
import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.grocery.matching.MatchingService;
import com.household.manager.grocery.matching.SearchWordSuggester;
import com.household.manager.grocery.matching.SearchWordSuggester.Suggestion;
import com.household.manager.grocery.pricing.BaseUnit;
import com.household.manager.model.entity.AppUser;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.GroceryProductRepository;
import com.household.manager.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroceryProductServiceTest {

    private final GroceryProductRepository products = mock(GroceryProductRepository.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final SearchWordSuggester suggester = mock(SearchWordSuggester.class);
    private final MatchingService matching = mock(MatchingService.class);
    private final AuditService audit = mock(AuditService.class);
    private final GroceryRunService runService = mock(GroceryRunService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneId.of("Europe/Berlin"));
    private GroceryProductService service;

    @BeforeEach
    void setUp() {
        service = new GroceryProductService(products, new GroceryOwners(users, currentUser), suggester, matching,
                audit, runService, clock);
        when(currentUser.currentUserId()).thenReturn(Optional.of(3L));
        when(currentUser.requireUserId()).thenReturn(3L);
        when(users.findAll()).thenReturn(List.of(AppUser.builder().id(3L).displayName("Benedikt").build(),
                AppUser.builder().id(9L).displayName("Anna").build()));
        when(products.save(any())).thenAnswer(inv -> {
            GroceryProduct p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(42L);
            }
            return p;
        });
    }

    private GroceryProduct stored(long id, Long owner) {
        GroceryProduct p = new GroceryProduct();
        p.setId(id);
        p.setOwnerUserId(owner);
        p.setName("Cola");
        p.setBaseUnit(BaseUnit.LITER);
        p.setSearchWords("cola");
        p.setMustWords("");
        p.setExcludeWords("");
        p.setActive(true);
        when(products.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    private ProductRequest request(String name, List<String> searchWords) {
        return new ProductRequest(name, null, BaseUnit.LITER, searchWords, List.of(), List.of(), null, null);
    }

    @Test
    void otherUsersProductIsReadOnly() {
        stored(1L, 9L);

        assertThatThrownBy(() -> service.update(1L, request("Cola", List.of("cola"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.verdict(1L, new VerdictRequest("fp", false)))
                .isInstanceOf(AccessDeniedException.class);
        verify(products, never()).save(any());
        verify(products, never()).delete(any());
        verify(matching, never()).recordUserVerdict(anyLong(), anyString(), anyBoolean());

        stored(2L, null);
        service.update(2L, request("Cola Zero", List.of("cola")));
        service.verdict(2L, new VerdictRequest("fp", false));
        service.delete(2L);
        verify(matching).recordUserVerdict(2L, "fp", false);
        verify(audit).record("grocery.product.update", "Cola Zero (id=2)");
        verify(audit).record("grocery.verdict", "Cola Zero (id=2) fp -> passt nicht");
        verify(audit).record("grocery.product.delete", "Cola Zero (id=2)");
        verify(runService, org.mockito.Mockito.times(3)).refreshProduct(2L);
    }

    @Test
    void createWithoutSearchWordsUsesSuggester() {
        when(suggester.suggest("Coca-Cola Zero 0,33 l")).thenReturn(
                new Suggestion(List.of("coca-cola", "cola zero"), BaseUnit.LITER));

        ProductDto dto = service.create(new ProductRequest("Coca-Cola Zero 0,33 l", null, null, null,
                List.of(" Zero "), List.of("Light"), new BigDecimal("1.85"), null));

        ArgumentCaptor<GroceryProduct> saved = ArgumentCaptor.forClass(GroceryProduct.class);
        verify(products).save(saved.capture());
        assertThat(saved.getValue().getSearchWords()).isEqualTo("coca-cola,cola zero");
        assertThat(saved.getValue().getBaseUnit()).isEqualTo(BaseUnit.LITER);
        assertThat(saved.getValue().getMustWords()).isEqualTo("zero");
        assertThat(saved.getValue().getExcludeWords()).isEqualTo("light");
        assertThat(saved.getValue().getOwnerUserId()).isEqualTo(3L);
        assertThat(dto.editable()).isTrue();
        assertThat(dto.ownerName()).isEqualTo("Benedikt");
        assertThat(dto.searchWords()).containsExactly("coca-cola", "cola zero");
        verify(runService).refreshProduct(42L);
        verify(audit).record(org.mockito.ArgumentMatchers.eq("grocery.product.create"), anyString());
    }

    @Test
    void createWithoutAnySearchWordsIsRejected() {
        when(suggester.suggest("???")).thenReturn(new Suggestion(List.of(), BaseUnit.PIECE));

        assertThatThrownBy(() -> service.create(new ProductRequest("???", null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Bitte Suchwörter angeben.");
        verify(products, never()).save(any());
    }

    @Test
    void verdictWithoutFitsIsRejected() {
        stored(1L, 3L);

        assertThatThrownBy(() -> service.verdict(1L, new VerdictRequest("fp", null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(matching, never()).recordUserVerdict(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void createWithSearchWordsSkipsSuggester() {
        service.create(request("Milch", List.of("Milch", "vollmilch")));

        verify(suggester, never()).suggest(anyString());
    }

    @Test
    void householdFlagCreatesHouseholdProduct() {
        ProductDto dto = service.create(new ProductRequest("Klopapier", true, BaseUnit.PIECE, List.of("klopapier"),
                List.of(), List.of(), null, null));

        assertThat(dto.ownerUserId()).isNull();
        assertThat(dto.ownerName()).isEqualTo("Haushalt");
    }

    @Test
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> service.create(request(" ", List.of("x"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(request("x".repeat(121), List.of("x"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(request("Cola", List.of("a".repeat(501)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new ProductRequest("Cola", null, BaseUnit.LITER, List.of("cola"),
                List.of(), List.of(), BigDecimal.ZERO, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new ProductRequest("Cola", null, BaseUnit.LITER, List.of("cola"),
                List.of(), List.of(), new BigDecimal("1000.01"), null))).isInstanceOf(IllegalArgumentException.class);
        verify(products, never()).save(any());
    }

    @Test
    void listFiltersByOwnerAndMarksEditable() {
        GroceryProduct mine = stored(1L, 3L);
        GroceryProduct household = stored(2L, null);
        GroceryProduct foreign = stored(3L, 9L);
        when(products.findAll()).thenReturn(List.of(mine, household, foreign));

        assertThat(service.list(null)).extracting(ProductDto::id).containsExactly(1L, 2L);
        assertThat(service.list("household")).extracting(ProductDto::id).containsExactly(2L);
        assertThat(service.list("9")).extracting(ProductDto::id).containsExactly(3L);
        List<ProductDto> all = service.list("all");
        assertThat(all).extracting(ProductDto::editable).containsExactly(true, true, false);
        assertThat(all.get(2).ownerName()).isEqualTo("Anna");
        assertThatThrownBy(() -> service.list("jemand")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirmOnNonMaybeIsConflict() {
        stored(1L, 3L);
        doThrow(new IllegalStateException("Nur unsichere Treffer lassen sich bestätigen"))
                .when(matching).recordUserVerdict(1L, "fp", true);

        assertThatThrownBy(() -> service.verdict(1L, new VerdictRequest("fp", true)))
                .isInstanceOf(GroceryConflictException.class);
    }

    @Test
    void updateWithEmptySearchWordsUsesSuggestionAndKeepsChosenUnit() {
        GroceryProduct product = stored(5L, 3L);
        when(suggester.suggest("Kaffee ganze Bohnen 1kg"))
                .thenReturn(new Suggestion(List.of("kaffee", "bohnen"), BaseUnit.PIECE));

        service.update(5L, new ProductRequest("Kaffee ganze Bohnen 1kg", null, BaseUnit.KILOGRAM, null,
                List.of(), List.of(), null, null));

        assertThat(product.getSearchWords()).isEqualTo("kaffee,bohnen");
        assertThat(product.getBaseUnit()).isEqualTo(BaseUnit.KILOGRAM);
    }

    @Test
    void updateFailsWhenNeitherInputNorSuggestionHasSearchWords() {
        stored(6L, 3L);
        when(suggester.suggest("7")).thenReturn(new Suggestion(List.of(), BaseUnit.PIECE));

        assertThatThrownBy(() -> service.update(6L, request("7", List.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Bitte Suchwörter angeben.");
        verify(products, never()).save(any());
    }

    @Test
    void changingNameOrUnitDropsTheEstimateButOtherEditsKeepIt() {
        GroceryProduct product = stored(7L, 3L);
        product.setEstimatedPricePerUnit(new BigDecimal("2.10"));
        product.setEstimatedAt(java.time.LocalDateTime.of(2026, 11, 10, 4, 0));

        service.update(7L, new ProductRequest("Cola", null, BaseUnit.LITER, List.of("cola", "coca"), List.of(),
                List.of(), null, null));
        assertThat(product.getEstimatedPricePerUnit()).isEqualByComparingTo("2.10");

        service.update(7L, request("Cola Zero", List.of("cola")));
        assertThat(product.getEstimatedPricePerUnit()).isNull();
        assertThat(product.getEstimatedAt()).isNull();
    }
}
