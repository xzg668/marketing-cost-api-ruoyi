package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplyPriceDatePolicyTest {
  private final LocalDate november = LocalDate.parse("2026-11-30");
  private record Quote(String supplier, LocalDate from, LocalDate to) {}
  private Quote quote(String supplier, String from, String to) {
    return new Quote(supplier, from == null ? null : LocalDate.parse(from), to == null ? null : LocalDate.parse(to));
  }
  private List<Quote> fixed(List<Quote> rows) {
    return SupplyPriceDatePolicy.preferCurrentOrLatestPeriod(rows, november, Quote::from, Quote::to);
  }

  @Test void latestExpiredPeriodWinsAcrossSuppliersRegardlessOfInputOrder() {
    var latest = quote("A", "2025-11-01", "2026-10-31");
    var older = quote("B", "2025-08-01", "2026-07-31");
    assertThat(fixed(List.of(older, latest))).containsExactly(latest);
    assertThat(fixed(List.of(latest, older))).containsExactly(latest);
  }
  @Test void allCurrentSuppliersRemainEligibleIncludingBothBoundaries() {
    var a = quote("A", "2026-11-30", "2027-01-31");
    var b = quote("B", "2026-01-01", "2026-11-30");
    var future = quote("C", "2026-12-01", "2027-12-31");
    var old = quote("D", "2025-01-01", "2026-10-31");
    assertThat(fixed(List.of(a, b, future, old))).containsExactly(a, b);
  }
  @Test void latestPeriodTiesRemainForSupplierSelection() {
    var a = quote("A", "2025-11-01", "2026-10-31");
    var b = quote("B", "2025-11-01", "2026-10-31");
    var shorter = quote("C", "2025-11-01", "2026-07-31");
    assertThat(fixed(List.of(a, shorter, b))).containsExactly(a, b);
  }
  @Test void noCurrentPriceRetainsExistingFuturePeriodFallback() {
    var a = quote("A", "2027-01-01", "2027-12-31");
    var b = quote("B", "2026-12-01", "2027-11-30");
    assertThat(fixed(List.of(b, a))).containsExactly(a);
  }
  @Test void openDatesRemainCurrentAndEmptyInputRemainsEmpty() {
    var a = quote("A", null, null);
    var b = quote("B", "2025-01-01", "2026-10-31");
    assertThat(fixed(List.of(a, b))).containsExactly(a);
    assertThat(fixed(List.of())).isEmpty();
  }
  @Test void orderAndTierConsumersKeepTheirOriginalFallbackPolicy() {
    var a = quote("A", "2025-11-01", "2026-10-31");
    var b = quote("B", "2025-08-01", "2026-07-31");
    assertThat(SupplyPriceDatePolicy.preferCurrent(List.of(a,b),november,Quote::from,Quote::to))
        .containsExactly(a,b);
  }
}
