package com.sanhua.marketingcost.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanhua.marketingcost.enums.LinkedPriceCalcScene;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LinkedPriceEnsureRequestTest {

  @Test
  void quoteFactory_should_normalize_item_codes_and_pass_validation() {
    LinkedPriceEnsureRequest request =
        LinkedPriceEnsureRequest.quote(
            "OA-001",
            "COMMERCIAL",
            "2026-05",
            new LinkedHashSet<>(List.of(" 301050066 ", "", "301990317", "301050066")));

    assertEquals(LinkedPriceCalcScene.QUOTE, request.getCalcScene());
    assertEquals(Set.of("301050066", "301990317"), request.normalizedItemCodes());
    assertTrue(request.validate().isEmpty());
  }

  @Test
  void monthlyAdjustFactory_should_allow_optional_adjust_batch_and_pass_validation() {
    LinkedPriceEnsureRequest request =
        LinkedPriceEnsureRequest.monthlyAdjust(
            18L,
            "HOUSEHOLD",
            "2026-05",
            new LinkedHashSet<>(List.of("MAT-1", " MAT-2 ")));

    assertEquals(LinkedPriceCalcScene.MONTHLY_ADJUST, request.getCalcScene());
    assertEquals(18L, request.getAdjustBatchId());
    assertEquals(Set.of("MAT-1", "MAT-2"), request.normalizedItemCodes());
    assertTrue(request.validate().isEmpty());
  }

  @Test
  void validate_should_report_common_missing_fields() {
    LinkedPriceEnsureRequest request = new LinkedPriceEnsureRequest();

    List<String> errors = request.validate();

    assertTrue(errors.contains("calcScene 不能为空"));
    assertTrue(errors.contains("businessUnitType 不能为空"));
    assertTrue(errors.contains("pricingMonth 不能为空"));
    assertTrue(errors.contains("itemCodes 不能为空"));
  }

  @Test
  void validate_quote_should_require_oa_no() {
    LinkedPriceEnsureRequest request =
        LinkedPriceEnsureRequest.quote(
            " ",
            "COMMERCIAL",
            "2026-05",
            new LinkedHashSet<>(List.of("MAT-1")));

    assertEquals(List.of("QUOTE 场景 oaNo 不能为空"), request.validate());
  }

  @Test
  void validate_monthlyAdjust_should_allow_missing_adjust_batch_id() {
    LinkedPriceEnsureRequest request =
        LinkedPriceEnsureRequest.monthlyAdjust(
            null,
            "COMMERCIAL",
            "2026-05",
            new LinkedHashSet<>(List.of("MAT-1")));

    assertTrue(request.validate().isEmpty());
  }

  @Test
  void financeQuote_should_allow_only_positive_cu_override() {
    LinkedPriceEnsureRequest request = LinkedPriceEnsureRequest.quote(
        "OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-CU"));
    request.setPriceScenarioType(QuotePriceScenarioType.FINANCE_QUOTE_BASE);
    request.setVariableOverrides(Map.of("Cu", new BigDecimal("90.000000")));

    assertTrue(request.validate().isEmpty());

    request.setVariableOverrides(Map.of(
        "Cu", new BigDecimal("90.000000"), "Zn", new BigDecimal("20")));
    assertEquals(List.of("FINANCE_QUOTE_BASE 场景只允许覆盖 Cu"), request.validate());
  }
}
