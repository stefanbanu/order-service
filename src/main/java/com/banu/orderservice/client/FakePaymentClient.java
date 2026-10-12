package com.banu.orderservice.client;

import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A stand-in until a real payment provider exists: it moves no money and always
 * succeeds. No HTTP client is on the classpath, and adding one would mean adding
 * a starter plus its matching {@code -test} starter (see CLAUDE.md), which this
 * feature does not need — the cancellation rules only depend on whether the
 * refund throws.
 *
 * <p>The log line is a WARN that names itself a no-op: in a running app every
 * PAID cancellation is cancelled-but-not-refunded, and an INFO "Refunded ..."
 * would read like a real refund had happened. Registered unconditionally rather
 * than behind a profile because this project defines no profiles.
 *
 * <p>Logs through SLF4J directly: Lombok is kept to the entity in this project,
 * so there is no {@code @Slf4j} here.
 */
@Component
public class FakePaymentClient implements PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(FakePaymentClient.class);

    @Override
    public void refund(Long orderId, BigDecimal amount) {
        log.warn("NO-OP refund (no payment provider configured): {} for order {}", amount, orderId);
    }
}
