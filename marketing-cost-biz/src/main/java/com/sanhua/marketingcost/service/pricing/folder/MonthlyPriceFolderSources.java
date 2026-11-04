package com.sanhua.marketingcost.service.pricing.folder;

import com.sanhua.marketingcost.service.pricing.OriginalRuleSignature;
import com.sanhua.marketingcost.service.pricing.OriginalRuleGroups;

import static com.sanhua.marketingcost.service.pricing.folder.MonthlyPriceFolderModel.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.pricing.MaterialPriceRuleKey;
import java.util.*;
import org.springframework.stereotype.Component;

/** 读取原稿版本，既不采用共享月价，也不改动旧规则和成本。 */
@Component
public class MonthlyPriceFolderSources {
  private final PriceLinkedItemMapper linked;
  private final PriceRangeFactorRuleMapper ranges;
  private final OriginalLinkedRuleSnapshot linkedSnapshots;
  private final OriginalRangeRuleSnapshot rangeSnapshots;
  private final OriginalRuleSignature signatures;

  public MonthlyPriceFolderSources(PriceLinkedItemMapper linked, PriceRangeFactorRuleMapper ranges,
      OriginalLinkedRuleSnapshot linkedSnapshots, OriginalRangeRuleSnapshot rangeSnapshots,
      OriginalRuleSignature signatures) {
    this.linked = linked; this.ranges = ranges; this.linkedSnapshots = linkedSnapshots;
    this.rangeSnapshots = rangeSnapshots; this.signatures = signatures;
  }

  public record Sources(Map<String, PriceLinkedItem> linked, Map<String, PriceRangeFactorRule> ranges) {}

  public Sources read(String unit, String month, boolean includeEarlier) {
    return read(unit, month, includeEarlier, "PUBLISHED", null);
  }

  public Sources staged(String unit, String month, Set<Long> batches) {
    if (batches.isEmpty()) return new Sources(Map.of(), Map.of());
    return read(unit, month, false, "STAGED", batches);
  }

  private Sources read(String unit, String month, boolean includeEarlier, String status, Set<Long> batches) {
    var lq = Wrappers.lambdaQuery(PriceLinkedItem.class)
        .eq(PriceLinkedItem::getBusinessUnitType, unit).eq(PriceLinkedItem::getSourceKind, "PUBLIC")
        .eq(PriceLinkedItem::getDeleted, 0).eq(PriceLinkedItem::getRuleStatus, status)
        .eq(PriceLinkedItem::getExpressionVersion, OriginalLinkedRuleSnapshot.VERSION)
        .isNotNull(PriceLinkedItem::getRuleIdentityKey);
    if (includeEarlier) lq.le(PriceLinkedItem::getPricingMonth, month);
    else lq.eq(PriceLinkedItem::getPricingMonth, month);
    if (batches != null) lq.in(PriceLinkedItem::getSourceUploadBatchId, batches);
    var linkedRows = new ArrayList<>(linked.selectList(lq));
    var rq = Wrappers.lambdaQuery(PriceRangeFactorRule.class)
        .eq(PriceRangeFactorRule::getBusinessUnitType, unit).eq(PriceRangeFactorRule::getRuleStatus, status)
        .isNotNull(PriceRangeFactorRule::getSourceUploadBatchId).isNotNull(PriceRangeFactorRule::getRuleIdentityKey);
    if (includeEarlier) rq.le(PriceRangeFactorRule::getPricingMonth, month);
    else rq.eq(PriceRangeFactorRule::getPricingMonth, month);
    if (batches != null) rq.in(PriceRangeFactorRule::getSourceUploadBatchId, batches);
    var rangeRows = new ArrayList<>(ranges.selectList(rq));
    if (includeEarlier) {
      Map<String, String> months = new HashMap<>();
      linkedRows.forEach(row -> months.merge(row.getOrgCode(), row.getPricingMonth(), (a, b) -> a.compareTo(b) >= 0 ? a : b));
      rangeRows.forEach(row -> months.merge(row.getOrgCode(), row.getPricingMonth(), (a, b) -> a.compareTo(b) >= 0 ? a : b));
      linkedRows.removeIf(row -> !row.getPricingMonth().equals(months.get(row.getOrgCode())));
      rangeRows.removeIf(row -> !row.getPricingMonth().equals(months.get(row.getOrgCode())));
    }
    var currentLinked = status.equals("STAGED") ? linkedRows
        : OriginalRuleGroups.current(linkedRows, PriceLinkedItem::getRuleIdentityKey, OriginalRuleGroups::revision);
    var currentRange = status.equals("STAGED") ? rangeRows : OriginalRuleGroups.ranges(rangeRows);
    Map<String, PriceLinkedItem> ls = new LinkedHashMap<>();
    Map<String, PriceRangeFactorRule> rs = new LinkedHashMap<>();
    currentLinked.forEach(row -> ls.put(row.getId().toString(), row));
    currentRange.forEach(row -> rs.put(row.getId().toString(), row));
    return new Sources(ls, rs);
  }

  public List<Candidate> candidates(Sources sources, Set<String> identities) {
    List<Candidate> result = new ArrayList<>();
    for (var item : sources.linked().values()) {
      if (!identities.contains(item.getRuleIdentityKey())) continue;
      var rule = linkedSnapshots.read(item);
      result.add(new Candidate("stored-linked-" + item.getId(), null, stateLabel(item.getRuleStatus()) + item.getSourceName(),
          rule.sheet(), rule.row(), "LINKED", rule.identity(), signatures.linked(rule),
          signatures.describe(rule), item.getId(), item.getRuleStatus()));
    }
    for (var item : sources.ranges().values()) {
      if (!identities.contains(item.getRuleIdentityKey())) continue;
      var rule = rangeSnapshots.read(item).rule();
      result.add(new Candidate("stored-range-" + item.getId(), null, stateLabel(item.getRuleStatus()) + item.getSourceFile(),
          rule.sheet(), rule.row(), "RANGE", rule.identity(), signatures.range(rule),
          signatures.describe(rule), item.getId(), item.getRuleStatus()));
    }
    return result;
  }

  private String stateLabel(String state) { return "STAGED".equals(state) ? "本批已保存待生效：" : "当月已生效："; }
}
