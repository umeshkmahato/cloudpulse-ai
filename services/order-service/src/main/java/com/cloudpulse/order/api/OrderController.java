package com.cloudpulse.order.api;

import com.cloudpulse.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create an order; optional Idempotency-Key makes retries safe")
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        OrderResponse created = service.create(request, idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + created.orderId())).body(created);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get an order by its public order ID")
    public OrderResponse get(@PathVariable String orderId) {
        return service.get(orderId);
    }

    @GetMapping
    @Operation(summary = "List orders, newest first")
    public PageResponse<OrderResponse> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        return service.list(PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
    }
}
