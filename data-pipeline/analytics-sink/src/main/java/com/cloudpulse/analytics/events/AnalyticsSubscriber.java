package com.cloudpulse.analytics.events;

import com.cloudpulse.analytics.PubSubProperties;
import com.cloudpulse.analytics.SinkProperties;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** One streaming-pull subscriber per configured subscription; ack after store write, nack on failure. */
@Component
public class AnalyticsSubscriber implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsSubscriber.class);

    private final SinkProperties sink;
    private final PubSubProperties gcp;
    private final EventRouter router;
    private final List<Subscriber> subscribers = new ArrayList<>();
    private ManagedChannel channel;

    public AnalyticsSubscriber(SinkProperties sink, PubSubProperties gcp, EventRouter router) {
        this.sink = sink;
        this.gcp = gcp;
        this.router = router;
    }

    @Override
    public void start() {
        if (!sink.consumerEnabled()) { log.info("consumers disabled"); return; }
        if (gcp.useEmulator()) {
            channel = ManagedChannelBuilder.forTarget(gcp.emulatorHost()).usePlaintext().build();
        }
        MessageReceiver receiver = (PubsubMessage msg, AckReplyConsumer consumer) -> {
            try {
                router.route(msg.getData().toStringUtf8());
                consumer.ack();
            } catch (Exception e) {
                log.warn("sink failed, nack messageId={} reason={}", msg.getMessageId(), e.toString());
                consumer.nack();
            }
        };
        for (String sub : sink.subscriptions()) {
            Subscriber.Builder b = Subscriber.newBuilder(ProjectSubscriptionName.of(gcp.projectId(), sub), receiver);
            if (channel != null) {
                b.setChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
                 .setCredentialsProvider(NoCredentialsProvider.create());
            }
            Subscriber s = b.build();
            s.startAsync().awaitRunning();
            subscribers.add(s);
            log.info("subscribed to {}", sub);
        }
    }

    @Override
    public void stop() {
        subscribers.forEach(Subscriber::stopAsync);
        for (Subscriber s : subscribers) {
            try { s.awaitTerminated(10, TimeUnit.SECONDS); } catch (Exception ignored) { }
        }
        subscribers.clear();
        if (channel != null) channel.shutdown();
    }

    @Override
    public boolean isRunning() { return !subscribers.isEmpty() && subscribers.stream().allMatch(Subscriber::isRunning); }
}
