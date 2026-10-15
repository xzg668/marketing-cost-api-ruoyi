package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.entity.FinanceEffectivePrice;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.pricing.*;
import org.springframework.stereotype.Service;

/** 预检和正式核算执行同一个区间算法，取价结果携带不可变的财务输入和档位证据。 */
@Service
public final class OriginalRangePriceRuntime {
  private final OriginalRangeRuleSnapshot snapshots;
  private final OriginalRangeRuleCalculator calculator;
  private final FinanceFactorPriceReader finance;
  private final PriceRangeItemMapper bands;
  private final OaFormMapper oa;
  private final ObjectMapper json;

  public OriginalRangePriceRuntime(
      OriginalRangeRuleSnapshot snapshots,
      OriginalRangeRuleCalculator calculator,
      FinanceFactorPriceReader finance,
      PriceRangeItemMapper bands,
      OaFormMapper oa,
      ObjectMapper json) {
    this.snapshots = snapshots;
    this.calculator = calculator;
    this.finance = finance;
    this.bands = bands;
    this.oa = oa;
    this.json = json;
  }

  public PriceResolveResult resolve(
      PriceRangeFactorRule item,
      String oaNo,
      CostRunContext run,
      SupplierPreferredPriceSelection<PriceRangeFactorRule> supplier) {
    return resolve(item, oaNo, run, supplier, null);
  }

  public record PreparedInputs(
      OaForm form, java.util.Map<Long, java.util.List<PriceRangeItem>> bands) {}

  public PriceResolveResult resolve(
      PriceRangeFactorRule item,
      String oaNo,
      CostRunContext run,
      SupplierPreferredPriceSelection<PriceRangeFactorRule> supplier,
      PreparedInputs prepared) {
    return resolve(item, oaNo, run, supplier, prepared, null, null);
  }

  public FactorPriceSnapshot financialSnapshot(PriceRangeFactorRule item, String month) {
    return finance
        .readDefault(
            new FinanceFactorImportContext(
                item.getBusinessUnitType(), item.getOrgCode(), month, null, null),
            item.getMaterialCode())
        .financial();
  }

  public PriceResolveResult resolveSaved(
      PriceRangeFactorRule item,
      String oaNo,
      CostRunContext run,
      SupplierPreferredPriceSelection<PriceRangeFactorRule> supplier,
      PreparedInputs prepared,
      FinanceEffectivePrice effective,
      FinanceEffectivePriceService.Calculation saved) {
    return resolve(
        item,
        oaNo,
        run,
        supplier,
        prepared,
        java.util.Objects.requireNonNull(effective),
        java.util.Objects.requireNonNull(saved));
  }

