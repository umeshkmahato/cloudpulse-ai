package com.cloudpulse.assistant.api;

import com.cloudpulse.assistant.AssistantService;
import com.cloudpulse.assistant.Model.AssistantAnswer;
import com.cloudpulse.assistant.Model.Intent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/assistant")
@Tag(name = "AI incident assistant")
@Validated
public class AssistantController {
    public record AskRequest(@NotBlank @Size(max = 500) String question,
                             @Pattern(regexp = "^[A-Za-z0-9._-]{1,64}$") String service) {}

    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    @PostMapping("/ask")
    @Operation(summary = "Ask a question. Answers are built only from operational evidence; every claim cites evidence ids")
    public AssistantAnswer ask(@Valid @RequestBody AskRequest req) {
        return service.ask(req.question().trim(), req.service());
    }

    @GetMapping("/questions")
    @Operation(summary = "Supported questions")
    public List<String> questions() {
        return Arrays.stream(Intent.values()).map(i -> i.example).filter(s -> !s.isEmpty()).toList();
    }

    @GetMapping("/history")
    @Operation(summary = "Audit trail of recent answers (full evidence is stored per answer)")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.history(limit);
    }
}
