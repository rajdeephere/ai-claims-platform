package com.claimsai.financials.domain;

import java.math.BigDecimal;

/**
 * Port to the system that actually moves money (in Guidewire, BillingCenter or the carrier's payment
 * platform). Implementations must be idempotent by key: the same key twice returns the first payment's
 * reference, never pays again.
 */
public interface PaymentRail {

    record Instruction(String idempotencyKey, BigDecimal amount, String payeeName, String claimNumber) {
    }

    /**
     * @return the rail's reference for the payment
     * @throws RailUnavailableException temporary: retry with the same key
     * @throws PaymentRefusedException  permanent: e.g. the payee's account is closed
     */
    String issue(Instruction instruction);

    class RailUnavailableException extends RuntimeException {
        public RailUnavailableException(String message) {
            super(message);
        }
    }

    class PaymentRefusedException extends RuntimeException {
        public PaymentRefusedException(String message) {
            super(message);
        }
    }
}
