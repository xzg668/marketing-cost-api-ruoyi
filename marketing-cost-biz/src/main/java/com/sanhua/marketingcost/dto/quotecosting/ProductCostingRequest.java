package com.sanhua.marketingcost.dto.quotecosting;

/** 整单和单品共用的产品级核算命令。 */
public record ProductCostingRequest(
    String oaNo,
    Long oaFormItemId,
    String periodMonth,
    String initiatedBy,
    boolean force,
    boolean includeSavedCostRun) {
  public ProductCostingRequest(String oaNo, Long oaFormItemId, String periodMonth, String initiatedBy, boolean force) {
    this(oaNo, oaFormItemId, periodMonth, initiatedBy, force, false);
  }
}
