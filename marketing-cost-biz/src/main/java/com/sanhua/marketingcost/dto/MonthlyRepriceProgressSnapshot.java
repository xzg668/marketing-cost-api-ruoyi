package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 月度调价批次进度快照。 */
@Getter
@Setter
public class MonthlyRepriceProgressSnapshot {

  private Long id;
  private String repriceNo;
  private String pricingMonth;
  private LocalDateTime priceAsOfTime;
  private String bomSourcePolicy;
  private String businessUnitType;
  private String status;
  private int totalCount;
  private int successCount;
  private int failedCount;
  private int skippedCount;
  private int pendingCount;
  private int runningCount;
  private int resultCount;
  private int progressPercent;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  private LocalDateTime confirmedAt;

  public static MonthlyRepriceProgressSnapshot of(
      MonthlyRepriceBatch batch,
      int pendingCount,
      int runningCount,
      int resultCount) {
    MonthlyRepriceProgressSnapshot snapshot = new MonthlyRepriceProgressSnapshot();
    if (batch == null) {
      return snapshot;
    }
    snapshot.setId(batch.getId());
    snapshot.setRepriceNo(batch.getRepriceNo());
    snapshot.setPricingMonth(batch.getPricingMonth());
    snapshot.setPriceAsOfTime(batch.getPriceAsOfTime());
    snapshot.setBomSourcePolicy(batch.getBomSourcePolicy());
    snapshot.setBusinessUnitType(batch.getBusinessUnitType());
    snapshot.setStatus(batch.getStatus());
    snapshot.setTotalCount(safe(batch.getTotalCount()));
    snapshot.setSuccessCount(safe(batch.getSuccessCount()));
    snapshot.setFailedCount(safe(batch.getFailedCount()));
    snapshot.setSkippedCount(safe(batch.getSkippedCount()));
    snapshot.setPendingCount(pendingCount);
    snapshot.setRunningCount(runningCount);
    snapshot.setResultCount(resultCount);
    snapshot.setProgressPercent(progressPercent(snapshot));
    snapshot.setStartedAt(batch.getStartedAt());
    snapshot.setFinishedAt(batch.getFinishedAt());
    snapshot.setConfirmedAt(batch.getConfirmedAt());
    return snapshot;
  }

  private static int progressPercent(MonthlyRepriceProgressSnapshot snapshot) {
    if (snapshot.getTotalCount() <= 0) {
      return 0;
    }
    int completed =
        snapshot.getSuccessCount() + snapshot.getFailedCount() + snapshot.getSkippedCount();
    return Math.min(100, Math.max(0, completed * 100 / snapshot.getTotalCount()));
  }

  private static int safe(Integer value) {
    return value == null ? 0 : value;
  }
}