  private PriceResolveResult resolve(
      PriceRangeFactorRule item,
      String oaNo,
      CostRunContext run,
      SupplierPreferredPriceSelection<PriceRangeFactorRule> supplier,
      PreparedInputs prepared,
      FinanceEffectivePrice effective,
      FinanceEffectivePriceService.Calculation saved) {
    try {
      if (run == null || run.getPricingMonth() == null)
        throw new IllegalArgumentException("区间原稿核算月份缺失");
      var rule = snapshots.read(item).rule();
      var context =
          saved == null
              ? finance.readDefault(
                  new FinanceFactorImportContext(
                      item.getBusinessUnitType(),
                      item.getOrgCode(),
                      run.getPricingMonth(),
                      null,
                      null),
                  item.getMaterialCode())
              : new PriceFactorContext(
                  item.getMaterialCode(),
                  new PriceMonthSelection(
                      run.getPricingMonth(), saved.context().months().sourceMonth()),
                  saved.context().financial(),
                  null,
                  saved.context().boardInputs());
      boolean financialOnly =
          CostRunContext.SCENE_MONTHLY_REPRICE.equals(run.getScene())
              || "FINANCE_QUOTE_BASE".equals(run.getPriceScenarioType());
      if (!financialOnly && oaNo != null && !oaNo.isBlank()) {
        OaForm form =
            prepared != null
                ? prepared.form()
                : oa.selectOne(
                    Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, oaNo).last("LIMIT 1"));
        if (form != null && form.getCopperPrice() != null)
          context = context.withCustomerCopperPerTon(form.getCopperPrice());
      }
      var result =
          saved == null
                  || context
                          .requiredPrice(rule.factorKey(), rule.factorUnit(), true)
                          .compareTo(saved.range().factorValue())
                      != 0
              ? calculator.calculate(rule, context)
              : saved.range();
      var selected =
          prepared != null
              ? prepared.bands().getOrDefault(item.getId(), java.util.List.of()).stream()
                  .filter(
                      band ->
                          result.sourceBand().address().sheet().equals(band.getSourceSheetName())
                              && result
                                  .sourceBand()
                                  .address()
                                  .cell()
                                  .equals(band.getSourceCellRef()))
                  .toList()
              : bands.selectList(
                  Wrappers.lambdaQuery(PriceRangeItem.class)
                      .eq(PriceRangeItem::getFactorRuleId, item.getId())
                      .eq(PriceRangeItem::getSourceSheetName, result.sourceBand().address().sheet())
                      .eq(PriceRangeItem::getSourceCellRef, result.sourceBand().address().cell()));
      if (selected.size() != 1) throw new IllegalArgumentException("发布的区间来源行不存在或重复");
      var snapshot =
          new Calculation(
              OriginalRangeRuleSnapshot.VERSION,
              item.getId(),
              item.getVersionNo(),
              item.getPricingMonth(),
              item.getSourceUploadBatchId(),
              rule.identity(),
              rule.factorKey(),
              rule.factorUnit(),
              context,
              result,
              effective == null ? null : effective.getId());
      String trace =
          "行情="
              + result.factorValue().toPlainString()
              + " "
              + rule.factorUnit()
              + "，命中="
              + result.selectedBounds().low()
              + "—"
              + result.selectedBounds().high()
              + "，来源="
              + result.sourceBand().address().sheet()
              + "!"
              + result.sourceBand().address().cell()
              + (result.extrapolation() == null ? "" : "，按已核实档差向上推算")
              + "，财务来源月="
              + context.months().sourceMonth()
              + "，财务发布="
              + context.financial().batchId();
      var evidence =
          new PriceResolveEvidence(
              item.getId(),
              item.getImportBatchNo(),
              item.getSupplierName(),
              item.getSupplierCode(),
              supplier == null ? null : supplier.supplyRatio(),
              supplier == null ? null : supplier.supplyRatioRecordId(),
              item.getEffectiveFrom(),
              null,
              context.months().carriedForward(),
              (context.months().carriedForward() ? "财务因素沿用历史月份；" : "")
                  + (supplier != null && "FALLBACK_HIGH".equals(supplier.matchMode())
                      ? supplier.traceMessage()
                      : ""),
              json.writeValueAsString(snapshot));
      return PriceResolveResult.hit(
          result.priceExcludingTax(), "区间价", trace, selected.getFirst().getId(), evidence);
    } catch (org.springframework.dao.TransientDataAccessException retryable) {
      throw retryable;
    } catch (Exception error) {
      return PriceResolveResult.miss(
          "ORIGINAL_RANGE_PRICE_INVALID",
          "物料=" + item.getMaterialCode() + "：" + error.getMessage());
    }
  }

  public record Calculation(
      String calculatorVersion,
      Long ruleId,
      Integer ruleRevision,
      String ruleMonth,
      Long sourceUploadBatchId,
      MaterialPriceRuleKey ruleIdentity,
      String factorKey,
      FactorPriceUnit factorUnit,
      PriceFactorContext context,
      OriginalRangeRuleCalculator.Result result,
      Long effectivePriceId) {}
}
