package com.cloudpulse.remediation.api;

import com.cloudpulse.remediation.RemediationProperties;
import com.cloudpulse.remediation.engine.RemediationEngine;
import com.cloudpulse.remediation.k8s.KubernetesScaler;
import com.cloudpulse.remediation.model.Remediation;
import com.cloudpulse.remediation.store.RemediationStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Remediation")
@Validated
public class RemediationController {
    public record Decision(@Size(max = 300) String comment) {}

    public record KillSwitch(boolean enabled) {}

    public record View(Remediation remediation, JsonNode guardrails, String nextStep) {}

    private final RemediationEngine engine;
    private final RemediationStore store;
    private final JwtAuth auth;
    private final RemediationProperties props;
    private final KubernetesScaler scaler;
    private final ObjectMapper mapper;

    public RemediationController(RemediationEngine engine, RemediationStore store, JwtAuth auth, RemediationProperties props, KubernetesScaler scaler, ObjectMapper mapper) {
        this.engine = engine;
        this.store = store;
        this.auth = auth;
        this.props = props;
        this.scaler = scaler;
        this.mapper = mapper;
    }

    @GetMapping("/remediations")
    @Operation(summary = "Remediation proposals and their outcomes (read-only, no auth)")
    public List<View> list(@RequestParam(required = false) @Pattern(regexp = "[A-Z_]{4,16}") String status, @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return store.list(status, limit).stream().map(this::view).toList();
    }

    @GetMapping("/remediations/{id}")
    public View get(@PathVariable UUID id) {
        return store.find(id).map(this::view).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "remediation not found"));
    }

    @GetMapping("/remediations/{id}/audit")
    @Operation(summary = "Append-only audit trail for one remediation")
    public List<Map<String, Object>> audit(@PathVariable UUID id) {
        return store.auditFor(id);
    }

    @PostMapping("/remediations/{id}/approve")
    @Operation(summary = "Approve a proposed action. Requires an OPERATOR token (operator identity comes from the verified token)")
    public View approve(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String authz, @Valid @RequestBody Decision d) {
        var who = auth.require(authz, JwtAuth.Role.OPERATOR);
        try { return view(engine.approve(id, who.name(), d.comment())); }
        catch (NoSuchElementException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage()); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()); }
    }

    @PostMapping("/remediations/{id}/reject")
    @Operation(summary = "Reject a proposed action. Requires an OPERATOR token (operator identity comes from the verified token)")
    public View reject(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String authz, @Valid @RequestBody Decision d) {
        var who = auth.require(authz, JwtAuth.Role.OPERATOR);
        try { return view(engine.reject(id, who.name(), d.comment())); }
        catch (NoSuchElementException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage()); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()); }
    }

    @GetMapping("/remediation/status")
    @Operation(summary = "Controller mode, kill switch, allow-list and automated policy")
    public Map<String, Object> status() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("mode", scaler.mode());
        out.put("killSwitch", store.killSwitchOn());
        out.put("approvalsEnabled", true);
        out.put("authentication", "JWT from identity-service: OPERATOR to approve/reject, ADMIN for the kill switch");
        out.put("allowList", props.targets());
        out.put("automatedPolicy", props.policy());
        out.put("cooldownMinutes", props.cooldownMinutes());
        out.put("maxActionsPerHour", props.maxActionsPerHour());
        out.put("monitorWindowMinutes", props.monitorWindowMinutes());
        out.put("executableActions", List.of("Raise the HPA capacity floor of an allow-listed deployment (bounded), restored after recovery"));
        out.put("now", Instant.now().toString());
        return out;
    }

    @PostMapping("/remediation/kill-switch")
    @Operation(summary = "Pause/resume all execution. Approved work waits while paused. Requires an ADMIN token")
    public Map<String, Object> killSwitch(@RequestHeader(value = "Authorization", required = false) String authz, @Valid @RequestBody KillSwitch k) {
        var who = auth.require(authz, JwtAuth.Role.ADMIN);
        store.setKillSwitch(k.enabled());
        store.audit(null, who.name(), k.enabled() ? "KILL_SWITCH_ON" : "KILL_SWITCH_OFF", "execution " + (k.enabled() ? "paused" : "resumed"));
        return Map.of("killSwitch", k.enabled());
    }

    @GetMapping("/remediation/audit")
    public List<Map<String, Object>> recentAudit(@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return store.recentAudit(limit);
    }

    private View view(Remediation r) {
        JsonNode g;
        try { g = mapper.readTree(r.guardrailNotes()); } catch (Exception e) { g = mapper.createArrayNode(); }
        String next = switch (r.status()) {
            case PROPOSED -> "Awaiting approval by an authorised operator (expires " + r.expiresAt() + ")";
            case APPROVED -> store.killSwitchOn() ? "Approved, but execution is paused by the kill switch" : "Approved: executes on the next controller cycle";
            case MONITORING -> "Executed: monitoring recovery";
            default -> r.outcomeDetail() == null ? r.status().name() : r.outcomeDetail();
        };
        return new View(r, g, next);
    }
}
