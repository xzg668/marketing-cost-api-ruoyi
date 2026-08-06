package com.sanhua.marketingcost.service.bomalternative;

import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** 报价 BOM 替代选择版本仓储。 */
public interface QuoteBomAlternativeSelectionRepository {

  QuoteBomAlternativeSelection findCurrent(
      QuoteBomAlternativeSelectionScope scope, String groupKey);

  QuoteBomAlternativeSelection findCurrentForUpdate(
      QuoteBomAlternativeSelectionScope scope, String groupKey);

  List<QuoteBomAlternativeSelection> findCurrentsForUpdate(
      QuoteBomAlternativeSelectionScope scope);

  QuoteBomAlternativeSelection findLatest(
      QuoteBomAlternativeSelectionScope scope, String groupKey);

  List<QuoteBomAlternativeSelection> findHistory(
      QuoteBomAlternativeSelectionScope scope, String groupKey);

  /** 按不可变选择ID读取冻结最终树引用的原始选择证据。 */
  List<QuoteBomAlternativeSelection> findByIds(Collection<Long> ids);

  void insert(QuoteBomAlternativeSelection selection);

  boolean transitionCurrent(
      Long id,
      Integer expectedVersion,
      String targetStatus,
      LocalDateTime updatedAt);

  boolean refreshSource(
      Long id,
      Integer expectedVersion,
      String sourceImportBatchId,
      String sourceBuildBatchId,
      LocalDateTime updatedAt);
}
