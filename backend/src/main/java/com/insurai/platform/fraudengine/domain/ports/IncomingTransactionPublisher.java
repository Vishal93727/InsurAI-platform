package com.insurai.platform.fraudengine.domain.ports;

import com.insurai.platform.fraudengine.domain.model.TransactionRequest;

/**
 * Outbound port for handing an accepted transaction to the asynchronous
 * processing pipeline.
 */
public interface IncomingTransactionPublisher {

    /**
     * Publishes a transaction for asynchronous evaluation.
     *
     * @param request the accepted transaction
     */
    void publish(TransactionRequest request);
}
