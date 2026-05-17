package com.sanhua.marketingcost.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class QuoteIngestEnumTest {

  @Test
  void sourceTypesMatchIngestSchema() {
    assertThat(Arrays.stream(QuoteSourceType.values()).map(QuoteSourceType::getCode))
        .containsExactly("OA", "MOCK_OA", "MANUAL", "EXCEL", "TECH", "LEGACY");
  }

  @Test
  void quoteScenariosMatchTypeRules() {
    assertThat(Arrays.stream(QuoteScenario.values()).map(QuoteScenario::getCode))
        .containsExactly(
            "DIRECT_SALE",
            "STANDARD_BATCH",
            "NEW_PRODUCT",
            "MASS_PRODUCT",
            "DERIVED_PRODUCT",
            "TECH_SUPPLEMENT",
            "UNKNOWN");
  }

  @Test
  void statusesMatchDatabaseColumns() {
    assertThat(Arrays.stream(QuoteIngestStatus.values()).map(QuoteIngestStatus::getCode))
        .containsExactly(
            "RECEIVED", "VALIDATING", "REJECTED", "CLASSIFY_PENDING", "IMPORTED", "FAILED");
    assertThat(
            Arrays.stream(QuoteClassificationStatus.values())
                .map(QuoteClassificationStatus::getCode))
        .containsExactly("CONFIRMED", "PENDING", "REJECTED");
    assertThat(Arrays.stream(QuoteBomStatusCode.values()).map(QuoteBomStatusCode::getCode))
        .containsExactly(
            "NOT_CHECKED",
            "SYNCED",
            "NO_BOM",
            "ENTRY_PENDING",
            "ENTRY_IN_PROGRESS",
            "MANUAL_ENTERED",
            "EXPIRED",
            "CHECK_FAILED");
    assertThat(Arrays.stream(QuoteWritebackStatus.values()).map(QuoteWritebackStatus::getCode))
        .containsExactly("PENDING", "SUCCESS", "FAILED", "SKIPPED");
  }

  @Test
  void feeCategoriesMatchExtraFeeTableComment() {
    assertThat(Arrays.stream(QuoteExtraFeeCategory.values()).map(QuoteExtraFeeCategory::getCode))
        .containsExactly(
            "TOOLING", "MOLD", "CERTIFICATION", "EQUIPMENT", "CUTTER", "LABOR", "SCRAP",
            "OTHER");
  }
}
