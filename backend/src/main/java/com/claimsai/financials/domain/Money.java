package com.claimsai.financials.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Amounts are BigDecimal at scale 2, always; never double. */
public final class Money {

    private Money() {
    }

    public static final BigDecimal ZERO = new BigDecimal("0.00");

    public static BigDecimal of(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static boolean positive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }
}
