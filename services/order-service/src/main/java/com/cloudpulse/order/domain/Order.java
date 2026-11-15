package com.cloudpulse.order.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private String orderId;
    @Column(name = "customer_id", nullable = false)
    private String customerId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;
    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "status_reason")
    private String statusReason;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {}

    public Order(String orderId, String customerId, String currency, String idempotencyKey, Instant createdAt) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
        this.status = OrderStatus.PENDING;
        this.totalAmount = BigDecimal.ZERO;
    }

    public void addItem(String sku, int quantity, BigDecimal unitPrice) {
        items.add(new OrderItem(this, sku, quantity, unitPrice));
        totalAmount = totalAmount.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    public void confirm() { this.status = OrderStatus.CONFIRMED; this.statusReason = null; }

    public void reject(String reason) { this.status = OrderStatus.REJECTED; this.statusReason = reason; }

    public String getStatusReason() { return statusReason; }
    public String getOrderId() { return orderId; }
    public String getCustomerId() { return customerId; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public List<OrderItem> getItems() { return items; }
}
