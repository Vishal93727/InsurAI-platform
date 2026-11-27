package com.insurai.platform.fraudengine.infrastructure.kafka;

/**
 * Names of the fraud engine's Kafka beans. They are named (and qualified at every injection point) because the
 * platform hosts other Kafka users with different serialization; see {@code KafkaConfig}.
 */
public final class KafkaBeans {

    /** JSON-serialising {@code KafkaTemplate<String, Object>}. */
    public static final String TEMPLATE = "fraudKafkaTemplate";
    /** Listener factory for {@code transactions.incoming}. */
    public static final String LISTENER_FACTORY = "fraudListenerContainerFactory";
    /** Listener factory for ledger events (raw String payloads). */
    public static final String LEDGER_LISTENER_FACTORY = "ledgerTransferListenerContainerFactory";

    private KafkaBeans() {
    }
}
