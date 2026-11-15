package com.cloudpulse.order.api;

import com.cloudpulse.order.domain.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(String orderId, String customerId, String status, BigDecimal totalAmount,
                            String currency, Instant createdAt, List<ItemView> items, String statusReason) {

    public record ItemView(String sku, int quantity, BigDecimal unitPrice) {}

    public static OrderResponse from(Order o) {
        return new OrderResponse(o.getOrderId(), o.getCustomerId(), o.getStatus().name(), o.getTotalAmount(),
                o.getCurrency().trim(), o.getCreatedAt(),
                o.getItems().stream().map(i -> new ItemView(i.getSku(), i.getQuantity(), i.getUnitPrice())).toList(), o.getStatusReason());
    }
}
