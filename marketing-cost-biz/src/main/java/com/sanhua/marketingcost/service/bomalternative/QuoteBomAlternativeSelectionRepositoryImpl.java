package com.sanhua.marketingcost.service.bomalternative;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import com.sanhua.marketingcost.mapper.QuoteBomAlternativeSelectionMapper;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Repository;

/** MyBatis-Plus 报价 BOM 替代选择仓储实现。 */
@Repository
public class QuoteBomAlternativeSelectionRepositoryImpl
    implements QuoteBomAlternativeSelectionRepository {

  private final QuoteBomAlternativeSelectionMapper mapper;

  public QuoteBomAlternativeSelectionRepositoryImpl(
      QuoteBomAlternativeSelectionMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public QuoteBomAlternativeSelection findCurrent(
      QuoteBomAlternativeSelectionScope scope, String groupKey) {
    return mapper.selectOne(
        currentQuery(scope, groupKey).last("LIMIT 1"));
  }

  @Override
  public QuoteBomAlternativeSelection findCurrentForUpdate(
      QuoteBomAlternativeSelectionScope scope, String groupKey) {
    return mapper.selectOne(
        currentQuery(scope, groupKey).last("LIMIT 1 FOR UPDATE"));
  }

  @Override
  public List<QuoteBomAlternativeSelection> findCurrentsForUpdate(
      QuoteBomAlternativeSelectionScope scope) {
    return mapper.selectList(
        scopeQuery(scope)
            .eq(
                QuoteBomAlternativeSelection::getSelectionStatus,
                QuoteBomAlternativeSelection.STATUS_ACTIVE)
            .eq(
                QuoteBomAlternativeSelection::getCurrentSlot,
                QuoteBomAlternativeSelection.CURRENT_SLOT)
            .orderByAsc(
                QuoteBomAlternativeSelection::getAlternativeGroupKey)
            .last("FOR UPDATE"));
  }

  @Override
  public QuoteBomAlternativeSelection findLatest(
      QuoteBomAlternativeSelectionScope scope, String groupKey) {
    List<QuoteBomAlternativeSelection> rows =
        mapper.selectList(
            scopeQuery(scope)
                .eq(
                    QuoteBomAlternativeSelection::getAlternativeGroupKey,
                    groupKey)
                .orderByDesc(
                    QuoteBomAlternativeSelection::getSelectionVersion)
                .last("LIMIT 1"));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  @Override
  public List<QuoteBomAlternativeSelection> findHistory(
      QuoteBomAlternativeSelectionScope scope, String groupKey) {
    return mapper.selectList(
        scopeQuery(scope)
            .eq(
                QuoteBomAlternativeSelection::getAlternativeGroupKey,
                groupKey)
            .orderByAsc(
                QuoteBomAlternativeSelection::getSelectionVersion));
  }

  @Override
  public List<QuoteBomAlternativeSelection> findByIds(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return mapper.selectBatchIds(ids);
  }

  @Override
  public void insert(QuoteBomAlternativeSelection selection) {
    mapper.insert(selection);
  }

  @Override
  public boolean transitionCurrent(
      Long id,
      Integer expectedVersion,
      String targetStatus,
      LocalDateTime updatedAt) {
    int updated =
        mapper.update(
            null,
            Wrappers.lambdaUpdate(QuoteBomAlternativeSelection.class)
                .set(
                    QuoteBomAlternativeSelection::getSelectionStatus,
                    targetStatus)
                .set(
                    QuoteBomAlternativeSelection::getCurrentSlot,
                    null)
                .set(
                    QuoteBomAlternativeSelection::getUpdatedAt,
                    updatedAt)
                .eq(QuoteBomAlternativeSelection::getId, id)
                .eq(
                    QuoteBomAlternativeSelection::getSelectionVersion,
                    expectedVersion)
                .eq(
                    QuoteBomAlternativeSelection::getSelectionStatus,
                    QuoteBomAlternativeSelection.STATUS_ACTIVE)
                .eq(
                    QuoteBomAlternativeSelection::getCurrentSlot,
                    QuoteBomAlternativeSelection.CURRENT_SLOT));
    return updated == 1;
  }

  @Override
  public boolean refreshSource(
      Long id,
      Integer expectedVersion,
      String sourceImportBatchId,
      String sourceBuildBatchId,
      LocalDateTime updatedAt) {
    int updated =
        mapper.update(
            null,
            Wrappers.lambdaUpdate(QuoteBomAlternativeSelection.class)
                .set(
                    QuoteBomAlternativeSelection::getSourceImportBatchId,
                    sourceImportBatchId)
                .set(
                    QuoteBomAlternativeSelection::getSourceBuildBatchId,
                    sourceBuildBatchId)
                .set(
                    QuoteBomAlternativeSelection::getUpdatedAt,
                    updatedAt)
                .eq(QuoteBomAlternativeSelection::getId, id)
                .eq(
                    QuoteBomAlternativeSelection::getSelectionVersion,
                    expectedVersion)
                .eq(
                    QuoteBomAlternativeSelection::getSelectionStatus,
                    QuoteBomAlternativeSelection.STATUS_ACTIVE)
                .eq(
                    QuoteBomAlternativeSelection::getCurrentSlot,
                    QuoteBomAlternativeSelection.CURRENT_SLOT));
    return updated == 1;
  }

  private LambdaQueryWrapper<QuoteBomAlternativeSelection> currentQuery(
      QuoteBomAlternativeSelectionScope scope, String groupKey) {
    return scopeQuery(scope)
        .eq(
            QuoteBomAlternativeSelection::getAlternativeGroupKey,
            groupKey)
        .eq(
            QuoteBomAlternativeSelection::getSelectionStatus,
            QuoteBomAlternativeSelection.STATUS_ACTIVE)
        .eq(
            QuoteBomAlternativeSelection::getCurrentSlot,
            QuoteBomAlternativeSelection.CURRENT_SLOT);
  }

  private LambdaQueryWrapper<QuoteBomAlternativeSelection> scopeQuery(
      QuoteBomAlternativeSelectionScope scope) {
    return Wrappers.lambdaQuery(QuoteBomAlternativeSelection.class)
        .eq(QuoteBomAlternativeSelection::getOaNo, scope.oaNo())
        .eq(
            QuoteBomAlternativeSelection::getOaFormItemId,
            scope.oaFormItemId())
        .eq(
            QuoteBomAlternativeSelection::getTopProductCode,
            scope.topProductCode())
        .eq(
            QuoteBomAlternativeSelection::getPeriodMonth,
            scope.periodMonth())
        .eq(
            QuoteBomAlternativeSelection::getPriceOrgCode,
            scope.priceOrgCode())
        .eq(
            QuoteBomAlternativeSelection::getBusinessUnitType,
            scope.businessUnitType());
  }
}
