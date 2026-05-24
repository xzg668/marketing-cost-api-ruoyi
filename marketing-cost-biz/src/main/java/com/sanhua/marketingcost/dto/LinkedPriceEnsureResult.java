package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 联动价按需确保结果，用于告诉调用方本次新增、更新、跳过和失败了哪些料号。 */
@Getter
@Setter
public class LinkedPriceEnsureResult {
  private int requestedCount;
  private int createdCount;
  private int updatedCount;
  private int skippedCount;
  private int failedCount;
  private List<FailedItem> failedItems = new ArrayList<>();

  public void addFailedItem(String itemCode, String reason) {
    failedItems.add(new FailedItem(itemCode, reason));
    failedCount = failedItems.size();
  }

  @Getter
  @Setter
  public static class FailedItem {
    private String itemCode;
    private String reason;

    public FailedItem() {
    }

    public FailedItem(String itemCode, String reason) {
      this.itemCode = itemCode;
      this.reason = reason;
    }
  }
}
