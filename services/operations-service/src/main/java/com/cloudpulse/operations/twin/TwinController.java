package com.cloudpulse.operations.twin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/twin")
@Tag(name = "Digital twin")
@Validated
public class TwinController {
    private final TwinService twin;

    public TwinController(TwinService twin) {
        this.twin = twin;
    }

    @GetMapping("/graph")
    @Operation(summary = "Dependency graph with live status overlay")
    public TwinService.Graph graph() {
        return twin.graph();
    }

    @GetMapping("/impact")
    @Operation(summary = "What happens if this component fails? Dependents, transactions, message recovery, recovery steps")
    public ResponseEntity<ImpactCalculator.Result> impact(@RequestParam @Pattern(regexp = "^[A-Za-z0-9._-]{1,64}$") String component,
                                                          @RequestParam(defaultValue = "current") @Pattern(regexp = "current|target") String scope) {
        return twin.impact(component, scope).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
