package com.cloudpulse.order.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Consumer-side view of inventory.updated v1 (tolerant reader). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryUpdatedData(String orderId, String status, String reason) {}
