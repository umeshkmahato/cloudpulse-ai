package com.cloudpulse.inventory.events;

import com.cloudpulse.inventory.service.ReservationService;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Streaming-pull consumer for orders-created. ack only after the DB transaction committed;
 * any failure -> nack, so Pub/Sub retries with backoff and finally routes to the dead-letter topic.
 */
@Component
public class OrderEventListener implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final PubSubProperties props;
    private final ReservationService service;
    private Subscriber subscriber;
    private ManagedChannel channel;

    public OrderEventListener(PubSubProperties props, ReservationService service) {
        this.props = props;
        this.service = service;
    }

    @Override
    public void start() {
        if (!props.consumerEnabled()) { log.info("consumer disabled"); return; }
        MessageReceiver receiver = (PubsubMessage msg, AckReplyConsumer consumer) -> {
            try {
                var outcome = service.handle(msg.getData().toStringUtf8());
                consumer.ack();
                log.debug("acked messageId={} outcome={}", msg.getMessageId(), outcome);
            } catch (Exception e) {
                log.error("processing failed, nack messageId={}", msg.getMessageId(), e);
                consumer.nack();
            }
        };
        Subscriber.Builder b = Subscriber.newBuilder(ProjectSubscriptionName.of(props.projectId(), props.ordersSubscription()), receiver);
        if (props.useEmulator()) {
            channel = ManagedChannelBuilder.forTarget(props.emulatorHost()).usePlaintext().build();
            b.setChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
             .setCredentialsProvider(NoCredentialsProvider.create());
        }
        subscriber = b.build();
        subscriber.startAsync().awaitRunning();
        log.info("subscribed to {}", props.ordersSubscription());
    }

    @Override
    public void stop() {
        if (subscriber != null) {
            subscriber.stopAsync();
            try { subscriber.awaitTerminated(10, TimeUnit.SECONDS); } catch (Exception ignored) { }
        }
        if (channel != null) channel.shutdown();
        subscriber = null;
    }

    @Override
    public boolean isRunning() { return subscriber != null && subscriber.isRunning(); }
}
