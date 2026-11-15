package com.cloudpulse.order.events;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class PubSubEventPublisher implements EventPublisher {
    private final PubSubProperties props;
    private final Map<String, Publisher> publishers = new ConcurrentHashMap<>();
    private ManagedChannel emulatorChannel;

    public PubSubEventPublisher(PubSubProperties props) {
        this.props = props;
    }

    @Override
    public void publish(String topic, String jsonPayload, Map<String, String> attributes) throws Exception {
        Publisher publisher = publishers.computeIfAbsent(topic, this::newPublisher);
        PubsubMessage msg = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(jsonPayload))
                .putAllAttributes(attributes)
                .build();
        publisher.publish(msg).get(10, TimeUnit.SECONDS);
    }

    private synchronized Publisher newPublisher(String topic) {
        try {
            Publisher.Builder b = Publisher.newBuilder(TopicName.of(props.projectId(), topic));
            if (props.useEmulator()) {
                if (emulatorChannel == null) {
                    emulatorChannel = ManagedChannelBuilder.forTarget(props.emulatorHost()).usePlaintext().build();
                }
                b.setChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(emulatorChannel)))
                 .setCredentialsProvider(NoCredentialsProvider.create());
            }
            return b.build();   // real GCP: Application Default Credentials / Workload Identity
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create Pub/Sub publisher for " + topic, e);
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        for (Publisher p : publishers.values()) {
            p.shutdown();
            p.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (emulatorChannel != null) emulatorChannel.shutdown();
    }
}
