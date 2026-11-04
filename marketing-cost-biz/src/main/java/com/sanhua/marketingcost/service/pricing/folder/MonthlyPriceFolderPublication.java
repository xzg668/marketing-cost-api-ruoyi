package com.sanhua.marketingcost.service.pricing.folder;

import static com.sanhua.marketingcost.service.pricing.folder.MonthlyPriceFolderModel.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.pricing.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 一次原子发布形成包含全部并行规则的新修订；旧修订和来源永不改写。 */
@Component
public class MonthlyPriceFolderPublication {
  private final PriceLinkedItemMapper linked;
  private final PriceRangeFactorRuleMapper ranges;
  private final PriceRangeItemMapper bands;
  private final FactorUploadBatchMapper batches;
  public MonthlyPriceFolderPublication(PriceLinkedItemMapper linked, PriceRangeFactorRuleMapper ranges,
      PriceRangeItemMapper bands, FactorUploadBatchMapper batches) {
    this.linked = linked; this.ranges = ranges; this.bands = bands; this.batches = batches;
  }
  public void publish(List<Group> groups, Set<Long> ownedBatches) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("正式生效必须在完成事务内执行");
    Set<Long> publishedBatches = new HashSet<>();
    var planner = new MonthlyPriceFolderPlan();
    Map<String, Map<String, Integer>> linkedCounters = new HashMap<>(), rangeCounters = new HashMap<>();
    for (var group : groups) {
      if (!group.status().equals("PREPARED")) continue;
      Integer linkedGroup = null, rangeGroup = null;
      for (var stored : planner.representatives(group.candidates())) {
        if (stored.fileId() != null) throw new IllegalArgumentException("还有未保存的并行规则");
        boolean staged = "STAGED".equals(stored.sourceState());
        if (stored.kind().equals("LINKED")) {
          var original = linked.selectById(stored.sourceId());
          requireSource(original == null ? null : original.getRuleStatus(), original == null ? null : original.getSourceUploadBatchId(), staged, ownedBatches);
          var counters = linkedCounters.computeIfAbsent(original.getBusinessUnitType() + ":" + original.getPricingMonth(),
              ignored -> OriginalRuleRevisions.linked(linked, original.getBusinessUnitType(), original.getPricingMonth()));
          int revision = counters.merge(original.getRuleIdentityKey(), 1, Integer::sum);
          if (linkedGroup == null) linkedGroup = revision;
          var row = new PriceLinkedItem(); BeanUtils.copyProperties(original, row);
          if (!staged) { row.setId(null); row.setCreatedAt(LocalDateTime.now()); }
          row.setRuleRevision(revision); row.setRuleStatus("PUBLISHED"); row.setUpdatedAt(LocalDateTime.now());
          row.setSourceInputSnapshotJson(OriginalRuleGroups.withRevision(row.getSourceInputSnapshotJson(), linkedGroup));
          if (staged) linked.updateById(row); else linked.insert(row);
          if (staged) publishedBatches.add(row.getSourceUploadBatchId());
        } else {
          var original = ranges.selectById(stored.sourceId());
          requireSource(original == null ? null : original.getRuleStatus(), original == null ? null : original.getSourceUploadBatchId(), staged, ownedBatches);
          var counters = rangeCounters.computeIfAbsent(original.getBusinessUnitType() + ":" + original.getPricingMonth(),
              ignored -> OriginalRuleRevisions.ranges(ranges, original.getBusinessUnitType(), original.getPricingMonth()));
          int revision = counters.merge(original.getRuleIdentityKey(), 1, Integer::sum);
          if (rangeGroup == null) rangeGroup = revision;
          var row = new PriceRangeFactorRule(); BeanUtils.copyProperties(original, row);
          if (!staged) { row.setId(null); row.setCreatedAt(LocalDateTime.now()); }
          row.setVersionNo(revision); row.setRuleStatus("PUBLISHED"); row.setCurrentFlag(1); row.setUpdatedAt(LocalDateTime.now());
          row.setSourceInputSnapshotJson(OriginalRuleGroups.withRevision(row.getSourceInputSnapshotJson(), rangeGroup));
          if (staged) {
            ranges.updateById(row);
            bands.update(null, Wrappers.lambdaUpdate(PriceRangeItem.class).eq(PriceRangeItem::getFactorRuleId, row.getId()).set(PriceRangeItem::getCurrentFlag, 1));
            publishedBatches.add(row.getSourceUploadBatchId());
          } else {
            ranges.insert(row);
            for (var oldBand : bands.selectList(Wrappers.lambdaQuery(PriceRangeItem.class).eq(PriceRangeItem::getFactorRuleId, original.getId()))) {
              var band = new PriceRangeItem(); BeanUtils.copyProperties(oldBand, band); band.setId(null);
              band.setFactorRuleId(row.getId()); band.setCreatedAt(LocalDateTime.now()); band.setUpdatedAt(LocalDateTime.now()); bands.insert(band);
            }
          }
        }
      }
    }
    if (ownedBatches.isEmpty()) return;
    linked.update(null, Wrappers.lambdaUpdate(PriceLinkedItem.class).in(PriceLinkedItem::getSourceUploadBatchId, ownedBatches)
        .eq(PriceLinkedItem::getRuleStatus, "STAGED").set(PriceLinkedItem::getRuleStatus, "NOT_SELECTED"));
    ranges.update(null, Wrappers.lambdaUpdate(PriceRangeFactorRule.class).in(PriceRangeFactorRule::getSourceUploadBatchId, ownedBatches)
        .eq(PriceRangeFactorRule::getRuleStatus, "STAGED").set(PriceRangeFactorRule::getRuleStatus, "NOT_SELECTED"));
    for (Long id : ownedBatches) batches.update(null, Wrappers.lambdaUpdate(FactorUploadBatch.class)
        .eq(FactorUploadBatch::getId, id).eq(FactorUploadBatch::getStatus, "STAGED")
        .set(FactorUploadBatch::getStatus, publishedBatches.contains(id) ? "PUBLISHED" : "NOT_SELECTED")
        .set(FactorUploadBatch::getPublishedAt, publishedBatches.contains(id) ? LocalDateTime.now() : null));
  }
  private void requireSource(String status, Long batch, boolean staged, Set<Long> owned) {
    if (staged ? !"STAGED".equals(status) || !owned.contains(batch) : !"PUBLISHED".equals(status))
      throw new IllegalArgumentException("规则来源已变化，请重新处理文件");
  }
}
