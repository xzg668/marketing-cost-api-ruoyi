package com.sanhua.marketingcost.service.pricing.folder;

import static com.sanhua.marketingcost.service.pricing.folder.MonthlyPriceFolderModel.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.pricing.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 追加目标月缺少的原规则，保留旧来源和原件；金额仍由已有财务有效价服务生成。 */
@Service
public class MonthlyPriceRuleCarryForward {
  private final MonthlyPriceFolderSources sources;
  private final PriceLinkedItemMapper linked;
  private final PriceRangeFactorRuleMapper ranges;
  private final PriceRangeItemMapper bands;
  private final OriginalLinkedRuleSnapshot linkedSnapshots;
  private final OriginalRangeRuleSnapshot rangeSnapshots;
  private final MonthlyPriceFolderFinance finance;
  private final FinanceEffectivePriceService effective;
  public MonthlyPriceRuleCarryForward(MonthlyPriceFolderSources sources, PriceLinkedItemMapper linked,
      PriceRangeFactorRuleMapper ranges, PriceRangeItemMapper bands, OriginalLinkedRuleSnapshot linkedSnapshots,
      OriginalRangeRuleSnapshot rangeSnapshots, MonthlyPriceFolderFinance finance, FinanceEffectivePriceService effective) {
    this.sources = sources; this.linked = linked; this.ranges = ranges; this.bands = bands;
    this.linkedSnapshots = linkedSnapshots; this.rangeSnapshots = rangeSnapshots;
    this.finance = finance; this.effective = effective;
  }
  public record Carried(String kind, long sourceId, long targetId, String sourceMonth, MaterialPriceRuleKey identity) {}
  public record Result(String month, List<Carried> carried, FinanceEffectivePriceService.Generation effectivePrices) {}

  public Result complete(String unit, String month, List<Presence> incoming) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("补齐必须在导入完成事务中执行");
    var target = sources.read(unit, month, false);
    Set<String> current = new HashSet<>();
    target.linked().values().forEach(row -> current.add(row.getRuleIdentityKey()));
    target.ranges().values().forEach(row -> current.add(row.getRuleIdentityKey()));
    var prior = sources.read(unit, YearMonth.parse(month).minusMonths(1).toString(), true);
    var linkedRevisions = OriginalRuleRevisions.linked(linked, unit, month);
    var rangeRevisions = OriginalRuleRevisions.ranges(ranges, unit, month);
    Map<String, Integer> linkedGroups = new HashMap<>(), rangeGroups = new HashMap<>();
    var validation = finance.validation();
    List<Carried> carried = new ArrayList<>();
    Set<String> orgs = new TreeSet<>();
    var now = LocalDateTime.now();
    for (var old : prior.linked().values()) {
      var rule = linkedSnapshots.read(old);
      if (current.contains(old.getRuleIdentityKey()) || present(incoming, rule.identity())) continue;
      validation.validate(rule, month);
      var row = new PriceLinkedItem(); BeanUtils.copyProperties(old, row);
      row.setId(null); row.setPricingMonth(month);
      int revision = linkedRevisions.merge(old.getRuleIdentityKey(), 1, Integer::sum);
      row.setRuleRevision(revision);
      row.setSourceInputSnapshotJson(OriginalRuleGroups.withRevision(row.getSourceInputSnapshotJson(),
          linkedGroups.computeIfAbsent(old.getRuleIdentityKey(), ignored -> revision)));
      row.setEffectiveFrom(YearMonth.parse(month).atDay(1)); row.setEffectiveTo(null);
      row.setCreatedAt(now); row.setUpdatedAt(now); linked.insert(row);
      carried.add(new Carried("LINKED", old.getId(), row.getId(), old.getPricingMonth(), rule.identity()));
      orgs.add(rule.identity().orgCode());
    }
    for (var old : prior.ranges().values()) {
      var rule = rangeSnapshots.read(old).rule();
      if (current.contains(old.getRuleIdentityKey()) || present(incoming, rule.identity())) continue;
      validation.validate(rule, month);
      var row = new PriceRangeFactorRule(); BeanUtils.copyProperties(old, row);
      row.setId(null); row.setPricingMonth(month);
      int revision = rangeRevisions.merge(old.getRuleIdentityKey(), 1, Integer::sum);
      row.setVersionNo(revision);
      row.setSourceInputSnapshotJson(OriginalRuleGroups.withRevision(row.getSourceInputSnapshotJson(),
          rangeGroups.computeIfAbsent(old.getRuleIdentityKey(), ignored -> revision)));
      row.setEffectiveFrom(YearMonth.parse(month).atDay(1)); row.setEffectiveTo(null);
      row.setCreatedAt(now); row.setUpdatedAt(now); ranges.insert(row);
      for (var original : bands.selectList(Wrappers.lambdaQuery(PriceRangeItem.class).eq(PriceRangeItem::getFactorRuleId, old.getId()))) {
        var band = new PriceRangeItem(); BeanUtils.copyProperties(original, band);
        band.setId(null); band.setFactorRuleId(row.getId()); band.setEffectiveFrom(row.getEffectiveFrom());
        band.setEffectiveTo(null); band.setCreatedAt(now); band.setUpdatedAt(now); bands.insert(band);
      }
      carried.add(new Carried("RANGE", old.getId(), row.getId(), old.getPricingMonth(), rule.identity()));
      orgs.add(rule.identity().orgCode());
    }
    // 刚生效和原来已发布的当月规则都按此时锁定的当月财务版本复核。
    for (var row : target.linked().values()) {
      var rule = linkedSnapshots.read(row); validation.validate(rule, month); orgs.add(rule.identity().orgCode());
    }
    for (var row : target.ranges().values()) {
      var rule = rangeSnapshots.read(row).rule(); validation.validate(rule, month); orgs.add(rule.identity().orgCode());
    }
    var generated = new FinanceEffectivePriceService.Generation(0, 0, 0);
    for (var org : orgs) generated = generated.plus(effective.generateScope(unit, org));
    return new Result(month, List.copyOf(carried), generated);
  }

  private boolean present(List<Presence> incoming, MaterialPriceRuleKey key) {
    return incoming.stream().anyMatch(p -> p.orgCode().equals(key.orgCode()) && p.materialCode().equals(key.materialCode())
        && (p.supplierCode().isBlank() && p.supplierName().isBlank()
            || !p.supplierCode().isBlank() && p.supplierCode().equals(key.supplierCode())
            || p.supplierCode().isBlank() && p.supplierName().equals(key.supplierName())));
  }
}
