package com.sanhua.marketingcost.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanhua.marketingcost.enums.LinkedPriceCalcScene;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
  void monthlyAdjustFactory_should_require_adjust_batch_and_pass_validation() {
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
  void validate_monthlyAdjust_should_require_adjust_batch_id() {
    LinkedPriceEnsureRequest request =
        LinkedPriceEnsureRequest.monthlyAdjust(
            null,
            "COMMERCIAL",
            "2026-05",
            new LinkedHashSet<>(List.of("MAT-1")));

    assertEquals(List.of("MONTHLY_ADJUST 场景 adjustBatchId 不能为空"), request.validate());
  }
}
