package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.service.pricing.MaterialPriceMonthSelector;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.settlement.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 两组织共享的结算读取端口；批量读取已保存的月度结果，不在查询或核算时重算单价。 */
@Service
public class SettlementPriceReaderImpl implements SettlementPriceReader {
  public static final String SOURCE_TABLE = "lp_settlement_price";
  private final SettlementPriceRepository prices;
  private final SettlementFinanceBaseReader bases;
  private final SettlementPriceCalculator calculator;
  private final SettlementEffectivePriceRepository saved;
  private final SettlementPriceEvidence evidence;

  public SettlementPriceReaderImpl(SettlementPriceRepository prices, SettlementFinanceBaseReader bases,
      SettlementPriceCalculator calculator, SettlementEffectivePriceRepository saved, SettlementPriceEvidence evidence) {
    this.prices = prices;
    this.bases = bases;
    this.calculator = calculator;
    this.saved = saved;
    this.evidence = evidence;
  }

  @Override
  @Transactional(readOnly = true)
  public PriceResolveResult resolve(SettlementPriceRequest request) { return resolveBatch(List.of(request)).get(request); }

  @Override
  @Transactional(readOnly = true)
  public Map<SettlementPriceRequest, PriceResolveResult> resolveBatch(List<SettlementPriceRequest> requests) {
    Map<SettlementScope, List<SettlementPriceRequest>> groups = new LinkedHashMap<>();
    for (var request : new LinkedHashSet<>(requests)) {
      Objects.requireNonNull(request, "结算取价请求不能为空");
      var scope = new SettlementScope(request.businessUnitType(), request.accountingMonth());
      groups.computeIfAbsent(scope, ignored -> new ArrayList<>()).add(request);
    }
    Map<SettlementPriceRequest, PriceResolveResult> results = new LinkedHashMap<>();
    for (var group : groups.entrySet()) {
      var rows = prices.effectiveBatch(group.getKey(), group.getValue().stream().map(this::key).toList());
      boolean needsBase = rows.values().stream().anyMatch(row -> calculator.mode(row) == SettlementPriceMode.LINKED_FORMULA
          && row.weights().completeAndConfirmed());
      SettlementBasePrice base = null;
      String baseFailure = null;
      if (needsBase) {
        try { base = bases.effective(group.getKey()).orElse(null); }
        catch (IllegalArgumentException | IllegalStateException invalid) { baseFailure = invalid.getMessage(); }
      }
      var monthly = saved.effective(group.getKey(), rows.values().stream().map(SettlementPriceRecord::getId).toList(),
          base == null ? 0 : base.id());
      for (var request : group.getValue()) {
        var row = rows.get(key(request));
        if (row == null) {
          results.put(request, PriceResolveResult.miss(MaterialPriceMonthSelector.MATERIAL_MONTH_PRICE_MISSING,
              "不晚于 " + request.accountingMonth() + " 的" + request.priceType().label() + "无适用原稿：" + request.materialCode()));
          continue;
        }
        results.put(request, resolveRow(request, row, base, baseFailure, monthly.get(row.getId())));
      }
    }
    return Collections.unmodifiableMap(results);
  }

  private PriceResolveResult resolveRow(SettlementPriceRequest request, SettlementPriceRecord row,
      SettlementBasePrice selectedBase, String baseFailure, SettlementEffectivePriceRepository.Result result) {
    var mode = calculator.mode(row);
    var base = mode == SettlementPriceMode.LINKED_FORMULA ? selectedBase : null;
    try {
      if (mode == SettlementPriceMode.LINKED_FORMULA) {
        if (!row.weights().completeAndConfirmed())
          throw new SettlementPriceCalculator.CalculationException("SETTLEMENT_WEIGHTS_MISSING",
              "结算联动价原稿铜锌重量不完整，请修正原稿后重新导入：" + row.getMaterialCode());
        calculator.validateFormula(row);
        if (baseFailure != null)
          throw new SettlementPriceCalculator.CalculationException("SETTLEMENT_FINANCE_BASE_INVALID", baseFailure);
        if (base == null)
          throw new SettlementPriceCalculator.CalculationException("SETTLEMENT_BASE_MISSING",
              "尚未发布适用的商用财务铜锌基价：" + row.getMaterialCode());
      }
      if (result == null || !mode.name().equals(result.priceMode()))
        throw new SettlementPriceCalculator.CalculationException("SETTLEMENT_EFFECTIVE_PRICE_MISSING",
            "适用的月度结算结果尚未生成，请检查原稿和商用财务基价发布：" + row.getMaterialCode());
      return PriceResolveResult.hit(result.unitPrice(), request.priceType().label(), "采用已保存的月度结算价格", row.getId(),
          evidence.forRequest(request, result.calculationJson(), result));
    } catch (SettlementPriceCalculator.CalculationException invalid) {
      return new PriceResolveResult(null, PriceResolveResult.SOURCE_ERROR, invalid.getMessage(), row.getId(), invalid.code(),
          evidence.forRequest(request, evidence.capture(row, base, new SettlementPriceCalculator.Calculation(mode, null)), null));
    }
  }

  private SettlementPriceRepository.RuleKey key(SettlementPriceRequest request) {
    return new SettlementPriceRepository.RuleKey(request.materialCode(), request.priceType());
  }
}
