package com.sanhua.marketingcost.service.effectivebom;

import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import java.time.LocalDateTime;
import java.util.Optional;

/** 月度冻结事务需要的带锁查询和条件更新。 */
public interface QuoteBomMonthlyFreezeRepository {

  Optional<QuoteBomMonthlySnapshot> findActiveSuccessForUpdate(
      QuoteBomMonthlyFreezeKey key);

  Optional<QuoteBomStatus> findStatusForUpdate(Long oaFormItemId);

  int freezeDraft(
      Long snapshotId,
      String buildBatchId,
      String variantHash,
      Long frozenBy,
      LocalDateTime frozenAt);

  int stageDraft(
      Long snapshotId,
      String buildBatchId,
      String variantHash,
      LocalDateTime updatedAt);

  int bindStatus(
      Long statusId,
      Long oaFormItemId,
      Long snapshotId,
      String buildBatchId,
      LocalDateTime updatedAt);

  default boolean hasActiveConfirmationForBuild(String buildBatchId) {
    throw new UnsupportedOperationException();
  }

  default boolean hasActiveConfirmation(
      String oaNo,
      Long oaFormItemId,
      String topProductCode,
      String periodMonth) {
    throw new UnsupportedOperationException();
  }

  default int releaseProvisional(
      Long snapshotId,
      String expectedBuildBatchId,
      LocalDateTime updatedAt) {
    throw new UnsupportedOperationException();
  }

  default int clearStatusBindings(
      String buildBatchId,
      LocalDateTime updatedAt) {
    throw new UnsupportedOperationException();
  }
}
