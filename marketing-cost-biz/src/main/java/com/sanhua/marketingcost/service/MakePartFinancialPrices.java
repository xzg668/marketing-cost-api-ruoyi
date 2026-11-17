package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPreparationPrice;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.BeanUtils;

/** Public manufacturing prices use P0; quotation P1 rows and their saved evidence stay untouched. */
public final class MakePartFinancialPrices {
  private static final Pattern SAVED_SCRAP_KG = Pattern.compile("计算追溯:[^；]*?scrap_weight_kg=([-+0-9.eE]+)(?:,|$)");
  private final MonthlyPriceDocumentCodec codec;
  public MakePartFinancialPrices(MonthlyPriceDocumentCodec codec) { this.codec = codec; }

  public List<MakePartPriceCalcRow> project(List<MakePartPriceCalcRow> source) {
    var result = new ArrayList<MakePartPriceCalcRow>();
    var groups = new LinkedHashMap<List<Object>, List<Integer>>();
    for (var original : source) {
      var row = new MakePartPriceCalcRow();
      BeanUtils.copyProperties(original, row);
      // Recovery is transient on the entity. Historical non-weight inputs retain it in the trace.
      if (row.getRecoveredScrapWeightG() == null && row.getRemark() != null) {
        var recovery = SAVED_SCRAP_KG.matcher(row.getRemark());
        while (recovery.find()) row.setRecoveredScrapWeightG(new BigDecimal(recovery.group(1)).movePointRight(3));
      }
      boolean alreadyFinancial = QuotePriceScenarioType.FINANCE_QUOTE_BASE.name().equals(row.getPriceScenarioType());
      // Reuse exact saved prices on repeated refreshes, not rounded DECIMAL display columns.
      if (!alreadyFinancial || row.getRawInputSnapshotJson() != null) {
        row.setRawUnitPrice(base(row, row.getRawInputSnapshotJson(), row.getChildMaterialNo()));
      }
      if (!Boolean.TRUE.equals(row.getNoScrapConfirmed())
          && (!alreadyFinancial || row.getScrapInputSnapshotJson() != null)) {
          row.setScrapUnitPrice(base(row, row.getScrapInputSnapshotJson(), row.getScrapCode()));
      }
      row.setPriceScenarioType(QuotePriceScenarioType.FINANCE_QUOTE_BASE.name());
      if (row.getRemark() != null)
        row.setRemark(row.getRemark().replaceAll("(?:^|；)计算追溯:[^；]*", ""));
      var key = Arrays.<Object>asList(row.getBusinessUnitType(), row.getPricingMonth(), row.getCalcBatchId(),
          row.getSourceCostingRowId() == null ? row.getParentMaterialNo() : row.getSourceCostingRowId());
      groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result.size());
      result.add(row);
    }
    var calculator = new MakePartPriceCalculator();
    for (var indices : groups.values()) {
      var calculated = calculator.calculate(indices.stream().map(result::get).toList());
      for (int i = 0; i < indices.size(); i++) {
        var before = result.get(indices.get(i));
        var after = calculated.get(i);
        if (MakePartPriceCalculator.STATUS_OK.equals(before.getStatus())
            && !MakePartPriceCalculator.STATUS_OK.equals(after.getStatus()))
          throw new IllegalArgumentException("制造件财务价格缺少保存的计算依据：" + before.getId());
        if (before.getStatus() != null && !MakePartPriceCalculator.STATUS_OK.equals(before.getStatus()))
          after.setRemark(before.getRemark());
        result.set(indices.get(i), after);
      }
    }
    return result;
  }

  private BigDecimal base(MakePartPriceCalcRow row, String snapshot, String code) {
    if (snapshot == null || snapshot.isBlank()) {
      if (!MakePartPriceCalculator.STATUS_OK.equals(row.getStatus())) return null;
      throw new IllegalArgumentException("制造件财务价格缺少保存的基础价格依据：" + code);
    }
    var value = codec.decode(snapshot, MonthlyPreparationPrice.class);
    if (!value.request().matchesBomMaterialCode(code)
        || !Objects.equals(row.getBusinessUnitType(), value.request().family().businessUnitType())
        || !Objects.equals(row.getPricingMonth(), value.request().family().month().toString())
        || value.financialPrice().compareTo(value.selection().fact().price()) != 0)
      throw new IllegalArgumentException("制造件财务价格来源身份不一致：" + code);
    return value.financialPrice().multiply(value.unitPriceMultiplier());
  }
}
