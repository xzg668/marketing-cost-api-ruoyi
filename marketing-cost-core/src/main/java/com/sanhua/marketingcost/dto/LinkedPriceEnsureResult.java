package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 联动价按需确保结果，用于告诉调用方本次新增、更新、跳过和失败了哪些料号。 */
@Getter
@Setter
public class LinkedPriceEnsureResult {
  /** 供本次准备直接消费，避免再次查询“最新结果”时读到其他并发输入；不扩大外部汇总响应。 */
  @com.fasterxml.jackson.annotation.JsonIgnore
  private final java.util.Map<String, com.sanhua.marketingcost.entity.PriceLinkedCalcItem> items =
      new java.util.LinkedHashMap<>();

  private int requestedCount;
  private int createdCount;
  private int updatedCount;
  private int skippedCount;
  private int failedCount;
  private List<FailedItem> failedItems = new ArrayList<>();

  public void addFailedItem(String itemCode, String reason) {
    addFailedItem(itemCode, null, reason);
  }

  public void addFailedItem(String itemCode, String reasonCode, String reason) {
    failedItems.add(new FailedItem(itemCode, reasonCode, reason));
    failedCount = failedItems.size();
  }

  @Getter
  @Setter
  public static class FailedItem {
    private String itemCode;
    private String reasonCode;
    private String reason;

    public FailedItem() {}

    public FailedItem(String itemCode, String reason) {
      this(itemCode, null, reason);
    }

    public FailedItem(String itemCode, String reasonCode, String reason) {
      this.itemCode = itemCode;
      this.reasonCode = reasonCode;
      this.reason = reason;
    }
  }
}
