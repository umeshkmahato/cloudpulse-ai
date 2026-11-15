package com.cloudpulse.inventory.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/** Consumer-side view of order.created v1. Unknown fields are ignored (tolerant reader). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCreatedData(String orderId, String customerId, List<Line> items,
                               BigDecimal totalAmount, String currency) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String sku, int quantity) {}
}
