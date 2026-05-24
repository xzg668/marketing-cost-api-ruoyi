package com.sanhua.marketingcost.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LinkedPriceEnsureResultTest {

  @Test
  void addFailedItem_should_append_detail_and_sync_count() {
    LinkedPriceEnsureResult result = new LinkedPriceEnsureResult();

    result.addFailedItem("MAT-1", "公式缺变量");
    result.addFailedItem("MAT-2", "缺影响因素价格");

    assertEquals(2, result.getFailedCount());
    assertEquals(2, result.getFailedItems().size());
    assertEquals("MAT-1", result.getFailedItems().get(0).getItemCode());
    assertEquals("公式缺变量", result.getFailedItems().get(0).getReason());
  }
}
