package com.zoro.legaloa.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class FinancePolicy {
    private FinancePolicy() {}

    public static BigDecimal timeAmount(BigDecimal hourlyRate, int minutes, boolean billable) {
        if (!billable) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (hourlyRate == null || hourlyRate.signum() < 0 || minutes < 1 || minutes > 1440) {
            throw new IllegalArgumentException("Invalid time billing inputs");
        }
        return hourlyRate.multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal tax(BigDecimal subtotal, BigDecimal taxPercent) {
        if (subtotal == null || taxPercent == null
                || subtotal.signum() < 0
                || taxPercent.signum() < 0
                || taxPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Invalid tax inputs");
        }
        return subtotal.multiply(taxPercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    public static boolean allocationFits(
            BigDecimal allocation,
            BigDecimal paymentAvailable,
            BigDecimal invoiceOutstanding
    ) {
        return allocation != null
                && allocation.signum() > 0
                && allocation.compareTo(paymentAvailable) <= 0
                && allocation.compareTo(invoiceOutstanding) <= 0;
    }
}
