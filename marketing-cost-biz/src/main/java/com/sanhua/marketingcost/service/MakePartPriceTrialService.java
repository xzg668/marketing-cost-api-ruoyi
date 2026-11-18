package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.pricing.CustomerMaterialPrices;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.Selection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.BeanUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Replays a complete saved manufacturing occurrence. No generation, adoption or costing writes. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MakePartPriceTrialService {
  private final MakePartPriceCalcRowMapper rows;
  private final MonthlyPriceDocumentCodec codec;
  private final MonthlyPriceScenarios scenarios;
  private final MakePartPriceTraceSource sources;

  public MakePartPriceTrialService(MakePartPriceCalcRowMapper rows, MonthlyPriceDocumentCodec codec,
      MonthlyPriceScenarios scenarios, MakePartPriceTraceSource sources) {
    this.rows = rows; this.codec = codec; this.scenarios = scenarios; this.sources = sources;
  }

  public record Request(String expectedBasis, BigDecimal copperPerTon) {}
  public record Line(long id, String process, String rawQuantity, String rawQuantityUnit,
      String grossWeightG, String netWeightG, String scrapWeightG,
      MakePartPriceTraceSource.Source raw, MakePartPriceTraceSource.Source scrap,
      String rawAmount, String scrapAmount, String cost,
      String trialRawAmount, String trialScrapAmount, String trialCost, String difference) {}
  public record Result(String basis, String materialNo, String materialName, String month,
      String originalPrice, String trialPrice, String difference, String financeCopperPerTon,
      String trialCopperPerTon, boolean trialAvailable, String message, List<Line> lines) {}

  public Result calculate(long id, Request request) {
    String unit = BusinessUnitContext.getCurrentBusinessUnitType();
    if (!Set.of("COMMERCIAL", "HOUSEHOLD").contains(Objects.toString(unit, "")))
      throw new AccessDeniedException("请选择业务单元");
    var selected = rows.selectById(id);
    if (selected == null || !unit.equals(selected.getBusinessUnitType()))
      throw new AccessDeniedException("制造件价格不存在或不属于当前业务单元");
    var query = Wrappers.<MakePartPriceCalcRow>lambdaQuery()
        .eq(MakePartPriceCalcRow::getBusinessUnitType, unit)
        .eq(MakePartPriceCalcRow::getCalcBatchId, selected.getCalcBatchId())
        .eq(MakePartPriceCalcRow::getParentMaterialNo, selected.getParentMaterialNo())
        .eq(MakePartPriceCalcRow::getPricingMonth, selected.getPricingMonth())
        .eq(MakePartPriceCalcRow::getPriceScenarioType, selected.getPriceScenarioType());
    if (selected.getOaNo() == null) query.isNull(MakePartPriceCalcRow::getOaNo);
    else query.eq(MakePartPriceCalcRow::getOaNo, selected.getOaNo());
    if (selected.getSourceCostingRowId() == null) query.eq(MakePartPriceCalcRow::getId, id);
    else query.eq(MakePartPriceCalcRow::getSourceCostingRowId, selected.getSourceCostingRowId());
    // Sort the few matching rows in memory: source JSON can exceed MySQL's filesort buffer.
    var saved = rows.selectList(query).stream().sorted(Comparator.comparing(MakePartPriceCalcRow::getId)).toList();
    if (saved.isEmpty()) throw new IllegalArgumentException("未找到完整计算明细，请重新打开");
    String basis = MonthlyPriceDocumentCodec.sha256(codec.encode(saved));
    boolean trial = request != null;
    if (trial && (request.expectedBasis() == null || !basis.equals(request.expectedBasis())))
      throw new IllegalArgumentException("价格依据已更新，请重新打开后试算");
    if (trial && (request.copperPerTon() == null || request.copperPerTon().signum() <= 0
        || request.copperPerTon().compareTo(new BigDecimal("1000000000")) > 0))
      throw new IllegalArgumentException("请输入大于0且不超过1000000000的Cu价格（元/吨）");

    List<MakePartPriceCalcRow> base;
    String unavailable = null;
    try {
      if (saved.stream().anyMatch(row -> !"OK".equals(row.getStatus())))
        throw new IllegalArgumentException("资料不完整，补齐后可试算");
      if (saved.stream().anyMatch(row -> !"FINANCE_QUOTE_BASE".equals(row.getPriceScenarioType())))
        throw new IllegalArgumentException("该记录为旧报价口径，暂不支持铜价试算");
      base = new MakePartFinancialPrices(codec).project(saved);
      for (int i = 0; i < saved.size(); i++) {
        same(saved.get(i).getCostPrice(), base.get(i).getCostPrice());
        same(saved.get(i).getParentTotalCostPrice(), base.get(i).getParentTotalCostPrice());
      }
    } catch (IllegalArgumentException error) {
      if (trial) throw error;
      base = saved;
      unavailable = error.getMessage();
    }
    if (unavailable != null) return new Result(basis, selected.getParentMaterialNo(),
        selected.getParentMaterialName(), selected.getPricingMonth(), text(selected.getParentTotalCostPrice()),
        null, null, null, null, false, unavailable, List.of());

    var inputs = new LinkedHashMap<MonthlyPriceRequest, Selection>();
    var dates = new HashMap<Long, LocalDate>();
    var prices = new LinkedHashMap<String, MonthlyPreparationPrice>();
    for (var row : base) for (boolean raw : List.of(true, false)) {
      if (!raw && Boolean.TRUE.equals(row.getNoScrapConfirmed())) continue;
      var price = codec.decode(raw ? row.getRawInputSnapshotJson() : row.getScrapInputSnapshotJson(), MonthlyPreparationPrice.class);
      prices.put(row.getId() + (raw ? ":raw" : ":scrap"), price);
      add(inputs, price.request(), price.selection());
      if (price.monthlyInputs() != null)
        price.monthlyInputs().prices().forEach(p -> add(inputs, p.request(), p.selection()));
      if (row.getPriceAsOfTime() != null && price.selection().monthlyPriceId() != null)
        dates.put(price.selection().monthlyPriceId(), row.getPriceAsOfTime().toLocalDate());
    }
    var projected = scenarios.calculateMaterials(inputs,
        CustomerMaterialPrices.copperAndZinc(trial ? request.copperPerTon() : null, null), dates);
    var changed = new ArrayList<MakePartPriceCalcRow>();
    for (var row : base) {
      var copy = new MakePartPriceCalcRow(); BeanUtils.copyProperties(row, copy);
      var raw = prices.get(row.getId() + ":raw");
      copy.setRawUnitPrice(projected.get(raw.selection().monthlyPriceId()).customerPrice().multiply(raw.unitPriceMultiplier()));
      var scrap = prices.get(row.getId() + ":scrap");
      if (scrap != null) copy.setScrapUnitPrice(projected.get(scrap.selection().monthlyPriceId()).customerPrice().multiply(scrap.unitPriceMultiplier()));
      changed.add(copy);
    }
    changed = new ArrayList<>(new MakePartPriceCalculator().calculate(changed));
    var lines = new ArrayList<Line>();
    var copperValues = new TreeSet<BigDecimal>(BigDecimal::compareTo);
    for (int i = 0; i < base.size(); i++) {
      var before = base.get(i); var after = changed.get(i);
      if (!"OK".equals(after.getStatus())) throw new IllegalArgumentException("试算资料不完整，请核对原料及废料依据");
      var rawPrice = prices.get(before.getId() + ":raw");
      var scrapPrice = prices.get(before.getId() + ":scrap");
      BigDecimal quantity = MakePartConsumption.rawQuantity(before);
      BigDecimal scrapKg = MakePartConsumption.scrapQuantityKg(before);
      String quantityUnit = MakePartProcessTypePolicy.PROCESS_TYPE_BLANK.equals(before.getItemProcessType())
          ? "件" : MakePartConsumption.nonWeightRaw(before) ? before.getStockUnit() : "千克";
      var raw = sources.describe(before.getChildMaterialNo(), before.getChildMaterialName(), rawPrice,
          projected.get(rawPrice.selection().monthlyPriceId()), before.getRawUnitPrice(), after.getRawUnitPrice(), quantityUnit);
      var scrap = scrapPrice == null ? sources.noScrap() : sources.describe(before.getScrapCode(),
          before.getScrapName(), scrapPrice, projected.get(scrapPrice.selection().monthlyPriceId()),
          before.getScrapUnitPrice(), after.getScrapUnitPrice(), "千克");
      for (var source : List.of(raw, scrap)) if (source.financeCopperPerTon() != null)
        copperValues.add(new BigDecimal(source.financeCopperPerTon()));
      lines.add(new Line(before.getId(), before.getItemProcessType(), text(quantity), quantityUnit,
          text(before.getGrossWeightG()), text(before.getNetWeightG()), text(scrapKg.movePointRight(3)), raw, scrap,
          text(quantity.multiply(before.getRawUnitPrice())), text(scrapKg.multiply(before.getScrapUnitPrice())), text(before.getCostPrice()),
          text(quantity.multiply(after.getRawUnitPrice())), text(scrapKg.multiply(after.getScrapUnitPrice())), text(after.getCostPrice()),
          text(after.getCostPrice().subtract(before.getCostPrice()))));
    }
    var original = base.getFirst().getParentTotalCostPrice();
    var result = changed.getFirst().getParentTotalCostPrice();
    return new Result(basis, selected.getParentMaterialNo(), selected.getParentMaterialName(), selected.getPricingMonth(),
        text(original), text(result), text(result.subtract(original)),
        copperValues.size() == 1 ? text(copperValues.first()) : null,
        trial ? text(request.copperPerTon()) : null, true,
        copperValues.size() > 1 ? "原料与废料采用的铜价不同，分别列在计算明细中" : null, List.copyOf(lines));
  }

  private static void add(Map<MonthlyPriceRequest, Selection> inputs, MonthlyPriceRequest request, Selection selection) {
    var prior = inputs.putIfAbsent(request, selection);
    if (prior != null && !prior.equals(selection)) throw new IllegalArgumentException("保存的价格依据相互冲突，请核对来源");
  }
  private static void same(BigDecimal a, BigDecimal b) {
    if (a == null || b == null || a.setScale(8, RoundingMode.HALF_UP).compareTo(b.setScale(8, RoundingMode.HALF_UP)) != 0)
      throw new IllegalArgumentException("原价与保存的计算依据不一致，暂不可试算");
  }
  static String text(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
}
