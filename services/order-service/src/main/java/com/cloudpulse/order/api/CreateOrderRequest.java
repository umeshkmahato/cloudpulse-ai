package com.cloudpulse.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

public record CreateOrderRequest(
        @NotBlank @Size(max = 64) String customerId,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 code such as USD") String currency,
        @NotEmpty @Size(max = 50) List<@Valid Item> items) {

    public record Item(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "invalid SKU characters") String sku,
            @Min(1) @Max(1000) int quantity,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice) {}
}
