package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.entity.*;
import java.math.BigDecimal;
import java.util.*;

/** Capture the exact selected sources, including adjustments, before ordinary costs are rebound. */
public final class CmsCostCompositions {
  private CmsCostCompositions() {}
  public static String number(BigDecimal value) { return value == null ? "" : value.toPlainString(); }
  public static CostAmountComposition auxiliary(AuxCostItemDto source) {
    var excluded = (source.getExcludedSurfaceAmount() == null ? BigDecimal.ZERO : source.getExcludedSurfaceAmount())
        .add(source.getShapeExcludedAmount()==null ? BigDecimal.ZERO : source.getShapeExcludedAmount());
    var original = source.getUnitPrice() == null ? null : source.getUnitPrice().add(excluded);
    return new CostAmountComposition("SAVED", "CMS原始明细逐行由分转元并保留6位小数后汇总；"
        + "采用基数＝来源合计－剔除金额；本次金额＝采用基数×本次上浮系数（保留6位小数）。CMS路径已含用量，不再重复乘BOM用量。",
        number(original), number(excluded), source.getExclusionEvidence(),
        List.of(new CostAmountComposition.Source(source.getSourceTable(), source.getSourceRowIds(),
            source.getMaterialCode(), source.getSourcePeriod(), source.getAuxSubjectCode(), number(original))),
        source.getShapeExclusionRows()==null ? List.of() : source.getShapeExclusionRows());
  }
  public static CostAmountComposition labor(List<CmsCostSourceEffective> selected) {
    var amount = selected.stream().map(row -> row.getAmountYuan()==null ? BigDecimal.ZERO : row.getAmountYuan()
        .add(row.getShapeExcludedAmount()==null ? BigDecimal.ZERO : row.getShapeExcludedAmount())).filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    var excluded=selected.stream().map(CmsCostSourceEffective::getShapeExcludedAmount).filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO,BigDecimal::add);
    return new CostAmountComposition("SAVED", "按本次采用的CMS年度生效工资来源，原始明细逐行由分÷100转元、保留6位小数后相加；直接人工和辅助人工分别汇总，不乘辅料上浮系数。",
        number(amount), number(excluded), excluded.signum()==0 ? null : "形态切换按改动料号剔除自身CMS工资，路径已含实际用量", selected.stream().map(row -> new CostAmountComposition.Source(
            row.getSourceTable(), row.getSourceRowIds(), row.getParentCode(), row.getPeriod(),
            row.getSubjectCode(), number(row.getAmountYuan()==null ? null : row.getAmountYuan()
                .add(row.getShapeExcludedAmount()==null ? BigDecimal.ZERO : row.getShapeExcludedAmount())))).toList(),
        selected.stream().filter(row -> row.getShapeExclusionRows()!=null).flatMap(row -> row.getShapeExclusionRows().stream()).toList());
  }
  public static CostAmountComposition department(DepartmentFundRate rate, BigDecimal laborTotal) {
    var row = new LinkedHashMap<String,String>();
    row.put("sourceTable", "lp_department_fund_rate"); row.put("sourceId", String.valueOf(rate.getId()));
    row.put("subjectName", rate.getExpenseSubject()); row.put("period", String.valueOf(rate.getRateYear()));
    row.put("division", rate.getBusinessDivision()); row.put("laborTotal", number(laborTotal));
    row.put("manhourRate", number(rate.getManhourRate())); row.put("quoteRatio", number(rate.getQuoteRatio()));
    row.put("upliftRatio", number(rate.getUpliftRatio() == null ? BigDecimal.ONE : rate.getUpliftRatio()));
    row.put("calculationMode", DepartmentFundRate.RATE_CALCULATION_MODE_FINAL_QUOTE.equalsIgnoreCase(
        rate.getRateCalculationMode()==null?null:rate.getRateCalculationMode().trim())
        ? "最终费率" : "计划费率乘上浮系数");
    row.put("budgetAmount", number(rate.getBudgetAmount())); row.put("totalWorkMinutes", number(rate.getTotalWorkMinutes()));
    row.put("planRate", number(rate.getPlanRate())); row.put("sourceBatch", rate.getSourceBatchNo());
    return new CostAmountComposition("SAVED", "人工合计＝直接人工＋辅助人工；工时基数＝人工合计÷工时工资率（保留6位小数）；"
        + "本次金额＝工时基数×本次有效费率（保留6位小数）。最终费率模式直接采用报价费率，计划模式为报价费率×上浮系数。",
        number(laborTotal), "0", "下列同时列出人工来源与本次采用的部门费率参数。", List.of(), List.of(row));
  }
}
