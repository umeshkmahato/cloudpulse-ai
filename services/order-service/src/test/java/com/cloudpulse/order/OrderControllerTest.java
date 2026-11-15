package com.cloudpulse.order;

import com.cloudpulse.order.api.GlobalExceptionHandler;
import com.cloudpulse.order.api.OrderController;
import com.cloudpulse.order.api.OrderNotFoundException;
import com.cloudpulse.order.api.OrderResponse;
import com.cloudpulse.order.service.OrderService;
import com.cloudpulse.order.web.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class})
class OrderControllerTest {
    @Autowired MockMvc mvc;
    @MockBean OrderService service;

    private static final String VALID = """
            {"customerId":"CUST-1","currency":"USD","items":[{"sku":"SKU-1","quantity":2,"unitPrice":10.50}]}""";

    private OrderResponse sample() {
        return new OrderResponse("ORD-ABC123456789", "CUST-1", "PENDING", new BigDecimal("21.00"), "USD",
                Instant.parse("2026-01-01T00:00:00Z"), List.of(new OrderResponse.ItemView("SKU-1", 2, new BigDecimal("10.50"))), null);
    }

    @Test
    void postValidReturns201WithLocationAndCorrelationHeader() throws Exception {
        when(service.create(any(), eq(null))).thenReturn(sample());
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(VALID)
                        .header("X-Correlation-ID", "test-corr-1"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/orders/ORD-ABC123456789"))
                .andExpect(header().string("X-Correlation-ID", "test-corr-1"))
                .andExpect(jsonPath("$.orderId").value("ORD-ABC123456789"));
    }

    @Test
    void postInvalidReturns400ProblemWithFieldErrors() throws Exception {
        String bad = """
                {"customerId":"","currency":"usd","items":[{"sku":"S","quantity":0,"unitPrice":-1}]}""";
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.customerId").exists())
                .andExpect(jsonPath("$.errors.currency").exists())
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void postEmptyItemsReturns400() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"C\",\"currency\":\"USD\",\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsafeCorrelationIdIsReplaced() throws Exception {
        when(service.get("ORD-1")).thenReturn(sample());
        mvc.perform(get("/api/v1/orders/ORD-1").header("X-Correlation-ID", "bad id\"{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", org.hamcrest.Matchers.matchesPattern("^[0-9a-f-]{36}$")));
    }

    @Test
    void getUnknownReturns404Problem() throws Exception {
        when(service.get("ORD-NOPE")).thenThrow(new OrderNotFoundException("ORD-NOPE"));
        mvc.perform(get("/api/v1/orders/ORD-NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));
    }
}
