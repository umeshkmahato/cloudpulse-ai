package com.cloudpulse.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "reservations")
public class Reservation {
    @Id
    @Column(name = "order_id")
    private String orderId;
    @Column(nullable = false)
    private String status;   // RESERVED | REJECTED
    private String reason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Reservation() {}

    public Reservation(String orderId, String status, String reason, Instant createdAt) {
        this.orderId = orderId;
        this.status = status;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public String getOrderId() { return orderId; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
}
