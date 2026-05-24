package com.sanhua.marketingcost.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class LinkedPriceCalcSceneTest {

  @Test
  void quote_should_use_oa_locked_factor_source() {
    assertEquals("QUOTE", LinkedPriceCalcScene.QUOTE.getCode());
    assertEquals("正常报价", LinkedPriceCalcScene.QUOTE.getLabel());
    assertEquals(LinkedPriceFactorSource.OA_LOCKED, LinkedPriceCalcScene.QUOTE.getDefaultFactorSource());
    assertTrue(LinkedPriceCalcScene.QUOTE.requiresOaNo());
    assertFalse(LinkedPriceCalcScene.QUOTE.requiresAdjustBatchId());
  }

  @Test
  void monthlyAdjust_should_require_adjust_batch() {
    assertEquals("MONTHLY_ADJUST", LinkedPriceCalcScene.MONTHLY_ADJUST.getCode());
    assertEquals("月度调价", LinkedPriceCalcScene.MONTHLY_ADJUST.getLabel());
    assertEquals(
        LinkedPriceFactorSource.ADJUST_BATCH,
        LinkedPriceCalcScene.MONTHLY_ADJUST.getDefaultFactorSource());
    assertFalse(LinkedPriceCalcScene.MONTHLY_ADJUST.requiresOaNo());
    assertTrue(LinkedPriceCalcScene.MONTHLY_ADJUST.requiresAdjustBatchId());
  }

  @Test
  void fromCode_should_trim_and_ignore_case() {
    assertEquals(Optional.of(LinkedPriceCalcScene.QUOTE), LinkedPriceCalcScene.fromCode(" quote "));
    assertEquals(
        Optional.of(LinkedPriceCalcScene.MONTHLY_ADJUST),
        LinkedPriceCalcScene.fromCode("monthly_adjust"));
  }

  @Test
  void fromCode_should_return_empty_for_blank_or_unknown() {
    assertEquals(Optional.empty(), LinkedPriceCalcScene.fromCode(null));
    assertEquals(Optional.empty(), LinkedPriceCalcScene.fromCode(" "));
    assertEquals(Optional.empty(), LinkedPriceCalcScene.fromCode("DAILY"));
  }
}
