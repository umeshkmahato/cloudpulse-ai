package com.cloudpulse.operations.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Read model over the analytics store (Postgres schema `analytics` locally; BigQuery in cloud - that adapter is a
 * later step and unverified). The sink owns the schema and is the only writer; this service only reads.
 */
@Service
public class AnalyticsQueryService {
    private static final String LATENCY_UNION = """
            select 'order.created' as type, occurred_at, ingested_at from analytics.order_events
            union all select 'inventory.updated', occurred_at, ingested_at from analytics.inventory_events
            union all select 'metric.sample', occurred_at, ingested_at from analytics.metric_samples""";

    private final JdbcTemplate jdbc;

    public AnalyticsQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> overview(int minutes) {
        Map<String, Object> orders = jdbc.queryForMap("""
                select count(*) as orders, coalesce(sum(total_amount), 0) as order_value
                from analytics.order_events where occurred_at > now() - make_interval(mins => ?)""", minutes);
        List<Map<String, Object>> outcomes = jdbc.queryForList("""
                select status, count(*) as count from analytics.inventory_events
                where occurred_at > now() - make_interval(mins => ?) group by status order by status""", minutes);
        Map<String, Object> metrics = jdbc.queryForMap("""
                select count(*) as samples, coalesce(bool_or(synthetic), false) as includes_synthetic
                from analytics.metric_samples where observed_at > now() - make_interval(mins => ?)""", minutes);
        return Map.of("windowMinutes", minutes, "orders", orders, "inventoryOutcomes", outcomes, "metrics", metrics,
                "dataSource", "analytics store (local Postgres stand-in for BigQuery)");
    }

    public List<Map<String, Object>> orderTrend(int minutes, int bucketSeconds) {
        return jdbc.queryForList("""
                select to_timestamp(floor(extract(epoch from occurred_at) / ?) * ?) as bucket,
                       count(*) as orders, coalesce(sum(total_amount), 0) as order_value
                from analytics.order_events where occurred_at > now() - make_interval(mins => ?)
                group by 1 order by 1""", bucketSeconds, bucketSeconds, minutes);
    }

    public List<Map<String, Object>> inventoryOutcomes(int minutes) {
        return jdbc.queryForList("""
                select status, coalesce(reason, '') as reason, count(*) as count
                from analytics.inventory_events where occurred_at > now() - make_interval(mins => ?)
                group by 1, 2 order by count desc""", minutes);
    }

    public List<Map<String, Object>> metricTrend(String service, String metric, int minutes, int bucketSeconds) {
        return jdbc.queryForList("""
                select to_timestamp(floor(extract(epoch from observed_at) / ?) * ?) as bucket,
                       avg(value) as avg, max(value) as max, count(*) as samples, bool_or(synthetic) as synthetic
                from analytics.metric_samples
                where service = ? and metric = ? and observed_at > now() - make_interval(mins => ?)
                group by 1 order by 1""", bucketSeconds, bucketSeconds, service, metric, minutes);
    }

    /** Event-processing latency = ingested_at - occurred_at, i.e. event time to analytics-store availability. */
    public List<Map<String, Object>> pipelineLatency(int minutes) {
        return jdbc.queryForList("select type, count(*) as events, "
                + "percentile_cont(0.5) within group (order by extract(epoch from (ingested_at - occurred_at)) * 1000) as p50_ms, "
                + "percentile_cont(0.95) within group (order by extract(epoch from (ingested_at - occurred_at)) * 1000) as p95_ms "
                + "from (" + LATENCY_UNION + ") e where occurred_at > now() - make_interval(mins => ?) "
                + "group by type order by type", minutes);
    }

    public List<Map<String, Object>> recentEvents(int limit, boolean includeMetrics) {
        return jdbc.queryForList("""
                select * from (
                  select 'order.created' as type, event_id, occurred_at, correlation_id,
                         'order ' || order_id || ' total ' || coalesce(total_amount::text, '?') as summary from analytics.order_events
                  union all
                  select 'inventory.updated', event_id, occurred_at, correlation_id,
                         'order ' || order_id || ' ' || coalesce(status, '?') || coalesce(' (' || reason || ')', '') from analytics.inventory_events
                  union all
                  select 'metric.sample', event_id, occurred_at, null,
                         service || ' ' || metric || '=' || round(value::numeric, 2)::text ||
                         case when synthetic then ' [synthetic]' else '' end from analytics.metric_samples
                ) e where (? or type <> 'metric.sample') order by occurred_at desc limit ?""", includeMetrics, limit);
    }
}
