package com.cloudpulse.inventory.events;

import java.util.List;

/** inventory.updated v1. status: RESERVED | REJECTED. */
public record InventoryUpdatedData(String orderId, String status, String reason, List<Line> items) {
    public record Line(String sku, int quantity, int remainingAvailable) {}
}
