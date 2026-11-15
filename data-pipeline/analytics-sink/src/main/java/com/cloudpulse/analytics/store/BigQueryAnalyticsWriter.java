package com.cloudpulse.analytics.store;

import com.cloudpulse.analytics.PubSubProperties;
import com.cloudpulse.analytics.SinkProperties;
import com.cloudpulse.analytics.store.Rows.InventoryRow;
import com.cloudpulse.analytics.store.Rows.MetricRow;
import com.cloudpulse.analytics.store.Rows.OrderRow;
import com.google.cloud.bigquery.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * BigQuery adapter (streaming insertAll; insertId = eventId gives best-effort dedupe).
 * NOT VERIFIED against real BigQuery yet: it needs the GCP project (cloud enablement step).
 * Select with SINK_TARGET=bigquery. Streaming inserts are billed - verify pricing first.
 */
@Component
@ConditionalOnProperty(name = "cloudpulse.sink.target", havingValue = "bigquery")
public class BigQueryAnalyticsWriter implements AnalyticsWriter {
    private final BigQuery bq;
    private final String dataset;

    public BigQueryAnalyticsWriter(SinkProperties sink, PubSubProperties gcp) {
        this.bq = BigQueryOptions.newBuilder().setProjectId(gcp.projectId()).build().getService();
        this.dataset = sink.bigqueryDataset();
    }

    @Override
    public boolean write(OrderRow r) {
        Map<String, Object> m = base(r.eventId(), r.occurredAt(), r.correlationId());
        m.put("order_id", r.orderId());
        m.put("customer_id", r.customerId());
        m.put("total_amount", r.totalAmount() == null ? null : r.totalAmount().toPlainString());
        m.put("currency", r.currency());
        m.put("payload", r.payloadJson());
        return insert("order_events", r.eventId(), m);
    }

    @Override
    public boolean write(InventoryRow r) {
        Map<String, Object> m = base(r.eventId(), r.occurredAt(), r.correlationId());
        m.put("order_id", r.orderId());
        m.put("status", r.status());
        m.put("reason", r.reason());
        m.put("payload", r.payloadJson());
        return insert("inventory_events", r.eventId(), m);
    }

    @Override
    public boolean write(MetricRow r) {
        Map<String, Object> m = new HashMap<>();
        m.put("event_id", r.eventId());
        m.put("occurred_at", r.occurredAt().toString());
        m.put("observed_at", r.observedAt().toString());
        m.put("service", r.service());
        m.put("metric", r.metric());
        m.put("value", r.value());
        m.put("unit", r.unit());
        m.put("synthetic", r.synthetic());
        m.put("ingested_at", Instant.now().toString());
        return insert("metric_samples", r.eventId(), m);
    }

    private Map<String, Object> base(String eventId, Instant occurredAt, String correlationId) {
        Map<String, Object> m = new HashMap<>();
        m.put("event_id", eventId);
        m.put("occurred_at", occurredAt.toString());
        m.put("correlation_id", correlationId);
        m.put("ingested_at", Instant.now().toString());
        return m;
    }

    private boolean insert(String table, String insertId, Map<String, Object> row) {
        InsertAllResponse resp = bq.insertAll(InsertAllRequest.newBuilder(TableId.of(dataset, table))
                .addRow(insertId, row).build());
        if (resp.hasErrors()) {
            throw new IllegalStateException("BigQuery insert failed for " + table + ": " + resp.getInsertErrors());
        }
        return true; // BigQuery does not report dedupe hits
    }
}
