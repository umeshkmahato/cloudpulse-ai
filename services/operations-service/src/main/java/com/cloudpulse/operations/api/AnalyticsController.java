package com.cloudpulse.operations.api;

import com.cloudpulse.operations.service.AnalyticsQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics")
@Validated
public class AnalyticsController {
    private static final String NAME = "^[A-Za-z0-9._-]{1,64}$";
    private final AnalyticsQueryService analytics;

    public AnalyticsController(AnalyticsQueryService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/overview")
    @Operation(summary = "Current operational/business snapshot over a window")
    public Map<String, Object> overview(@RequestParam(defaultValue = "60") @Min(1) @Max(1440) int minutes) {
        return analytics.overview(minutes);
    }

    @GetMapping("/orders/trend")
    @Operation(summary = "Order count and value per time bucket")
    public List<Map<String, Object>> orderTrend(@RequestParam(defaultValue = "60") @Min(1) @Max(1440) int minutes,
                                                @RequestParam(defaultValue = "60") @Min(10) @Max(3600) int bucketSeconds) {
        return analytics.orderTrend(minutes, bucketSeconds);
    }

    @GetMapping("/inventory/outcomes")
    @Operation(summary = "Reservation outcomes and rejection reasons (business error distribution)")
    public List<Map<String, Object>> outcomes(@RequestParam(defaultValue = "60") @Min(1) @Max(1440) int minutes) {
        return analytics.inventoryOutcomes(minutes);
    }

    @GetMapping("/metrics/trend")
    @Operation(summary = "Historical trend (avg/max per bucket) for one service metric")
    public List<Map<String, Object>> metricTrend(@RequestParam @Pattern(regexp = NAME) String service,
                                                 @RequestParam @Pattern(regexp = NAME) String metric,
                                                 @RequestParam(defaultValue = "60") @Min(1) @Max(1440) int minutes,
                                                 @RequestParam(defaultValue = "60") @Min(10) @Max(3600) int bucketSeconds) {
        return analytics.metricTrend(service, metric, minutes, bucketSeconds);
    }

    @GetMapping("/pipeline/latency")
    @Operation(summary = "Event processing latency (event time to analytics store), p50/p95 per event type")
    public List<Map<String, Object>> latency(@RequestParam(defaultValue = "60") @Min(1) @Max(1440) int minutes) {
        return analytics.pipelineLatency(minutes);
    }

    @GetMapping("/events/recent")
    @Operation(summary = "Most recent business events (event stream panel); metric samples only with includeMetrics=true")
    public List<Map<String, Object>> recent(@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
                                            @RequestParam(defaultValue = "false") boolean includeMetrics) {
        return analytics.recentEvents(limit, includeMetrics);
    }
}
