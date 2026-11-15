package com.cloudpulse.order;

import com.cloudpulse.order.domain.OutboxEvent;
import com.cloudpulse.order.events.EventPublisher;
import com.cloudpulse.order.events.OutboxRelay;
import com.cloudpulse.order.repo.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OutboxRelayTest {
    private final OutboxRepository repo = mock(OutboxRepository.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private OutboxRelay relay;

    private OutboxEvent event() {
        return new OutboxEvent(UUID.randomUUID(), "orders-created", "order.created", "corr-1", "{}", Instant.now());
    }

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(repo, publisher, new SimpleMeterRegistry(), 50);
    }

    @Test
    void successMarksPublished() throws Exception {
        OutboxEvent e = event();
        when(repo.lockUnpublished(any())).thenReturn(List.of(e));
        relay.relay();
        verify(publisher).publish(eq("orders-created"), eq("{}"), anyMap());
        assertThat(e.getPublishedAt()).isNotNull();
    }

    @Test
    void failureKeepsEventUnpublishedCountsAttemptAndStopsBatch() throws Exception {
        OutboxEvent first = event(), second = event();
        when(repo.lockUnpublished(any())).thenReturn(List.of(first, second));
        doThrow(new RuntimeException("broker down")).when(publisher).publish(anyString(), anyString(), anyMap());

        relay.relay();

        assertThat(first.getPublishedAt()).isNull();
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(second.getAttempts()).isZero();
        verify(publisher, times(1)).publish(anyString(), anyString(), anyMap());
    }
}
