package com.sanhua.marketingcost.service.collaboration.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class U9MonthlySnapshotIdentityTest {

  @Test
  void sameOrganizationMonthAndProductHasStableIdentity() {
    U9MonthlySnapshotIdentity first = U9MonthlySnapshotIdentity.from(
        context("commercial", "210", "commercial", "2026-08", " p-1 "));
    U9MonthlySnapshotIdentity second = U9MonthlySnapshotIdentity.from(
        context("COMMERCIAL", "210", "COMMERCIAL", "2026-08", "P-1"));

    assertThat(first.identityKey()).hasSize(64).isEqualTo(second.identityKey());
    assertThat(first.bomPurpose()).isEqualTo("主制造");
  }

  @Test
  void nextMonthOrDifferentOrganizationHasDifferentIdentity() {
    String current = U9MonthlySnapshotIdentity.from(
        context("COMMERCIAL", "210", "COMMERCIAL", "2026-08", "P-1")).identityKey();

    assertThat(U9MonthlySnapshotIdentity.from(
        context("COMMERCIAL", "210", "COMMERCIAL", "2026-09", "P-1")).identityKey())
        .isNotEqualTo(current);
    assertThat(U9MonthlySnapshotIdentity.from(
        context("HOUSEHOLD", "220", "PLATE", "2026-08", "P-1")).identityKey())
        .isNotEqualTo(current);
  }

  @Test
  void invalidMonthIsRejectedBeforeAnyQuery() {
    assertThatThrownBy(() -> U9MonthlySnapshotIdentity.from(
        context("COMMERCIAL", "210", "COMMERCIAL", "2026-13", "P-1")))
        .isInstanceOf(RuntimeException.class);
  }

  private QuoteCollaborationScanContext context(
      String businessUnit, String priceOrg, String materialOrg, String month, String product) {
    return new QuoteCollaborationScanContext(
        1L, 2L, "OA-1", month, businessUnit, product, "产品", "规格", "型号",
        priceOrg, materialOrg, LocalDate.of(2026, 8, 25),
        LocalDateTime.of(2026, 8, 25, 10, 0));
  }
}
