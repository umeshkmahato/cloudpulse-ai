package com.cloudpulse.order.events;

import java.math.BigDecimal;
import java.util.List;

public record OrderCreatedData(String orderId, String customerId, List<Line> items,
                               BigDecimal totalAmount, String currency) {
    public record Line(String sku, int quantity, BigDecimal unitPrice) {}
}
