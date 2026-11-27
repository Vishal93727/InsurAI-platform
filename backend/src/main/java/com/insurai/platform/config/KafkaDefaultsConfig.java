package com.insurai.platform.config;

import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * The platform's default Kafka producer: plain String keys and values.
 *
 * <p>Two modules produce to Kafka with different payload conventions. The core module's outbox relay sends
 * pre-serialised JSON envelopes ({@code KafkaTemplate<String, String>}); the fraud engine sends objects through
 * a JSON serializer. Spring Boot auto-configures exactly one {@code KafkaTemplate}/{@code ProducerFactory}, and
 * backs off as soon as any bean of those types exists. Because the fraud module has to declare its own, the
 * String-typed defaults that Boot would have provided are declared here explicitly, so each module keeps the
 * serialization it was written for. The fraud engine's JSON producer lives in its own configuration class.
 */
@Configuration(proxyBeanMethods = false)
class KafkaDefaultsConfig {

    @Bean
    ProducerFactory<String, String> kafkaProducerFactory(KafkaProperties properties) {
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(null));
    }

    @Bean
    KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> kafkaProducerFactory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(kafkaProducerFactory);
        // Hand-built, so it does not inherit spring.kafka.template.observation-enabled.
        template.setObservationEnabled(true);
        return template;
    }
}
