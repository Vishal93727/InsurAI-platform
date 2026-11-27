package com.insurai.platform.fraudengine.infrastructure.kafka;

import com.insurai.platform.fraudengine.domain.model.FraudDecision;
import com.insurai.platform.fraudengine.domain.model.TransactionRequest;
import com.insurai.platform.fraudengine.domain.ports.FlaggedTransactionPublisher;
import com.insurai.platform.fraudengine.domain.ports.IncomingTransactionPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka-backed implementation of the publishing ports. The transaction id is
 * used as the partition key so all events for one transaction stay ordered on
 * the same partition.
 */
@Component
public class KafkaTransactionPublisher implements IncomingTransactionPublisher, FlaggedTransactionPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaTransactionPublisher(@Qualifier(KafkaBeans.TEMPLATE) KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(TransactionRequest request) {
        kafkaTemplate.send(KafkaTopics.INCOMING, request.transactionId(), request);
    }

    @Override
    public void publish(FraudDecision decision) {
        kafkaTemplate.send(KafkaTopics.FLAGGED, decision.transactionId(), decision);
    }
}
