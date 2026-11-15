package com.cloudpulse.inventory.api;

import com.cloudpulse.inventory.domain.StockItem;
import com.cloudpulse.inventory.repo.StockRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory")
@Validated
public class InventoryController {
    public record StockView(String sku, String description, int available, Instant updatedAt) {
        static StockView of(StockItem s) { return new StockView(s.getSku(), s.getDescription(), s.getAvailable(), s.getUpdatedAt()); }
    }

    public record SetStockRequest(@Min(0) @Max(1_000_000) int available, @Size(max = 200) String description) {}

    private static final String SKU = "^[A-Za-z0-9._-]{1,64}$";
    private final StockRepository repo;

    public InventoryController(StockRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    @Operation(summary = "List stock levels")
    public List<StockView> list() {
        return repo.findAll().stream().map(StockView::of).toList();
    }

    @GetMapping("/{sku}")
    @Operation(summary = "Get stock for a SKU")
    public StockView get(@PathVariable @Pattern(regexp = SKU) String sku) {
        return repo.findById(sku).map(StockView::of).orElseThrow(() -> new NotFoundException("SKU not found: " + sku));
    }

    @PutMapping("/{sku}")
    @Transactional
    @Operation(summary = "Create or set stock level for a SKU (admin)")
    public ResponseEntity<StockView> put(@PathVariable @Pattern(regexp = SKU) String sku, @Valid @RequestBody SetStockRequest req) {
        Instant now = Instant.now();
        var existing = repo.findById(sku);
        StockItem item = existing.orElseGet(() -> new StockItem(sku, req.description(), req.available(), now));
        existing.ifPresent(s -> s.setAvailable(req.available(), now));
        repo.save(item);
        return existing.isPresent() ? ResponseEntity.ok(StockView.of(item)) : ResponseEntity.status(201).body(StockView.of(item));
    }
}
