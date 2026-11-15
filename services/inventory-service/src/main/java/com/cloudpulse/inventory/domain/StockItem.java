package com.cloudpulse.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "stock_items")
public class StockItem {
    @Id
    private String sku;
    private String description;
    @Column(nullable = false)
    private int available;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockItem() {}

    public StockItem(String sku, String description, int available, Instant now) {
        this.sku = sku;
        this.description = description;
        this.available = available;
        this.updatedAt = now;
    }

    public boolean canReserve(int qty) { return available >= qty; }

    public void reserve(int qty, Instant now) {
        if (!canReserve(qty)) throw new IllegalStateException("insufficient stock for " + sku);
        available -= qty;
        updatedAt = now;
    }

    public void setAvailable(int available, Instant now) { this.available = available; this.updatedAt = now; }

    public String getSku() { return sku; }
    public String getDescription() { return description; }
    public int getAvailable() { return available; }
    public Instant getUpdatedAt() { return updatedAt; }
}
