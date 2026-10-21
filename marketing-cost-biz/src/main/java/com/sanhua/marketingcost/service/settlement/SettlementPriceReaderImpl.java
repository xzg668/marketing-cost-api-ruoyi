package com.sanhua.marketingcost.service.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.service.pricing.MaterialPriceMonthSelector;
import com.sanhua.marketingcost.service.pricing.PriceResolveEvidence;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.settlement.*;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 两组织共享的结算读取端口；每个业务单元/月批次只读一组所需规则和至多一组基价。 */
@Service
public class SettlementPriceReaderImpl implements SettlementPriceReader {
  public static final String SOURCE_TABLE = "lp_settlement_price";
  private final SettlementPriceRepository prices;
  private final SettlementBasePriceRepository bases;
  private final SettlementPriceCalculator calculator;
  private final ObjectMapper json;

  public SettlementPriceReaderImpl(SettlementPriceRepository prices, SettlementBasePriceRepository bases,
      SettlementPriceCalculator calculator, ObjectMapper json) {
    this.prices = prices;
    this.bases = bases;
    this.calculator = calculator;
    this.json = json;
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
      boolean needsBase = rows.values().stream().anyMatch(row -> calculator.mode(row) == SettlementPriceMode.LINKED_FORMULA);
      var base = needsBase ? bases.effective(group.getKey()).orElse(null) : null;
      for (var request : group.getValue()) {
        var row = rows.get(key(request));
        if (row == null) {
          results.put(request, PriceResolveResult.miss(MaterialPriceMonthSelector.MATERIAL_MONTH_PRICE_MISSING,
              "不晚于 " + request.accountingMonth() + " 的" + request.priceType().label() + "无适用原稿：" + request.materialCode()));
          continue;
        }
        results.put(request, resolveRow(request, row, base));
      }
    }
    return Collections.unmodifiableMap(results);
  }

  private PriceResolveResult resolveRow(SettlementPriceRequest request, SettlementPriceRecord row, SettlementBasePrice selectedBase) {
    var mode = calculator.mode(row);
    var base = mode == SettlementPriceMode.LINKED_FORMULA ? selectedBase : null;
    try {
      var calculation = calculator.calculate(row, base);
      String description = mode == SettlementPriceMode.LINKED_FORMULA ? "按结算铜锌基价计算" : "采用原稿给定价";
      return PriceResolveResult.hit(calculation.unitPrice(), request.priceType().label(), description, row.getId(),
          evidence(request, row, base, mode, calculation.unitPrice()));
    } catch (SettlementPriceCalculator.CalculationException invalid) {
      return new PriceResolveResult(null, PriceResolveResult.SOURCE_ERROR, invalid.getMessage(), row.getId(), invalid.code(),
          evidence(request, row, base, mode, null));
    }
  }

  private PriceResolveEvidence evidence(SettlementPriceRequest request, SettlementPriceRecord rule,
      SettlementBasePrice base, SettlementPriceMode mode, java.math.BigDecimal unitPrice) {
    boolean ruleCarry = rule.getPriceMonth().compareTo(request.accountingMonth()) < 0;
    boolean baseCarry = base != null && base.priceMonth().compareTo(request.accountingMonth()) < 0;
    List<String> notes = new ArrayList<>();
    if (ruleCarry) notes.add("沿用 " + rule.getPriceMonth() + " 结算原稿");
    if (baseCarry) notes.add("沿用 " + base.priceMonth() + " 结算基价");
    String snapshot;
    try {
      ObjectNode source = json.valueToTree(rule);
      source.remove("sourceRowsJson");
      source.set("sourceRows", json.readTree(rule.getSourceRowsJson()));
      var values = new LinkedHashMap<String, Object>();
      values.put("sourceTable", SOURCE_TABLE);
      values.put("resultRefType", RESULT_REF_TYPE);
      values.put("sourceRecordId", rule.getId());
      values.put("priceType", request.priceType());
      values.put("accountingMonth", request.accountingMonth());
      values.put("consumerOrgCode", request.consumerOrgCode());
      values.put("mode", mode);
      values.put("rule", source);
      values.put("base", base);
      values.put("ruleCarriedForward", ruleCarry);
      values.put("baseCarriedForward", baseCarry);
      values.put("unitPrice", unitPrice);
      values.put("unitPriceScale", SettlementPriceCalculator.UNIT_PRICE_SCALE);
      values.put("calculatorVersion", SettlementPriceCalculator.CALCULATOR_VERSION);
      snapshot = json.writeValueAsString(values);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException("结算取价依据无法生成：" + rule.getMaterialCode(), invalid);
    }
    return new PriceResolveEvidence(rule.getId(), "STP-" + rule.getId() + "-R" + rule.getRevisionNo(),
        null, null, null, null, YearMonth.parse(rule.getPriceMonth()).atDay(1), null, ruleCarry || baseCarry,
        notes.isEmpty() ? null : String.join("；", notes), snapshot);
  }

  private SettlementPriceRepository.RuleKey key(SettlementPriceRequest request) {
    return new SettlementPriceRepository.RuleKey(request.materialCode(), request.priceType());
  }
}
