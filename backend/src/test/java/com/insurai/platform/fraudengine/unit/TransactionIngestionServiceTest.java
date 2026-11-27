package com.insurai.platform.fraudengine.unit;

import com.insurai.platform.fraudengine.application.TransactionIngestionService;
import com.insurai.platform.fraudengine.domain.model.TransactionRequest;
import com.insurai.platform.fraudengine.domain.ports.IncomingTransactionPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TransactionIngestionServiceTest {

    @Mock
    private IncomingTransactionPublisher publisher;

    @Test
    void accept_publishes_the_transaction() {
        TransactionIngestionService service = new TransactionIngestionService(publisher);
        TransactionRequest request = new TransactionRequest(
                "tx-1", "acc_001", new BigDecimal("100"), "USD",
                "merch_999", "name", "United States", Instant.now());

        service.accept(request);

        verify(publisher).publish(request);
    }
}
