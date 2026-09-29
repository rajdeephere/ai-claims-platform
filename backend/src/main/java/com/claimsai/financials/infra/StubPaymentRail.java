package com.claimsai.financials.infra;

import com.claimsai.financials.domain.PaymentRail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for the payment platform, behaving like a real payment API: idempotent by key, with its own
 * ledger (a table), so "pay" twice with one key returns the first reference. The payee name drives failure
 * scenarios for tests and demos:
 * <ul>
 *   <li>"REFUSE": permanent refusal (account closed)</li>
 *   <li>"FLAKY": unavailable on the first attempt, fine afterwards</li>
 *   <li>"DOWN": unavailable on every attempt (the payment ends up stuck, for a person to resolve)</li>
 *   <li>"LOST-RESPONSE": the money moves, but the answer is lost; the retry must NOT pay again</li>
 * </ul>
 */
@Component
public class StubPaymentRail implements PaymentRail {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Set<String> failedOnce = ConcurrentHashMap.newKeySet();

    public StubPaymentRail(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public String issue(Instruction instruction) {
        String payee = instruction.payeeName().toUpperCase(Locale.ROOT);
        if (payee.contains("REFUSE")) {
            throw new PaymentRefusedException("payee account closed");
        }
        if (payee.contains("DOWN")) {
            throw new RailUnavailableException("payment platform unreachable");
        }
        if (payee.contains("FLAKY") && failedOnce.add(instruction.idempotencyKey())) {
            throw new RailUnavailableException("payment platform timeout");
        }
        // the rail's side: record the payment once per key, return its reference
        jdbc.sql("""
                        INSERT INTO payment_rail_stub (idempotency_key, reference, amount, payee_name, created_at)
                        VALUES (:key, :reference, :amount, :payee, :now)
                        ON CONFLICT (idempotency_key) DO NOTHING""")
                .param("key", instruction.idempotencyKey())
                .param("reference", "PAY-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT))
                .param("amount", instruction.amount())
                .param("payee", instruction.payeeName())
                .param("now", Timestamp.from(clock.instant()))
                .update();
        String reference = jdbc.sql("SELECT reference FROM payment_rail_stub WHERE idempotency_key = :key")
                .param("key", instruction.idempotencyKey()).query(String.class).single();
        if (payee.contains("LOST-RESPONSE") && failedOnce.add(instruction.idempotencyKey())) {
            throw new RailUnavailableException("connection reset after the payment was made");
        }
        return reference;
    }
}
