package com.cloudpulse.analytics.store;

import com.cloudpulse.analytics.store.Rows.InventoryRow;
import com.cloudpulse.analytics.store.Rows.MetricRow;
import com.cloudpulse.analytics.store.Rows.OrderRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/** Local analytics store (Postgres schema `analytics`). Idempotent via ON CONFLICT DO NOTHING. */
@Component
@ConditionalOnProperty(name = "cloudpulse.sink.target", havingValue = "postgres", matchIfMissing = true)
public class JdbcAnalyticsWriter implements AnalyticsWriter {
    private final JdbcTemplate jdbc;

    public JdbcAnalyticsWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp ts(Instant i) { return Timestamp.from(i); }

    @Override
    public boolean write(OrderRow r) {
        return jdbc.update("""
                INSERT INTO analytics.order_events (event_id, occurred_at, correlation_id, order_id, customer_id,
                    total_amount, currency, payload, ingested_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (event_id) DO NOTHING""",
                r.eventId(), ts(r.occurredAt()), r.correlationId(), r.orderId(), r.customerId(), r.totalAmount(),
                r.currency(), r.payloadJson(), ts(Instant.now())) > 0;
    }

    @Override
    public boolean write(InventoryRow r) {
        return jdbc.update("""
                INSERT INTO analytics.inventory_events (event_id, occurred_at, correlation_id, order_id, status, reason,
                    payload, ingested_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (event_id) DO NOTHING""",
                r.eventId(), ts(r.occurredAt()), r.correlationId(), r.orderId(), r.status(), r.reason(),
                r.payloadJson(), ts(Instant.now())) > 0;
    }

    @Override
    public boolean write(MetricRow r) {
        return jdbc.update("""
                INSERT INTO analytics.metric_samples (event_id, occurred_at, observed_at, service, metric, value, unit,
                    synthetic, ingested_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING""",
                r.eventId(), ts(r.occurredAt()), ts(r.observedAt()), r.service(), r.metric(), r.value(), r.unit(),
                r.synthetic(), ts(Instant.now())) > 0;
    }
}
