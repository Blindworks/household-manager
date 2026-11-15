package com.household.manager.grocery;

import com.household.manager.exception.ErrorResponse;
import com.household.manager.grocery.dto.DealDto;
import com.household.manager.grocery.dto.DealsView;
import com.household.manager.grocery.dto.GrocerySummaryDto;
import com.household.manager.grocery.dto.PriceObservationDto;
import com.household.manager.grocery.dto.ProductDto;
import com.household.manager.grocery.dto.ProductRequest;
import com.household.manager.grocery.dto.VerdictRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** Produkte und Schnäppchen der Supermarkt-Angebote. {@code owner}: Nutzer-Id, {@code household} oder {@code all}. */
@Slf4j
@RestController
@RequestMapping("/v1/groceries")
public class GroceryController {

    private final GroceryProductService productService;
    private final GroceryDealQueryService dealQueryService;

    public GroceryController(GroceryProductService productService, GroceryDealQueryService dealQueryService) {
        this.productService = productService;
        this.dealQueryService = dealQueryService;
    }

    @GetMapping("/products")
    public List<ProductDto> products(@RequestParam(required = false) String owner) {
        return productService.list(owner);
    }

    @PostMapping("/products")
    public ProductDto create(@RequestBody ProductRequest request) {
        return productService.create(request);
    }

    @PutMapping("/products/{id}")
    public ProductDto update(@PathVariable long id, @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/products/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/products/{id}/offers")
    public List<DealDto> offers(@PathVariable long id) {
        return dealQueryService.offers(id);
    }

    @GetMapping("/products/{id}/price-history")
    public List<PriceObservationDto> priceHistory(@PathVariable long id) {
        return dealQueryService.priceHistory(id);
    }

    @PostMapping("/products/{id}/verdicts")
    public ResponseEntity<Void> verdict(@PathVariable long id, @RequestBody VerdictRequest request) {
        productService.verdict(id, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/deals")
    public DealsView deals(@RequestParam(required = false) String owner) {
        return dealQueryService.deals(owner);
    }

    @GetMapping("/summary")
    public GrocerySummaryDto summary() {
        return dealQueryService.summary();
    }

    /** Nur für diesen Controller: das globale IllegalState-Mapping (400) bleibt unberührt. */
    @ExceptionHandler(GroceryConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(GroceryConflictException ex) {
        log.warn("Urteil abgelehnt: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error("Conflict")
                .message(ex.getMessage())
                .build());
    }
}
