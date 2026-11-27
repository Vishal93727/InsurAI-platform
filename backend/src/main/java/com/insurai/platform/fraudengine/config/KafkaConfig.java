package com.insurai.platform.fraudengine.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import com.insurai.platform.fraudengine.domain.model.TransactionRequest;
import com.insurai.platform.fraudengine.infrastructure.kafka.KafkaBeans;
import com.insurai.platform.fraudengine.infrastructure.kafka.KafkaTopics;

/**
 * Declares Kafka topics, the fraud engine's own producer and listener factories, and the consumer
 * error-handling policy. Failed records are retried a bounded number of times, then routed to the dead-letter
 * topic so a poison message cannot block its partition indefinitely.
 *
 * <p><b>Why these are module-private.</b> When the engine was a standalone service it set JSON serializers and
 * a manual-ack, dead-lettering error handler globally through {@code spring.kafka.*}. In the platform the core
 * module shares the same broker connection and expects String payloads with record-level acks, so none of that
 * may be global any more. Everything JSON-specific is therefore declared here as <em>named</em> beans and the
 * fraud listeners opt in explicitly. In particular the error handler is intentionally <em>not</em> a bean:
 * Spring Boot attaches a lone {@code CommonErrorHandler} bean to the default listener factory, which would
 * dead-letter the core module's notification records into fraud topics.
 */
@Configuration
public class KafkaConfig {

    private static final int RETRY_INTERVAL_MS = 1000;
    private static final int MAX_RETRIES = 2;

    @Bean
    public NewTopic incomingTopic() {
        return TopicBuilder.name(KafkaTopics.INCOMING).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic flaggedTopic() {
        return TopicBuilder.name(KafkaTopics.FLAGGED).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic incomingDltTopic() {
        return TopicBuilder.name(KafkaTopics.INCOMING_DLT).partitions(1).replicas(1).build();
    }

    /** JSON-serialising producer for {@code transactions.incoming} / {@code transactions.flagged} and the DLT. */
    @Bean(KafkaBeans.TEMPLATE)
    public KafkaTemplate<String, Object> fraudKafkaTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
        template.setObservationEnabled(true);
        return template;
    }

    /**
     * Retry, then dead-letter. A plain factory method, not a bean - see the class comment. Public so the factory
     * beans below and the unit tests build it the same way.
     */
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
    }

    /**
     * Listener factory for {@code transactions.incoming}: JSON in, {@link TransactionRequest} out. Wrapped in an
     * {@link ErrorHandlingDeserializer} so an undecodable record reaches the error handler instead of crashing
     * the poll loop. Type headers are ignored on purpose: the target type is fixed, and a Java class name carried
     * on the wire would couple every producer to this module's package layout.
     */
    @Bean(KafkaBeans.LISTENER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, TransactionRequest> fraudListenerContainerFactory(
            KafkaProperties kafkaProperties, @Qualifier(KafkaBeans.TEMPLATE) KafkaTemplate<String, Object> template) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.insurai.platform.fraudengine.*");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TransactionRequest.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        ConcurrentKafkaListenerContainerFactory<String, TransactionRequest> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setCommonErrorHandler(kafkaErrorHandler(template));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        // Hand-built, so it does not inherit spring.kafka.listener.observation-enabled (see the ledger factory).
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    /**
     * Listener factory for topics owned by other services. The fraud factory deserializes into
     * {@code TransactionRequest}; ledger events are a foreign schema, so they arrive as raw JSON and are decoded
     * at the mapping boundary.
     */
    @Bean(KafkaBeans.LEDGER_LISTENER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> ledgerTransferListenerContainerFactory(
            KafkaProperties kafkaProperties, @Qualifier(KafkaBeans.TEMPLATE) KafkaTemplate<String, Object> template) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setCommonErrorHandler(kafkaErrorHandler(template));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        // Set explicitly: this factory is built by hand, so it does not inherit
        // spring.kafka.listener.observation-enabled. Without it the consumer never
        // extracts the traceparent header and the ledger trace silently ends at the
        // broker instead of continuing into this service.
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
