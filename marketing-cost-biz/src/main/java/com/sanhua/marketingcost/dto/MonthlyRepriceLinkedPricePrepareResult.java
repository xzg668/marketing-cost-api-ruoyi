package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult.FailedItem;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 月度调价联动价准备结果。 */
@Getter
@Setter
public class MonthlyRepriceLinkedPricePrepareResult {

  private String repriceNo;
  private String pricingMonth;
  private Long adjustBatchId;
  private int itemCount;
  private int createdCount;
  private int updatedCount;
  private int skippedCount;
  private int failedCount;
  private String batchStatus;
  private List<FailedItem> failedItems = new ArrayList<>();

  public static MonthlyRepriceLinkedPricePrepareResult of(
      String repriceNo,
      String pricingMonth,
      Long adjustBatchId,
      LinkedPriceEnsureResult ensureResult,
      String batchStatus) {
    MonthlyRepriceLinkedPricePrepareResult result =
        new MonthlyRepriceLinkedPricePrepareResult();
    result.setRepriceNo(repriceNo);
    result.setPricingMonth(pricingMonth);
    result.setAdjustBatchId(adjustBatchId);
    result.setBatchStatus(batchStatus);
    if (ensureResult != null) {
      result.setItemCount(ensureResult.getRequestedCount());
      result.setCreatedCount(ensureResult.getCreatedCount());
      result.setUpdatedCount(ensureResult.getUpdatedCount());
      result.setSkippedCount(ensureResult.getSkippedCount());
      result.setFailedCount(ensureResult.getFailedCount());
      result.setFailedItems(new ArrayList<>(ensureResult.getFailedItems()));
    }
    return result;
  }
}
