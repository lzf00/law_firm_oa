package com.zoro.legaloa.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FinancePolicyTest {
    @Test
    void snapshotsHourlyAmountWithCurrencyRounding() {
        assertThat(FinancePolicy.timeAmount(new BigDecimal("1200.00"), 75, true))
                .isEqualByComparingTo("1500.00");
    }

    @Test
    void nonBillableTimeHasNoAmount() {
        assertThat(FinancePolicy.timeAmount(new BigDecimal("1200.00"), 75, false))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void calculatesTaxAndRejectsInvalidRate() {
        assertThat(FinancePolicy.tax(new BigDecimal("1000"), new BigDecimal("6")))
                .isEqualByComparingTo("60.00");
        assertThatThrownBy(() -> FinancePolicy.tax(
                new BigDecimal("1000"), new BigDecimal("101")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allocationMustFitBothPaymentAndInvoiceBalance() {
        assertThat(FinancePolicy.allocationFits(
                new BigDecimal("300"), new BigDecimal("500"), new BigDecimal("400")
        )).isTrue();
        assertThat(FinancePolicy.allocationFits(
                new BigDecimal("450"), new BigDecimal("500"), new BigDecimal("400")
        )).isFalse();
    }
}
