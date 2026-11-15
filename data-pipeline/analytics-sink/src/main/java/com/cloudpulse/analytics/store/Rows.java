package com.cloudpulse.analytics.store;

import java.math.BigDecimal;
import java.time.Instant;

/** Row shapes mirror the BigQuery tables defined in infrastructure/terraform/modules/bigquery. */
public final class Rows {
    public record OrderRow(String eventId, Instant occurredAt, String correlationId, String orderId, String customerId,
                           BigDecimal totalAmount, String currency, String payloadJson) {}

    public record InventoryRow(String eventId, Instant occurredAt, String correlationId, String orderId, String status,
                               String reason, String payloadJson) {}

    public record MetricRow(String eventId, Instant occurredAt, Instant observedAt, String service, String metric,
                            double value, String unit, boolean synthetic) {}

    private Rows() {}
}
