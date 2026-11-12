package com.sanhua.marketingcost.service.scrap;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only comparison of saved financial inputs with explicit formula-variable trial values. */
@Service
@Transactional(readOnly = true)
public class ScrapPriceTrialService {
  private final ScrapPriceQueryService query;
  private final ScrapPriceCalculator calculator;
  private final ScrapPriceCalculationEvidence evidence;

  public ScrapPriceTrialService(ScrapPriceQueryService query, ScrapPriceCalculator calculator,
      ScrapPriceCalculationEvidence evidence) {
    this.query = query;
    this.calculator = calculator;
    this.evidence = evidence;
  }

  public record Request(Long expectedVersion, Map<String, BigDecimal> inputs) {}

  public record Factor(String variable, String name, String source,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal financeInput,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal trialInput) {}

  public record Result(Long id, Long rowVersion, String priceMonth, String financeMonth,
      String schemeCode, String financeFile,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal financePrice,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal trialPrice,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal difference,
      List<Factor> factors) {}

  public Result trial(String businessUnit, long id, Request request) {
    if (id <= 0 || request == null) throw new IllegalArgumentException("请选择废料价格及试算条件");
    var row = query.detail(businessUnit, id);
    if (request.expectedVersion() == null || !Objects.equals(row.getRowVersion(), request.expectedVersion()))
      throw new IllegalArgumentException("废料价格已更新，请重新打开详情后试算");
    if (!"READY".equals(row.getCalcStatus()) || row.getUnitPrice() == null)
      throw new IllegalArgumentException("废料价格尚不可试算：" + Objects.toString(row.getCalcMessage(), "尚未取得财务基价"));
    if ("LINKED".equals(row.getPriceType())
        && (row.getCalculationJson() == null || row.getCalculationJson().isBlank()))
      throw new IllegalArgumentException("废料月价缺少财务基价证据");
    var baseline = calculator.prepare(row, evidence.publicFactors(row));
    if (calculator.calculate(baseline).compareTo(row.getUnitPrice()) != 0)
      throw new IllegalArgumentException("保存的废料价格与计算依据不一致，请核对价格资料");
    var values = new TreeMap<>(baseline.variables());
    for (var entry : (request.inputs() == null ? Map.<String,BigDecimal>of() : request.inputs()).entrySet()) {
      if (!values.containsKey(entry.getKey())) throw new IllegalArgumentException("公式未使用因素变量：" + entry.getKey());
      if (entry.getValue() == null || entry.getValue().signum() < 0)
        throw new IllegalArgumentException("因素" + entry.getKey() + "试算值须不小于0（含税元/公斤）");
      values.put(entry.getKey(), entry.getValue());
    }
    // Preview variables carry no persistence identity or replacement financial evidence.
    var p1 = calculator.calculate(new ScrapPriceCalculator.Input(
        baseline.rule(), Map.copyOf(values), null, null, null));
    var factors = baseline.rule().factorBindings().entrySet().stream().map(binding -> {
      var original = baseline.factors().financial().required(binding.getValue());
      String source = original.sources().stream()
          .map(cell -> cell.sheetName() + "!" + cell.cellRef()).distinct()
          .collect(java.util.stream.Collectors.joining("；"));
      return new Factor(binding.getKey(), original.factorName(), source,
          baseline.variables().get(binding.getKey()), values.get(binding.getKey()));
    }).toList();
    var p0 = row.getUnitPrice();
    return new Result(row.getId(), row.getRowVersion(), row.getPriceMonth(), row.getFinanceMonth(),
        row.getSchemeCode(), baseline.factors() == null ? null : baseline.factors().financial().fileName(),
        p0, p1, p1.subtract(p0), factors);
  }
}
