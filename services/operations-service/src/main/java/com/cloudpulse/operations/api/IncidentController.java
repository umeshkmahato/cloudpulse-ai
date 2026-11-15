package com.cloudpulse.operations.api;

import com.cloudpulse.operations.detect.DetectionService;
import com.cloudpulse.operations.detect.IncidentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Incidents & detection")
@Validated
public class IncidentController {
    private final IncidentService incidents;
    private final DetectionService detection;

    public IncidentController(IncidentService incidents, DetectionService detection) {
        this.incidents = incidents;
        this.detection = detection;
    }

    @GetMapping("/incidents")
    @Operation(summary = "Incident history. kind=DETECTED is an anomaly now; kind=FORECAST is a low-confidence trend extrapolation")
    public List<IncidentService.IncidentView> list(@RequestParam(required = false) @Pattern(regexp = "OPEN|RESOLVED") String status,
                                                   @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return incidents.list(status, limit);
    }

    @GetMapping("/incidents/{id}")
    @Operation(summary = "One incident with full evidence and recommended (never auto-executed) actions")
    public ResponseEntity<IncidentService.IncidentView> get(@PathVariable UUID id) {
        return incidents.get(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/detection/current")
    @Operation(summary = "Latest assessment per service: baselines, z-scores, EWMA, forecasts, risk, notes")
    public List<DetectionService.CurrentState> current() {
        return detection.current();
    }

    @PostMapping("/detection/evaluate")
    @Operation(summary = "Run a detection cycle now (demos/tests); the scheduler runs one every 10 s")
    public List<DetectionService.CurrentState> evaluate() {
        return detection.evaluateAll();
    }
}
