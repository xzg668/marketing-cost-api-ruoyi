package com.sanhua.marketingcost.service.costing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.costing.ProductFeeCode;
import com.sanhua.marketingcost.dto.financequote.*;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.PricingType;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost.*;
import com.sanhua.marketingcost.service.impl.MonthlyPreparationComponents;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

/** Connects saved quotation preparations and the existing ordinary fee calculation. */
@Service
public final class MonthlyQuotationCosting {
  private final PricePrepareItemMapper preparations;
  private final MonthlyCostVersionService versions;
  private final MonthlyPreparationEvidence evidence;
  private final MonthlyPriceDocumentCodec codec;
  private final MonthlyPreparationComponents components;

  public MonthlyQuotationCosting(PricePrepareItemMapper preparations, MonthlyCostVersionService versions,
      MonthlyPriceDocumentCodec codec, ObjectMapper json) {
    this.preparations = preparations; this.versions = versions; this.codec = codec;
    evidence = new MonthlyPreparationEvidence(codec);
    components = new MonthlyPreparationComponents(json, codec);
  }

  public MonthlyCostVersionService.Stored save(QuoteCostRunVersion version, CostRunObjectResult ordinary,
      BigDecimal customerCopperPerTon) {
    var customer = items(version.getOaPricePrepareNo());
    var finance = items(version.getFinancePricePrepareNo());
    if (!customer.keySet().equals(finance.keySet()) || customer.isEmpty())
      throw new IllegalArgumentException("双场景准备范围不完整");
    var prices = new LinkedHashMap<MonthlyPriceRequest, MonthlyAdoptionPlan.Selection>();
    var uses = new ArrayList<MonthlyAdoptionPlan.Use>();
    var suppressed = new HashSet<String>();
    var materials = new ArrayList<MaterialLine>();
    var bindings = new HashMap<String, MonthlyCostVersionService.PreparationBinding>();
    for (var entry : customer.entrySet()) {
      var oa = entry.getValue(); var fin = finance.get(entry.getKey());
      if (oa.getQuantity() != null && oa.getQuantity().signum() == 0
          && oa.getAmount() != null && oa.getAmount().signum() == 0
          && fin.getQuantity() != null && fin.getQuantity().signum() == 0
          && fin.getAmount() != null && fin.getAmount().signum() == 0) continue;
      var inputs = evidence.read(oa);
      if (inputs == null || !inputs.equals(evidence.read(fin)) || inputs.uses().isEmpty())
        throw new IllegalArgumentException("成本准备缺少一致的已采用月价依据，请重新生成最终价格");
      for (var price : inputs.prices()) {
        var previous = prices.putIfAbsent(price.request(), price.selection());
        if (previous != null && !previous.equals(price.selection()))
          throw new IllegalArgumentException("同次成本包含不一致的月价");
      }
      uses.addAll(inputs.uses()); suppressed.addAll(inputs.suppressedPaths());
      if (bindings.putIfAbsent(inputs.rootPath(), new MonthlyCostVersionService.PreparationBinding(fin.getId(), oa.getId())) != null)
        throw new IllegalArgumentException("成本准备贡献路径重复");
      boolean household = inputs.uses().stream().anyMatch(use ->
          inputs.plan().selections().get(use.request()).fact().type() == PricingType.HOUSEHOLD_INQUIRY);
      if (household) {
        if (inputs.uses().size() != 1 || !inputs.uses().getFirst().path().equals(inputs.rootPath()))
          throw new IllegalArgumentException("家用整体计价必须先从普通母件范围独立");
      } else materials.add(new MaterialLine(inputs.rootPath(), oa.getMaterialCode(), oa.getQuantity(),
          inputs.prices().stream().map(price -> price.selection().monthlyPriceId()).distinct().toList(),
          fin.getAmount(), oa.getAmount(), null));
    }
    var plan = new MonthlyAdoptionPlan(prices, uses, suppressed);
    return versions.calculateAndSave(version.getBusinessUnitType(), version.getId(), plan,
        ordinary(ordinary.getCostItems(), materials), ordinary.getContext().getEffectiveTechnicalData(),
        customerCopperPerTon, bindings);
  }

  private Map<String, PricePrepareItem> items(String prepareNo) {
    if (prepareNo == null) throw new IllegalArgumentException("成本缺少准备批次");
    var result = new TreeMap<String, PricePrepareItem>();
    for (var row : preparations.selectList(Wrappers.lambdaQuery(PricePrepareItem.class)
        .eq(PricePrepareItem::getPrepareNo, prepareNo))) {
      if (!"READY".equals(row.getStatus()) || row.getSettlementKey() == null
          || result.putIfAbsent(row.getSettlementKey(), row) != null)
        throw new IllegalArgumentException("成本准备状态或结算身份不完整");
    }
    return result;
  }

  private static Ordinary ordinary(List<CostRunCostItemDto> rows, List<MaterialLine> materials) {
    var costs = new LinkedHashMap<String, CostRunCostItemDto>();
    for (var row : rows) if (costs.putIfAbsent(row.getCostCode(), row) != null)
      throw new IllegalArgumentException("普通成本项重复：" + row.getCostCode());
    var fees = new HashSet<String>();
    for (var code : ProductFeeCode.values()) fees.add(code.costCode());
    var independent = rows.stream().filter(row -> row.getCostCode().startsWith("OTHER_EXP_")
        && !"OTHER_EXP_PACKAGE".equals(row.getCostCode()) && !fees.contains(row.getCostCode()))
        .map(CostRunCostItemDto::getAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    var nonBom = amount(costs, "MATERIAL").subtract(materials.stream().map(MaterialLine::financeAmount)
        .map(OrdinaryCostAmounts::money).reduce(BigDecimal.ZERO, BigDecimal::add));
    return new Ordinary(materials, nonBom, amount(costs, "DIRECT_LABOR"), amount(costs, "INDIRECT_LABOR"),
        rate(costs, "LOSS"), rate(costs, "MANUFACTURE"), rate(costs, "ADJUSTED_MANUFACTURE_COST"),
        rate(costs, "MGMT_EXP"), rate(costs, "SALES_EXP"), rate(costs, "FIN_EXP"), independent,
        rows.stream().filter(row -> !"TOTAL".equals(row.getCostCode()) && !fees.contains(row.getCostCode()))
            .map(OrdinaryDetail::from).toList());
  }

  private static BigDecimal amount(Map<String, CostRunCostItemDto> rows, String code) {
    var row = Objects.requireNonNull(rows.get(code), "普通成本缺项：" + code);
    return Objects.requireNonNull(row.getAmount(), "普通成本金额缺失：" + code);
  }
  private static BigDecimal rate(Map<String, CostRunCostItemDto> rows, String code) {
    var row = Objects.requireNonNull(rows.get(code), "普通成本缺项：" + code);
    return Objects.requireNonNull(row.getRate(), "普通成本费率缺失：" + code);
  }

  public QuoteCuMaterialDiffResult difference(MonthlyCostVersionService.Stored stored) {
    var version = stored.version(); var snapshot = Objects.requireNonNull(stored.snapshot());
    var adopted = new LinkedHashMap<MonthlyPriceRequest, MonthlyAdoptionPlan.Selection>();
    snapshot.calculation().adoptedPrices().forEach(price -> adopted.put(price.request(), price.selection()));
    var exposure = new MonthlyCopperExposure(codec).read(adopted);
    var ids = snapshot.preparations().values().stream()
        .flatMap(binding -> java.util.stream.Stream.of(binding.financialItemId(), binding.customerItemId()))
        .distinct().toList();
    var saved = new HashMap<Long, PricePrepareItem>();
    for (int i = 0; i < ids.size(); i += 256)
      preparations.selectBatchIds(ids.subList(i, Math.min(i + 256, ids.size())))
          .forEach(row -> saved.put(row.getId(), row));
    var rows = new ArrayList<QuoteCuMaterialDiffItemResult>();
    for (var line : snapshot.calculation().ordinaryInputs().materials()) if (line.explanationOf() == null) {
      var binding = Objects.requireNonNull(snapshot.preparations().get(line.path()), "Missing saved preparation binding");
      var row = base(version, line.path(), binding);
      row.setDetailLevel("SETTLEMENT"); row.setContributesToAdjustment(1);
      row.setMaterialCode(line.materialCode()); row.setQuantity(line.quantity());
      row.setFinanceUnitPrice(line.financeAmount().divide(line.quantity(), 8, java.math.RoundingMode.HALF_UP));
      row.setOaUnitPrice(line.customerAmount().divide(line.quantity(), 8, java.math.RoundingMode.HALF_UP));
      row.setFinanceAmount(line.financeAmount()); row.setOaAmount(line.customerAmount());
      row.setDiffAmount(line.customerAmount().subtract(line.financeAmount()));
      row.setCuAffected(affected(exposure, line.monthlyPriceIds()) ? 1 : 0);
      row.setPriceFormulaRefType(MonthlyPreparationPrice.KIND);
      snapshot.calculation().adoptedPrices().stream().filter(price ->
          price.request().family().materialCode().equals(line.materialCode())
              && line.monthlyPriceIds().contains(price.selection().monthlyPriceId()))
          .findFirst().ifPresent(price -> row.setPriceFormulaRefId(price.selection().monthlyPriceId()));
      var children = componentRows(version, binding, line.path(), line.materialCode(), saved, exposure);
      var trace = new LinkedHashMap<String, Object>();
      trace.put("path", line.path()); trace.put("monthlyPriceIds", line.monthlyPriceIds());
      if (!children.isEmpty()) {
        trace.put("financeRoundingAdjustment", row.getFinanceAmount().subtract(children.stream()
            .map(QuoteCuMaterialDiffItemResult::getFinanceAmount).reduce(BigDecimal.ZERO, BigDecimal::add)));
        trace.put("customerRoundingAdjustment", row.getOaAmount().subtract(children.stream()
            .map(QuoteCuMaterialDiffItemResult::getOaAmount).reduce(BigDecimal.ZERO, BigDecimal::add)));
      }
      row.setTraceJson(codec.unwrap(codec.encode(trace)));
      requireCuDifference(row); rows.add(row); rows.addAll(children);
    }
    for (var use : snapshot.calculation().effectiveUses()) {
      var price = adopted.get(use.request());
      if (price.fact().type() != PricingType.HOUSEHOLD_INQUIRY) continue;
      var binding = Objects.requireNonNull(snapshot.preparations().get(use.path()), "Missing saved household preparation binding");
      var row = base(version, use.path(), binding);
      row.setDetailLevel("SETTLEMENT"); row.setContributesToAdjustment(0);
      row.setMaterialCode(use.request().family().materialCode()); row.setItemType("HOUSEHOLD_INQUIRY");
      row.setQuantity(use.cumulativeQuantity()); row.setFinanceUnitPrice(price.fact().price());
      row.setOaUnitPrice(price.fact().price());
      row.setFinanceAmount(OrdinaryCostAmounts.money(price.fact().price().multiply(use.cumulativeQuantity())));
      row.setOaAmount(row.getFinanceAmount()); row.setDiffAmount(BigDecimal.ZERO); row.setCuAffected(0);
      row.setPriceFormulaRefType(MonthlyPreparationPrice.KIND); row.setPriceFormulaRefId(price.monthlyPriceId());
      row.setTraceJson(codec.unwrap(codec.encode(Map.of("path", use.path(), "monthlyPriceId", price.monthlyPriceId(),
          "sourceEvidenceHash", price.fact().evidence().sha256(), "householdRate", price.fact().householdRate()))));
      rows.add(row);
    }
    for (int i = 0; i < rows.size(); i++) rows.get(i).setLineNo(i + 1);
    int settlements = (int) rows.stream().filter(row -> "SETTLEMENT".equals(row.getDetailLevel())).count();
    return new QuoteCuMaterialDiffResult(version.getId(), version.getCostRunNo(),
        snapshot.calculation().cuMaterialAdjustment(), settlements, rows.size() - settlements,
        (int) rows.stream().filter(row -> "SETTLEMENT".equals(row.getDetailLevel())
            && Integer.valueOf(1).equals(row.getCuAffected())).count(), rows);
  }

  private List<QuoteCuMaterialDiffItemResult> componentRows(QuoteCostRunVersion version,
      MonthlyCostVersionService.PreparationBinding binding, String path, String parent,
      Map<Long, PricePrepareItem> saved, Map<Long, Boolean> exposure) {
    var fin = saved.get(binding.financialItemId()); var oa = saved.get(binding.customerItemId());
    if (fin == null || oa == null) throw new IllegalArgumentException("Missing saved preparation");
    for (var prepared : List.of(fin, oa))
      if (!Objects.equals(version.getBusinessUnitType(), prepared.getBusinessUnitType())
          || !Objects.equals(version.getOaFormItemId(), prepared.getOaFormItemId())
          || !Objects.equals(version.getPricingMonth(), prepared.getPeriodMonth()))
        throw new IllegalArgumentException("Saved preparation scope differs from cost version");
    var financial = components.read(fin); var customer = components.read(oa);
    if (!financial.keySet().equals(customer.keySet())) throw new IllegalArgumentException("Saved component scopes differ");
    var result = new ArrayList<QuoteCuMaterialDiffItemResult>();
    for (var key : financial.keySet()) {
      var f = financial.get(key); var c = customer.get(key);
      if (!f.materialCode().equals(c.materialCode()) || f.quantity().compareTo(c.quantity()) != 0
          || !f.monthlyPriceIds().equals(c.monthlyPriceIds()))
        throw new IllegalArgumentException("Saved component inputs differ");
      var row = base(version, path + "/component/" + key, binding);
      row.setParentSettlementKey(path); row.setParentMaterialCode(parent);
      row.setDetailLevel("RAW_COMPONENT"); row.setItemType(f.referenceType()); row.setContributesToAdjustment(0);
      row.setMaterialCode(f.materialCode()); row.setMaterialName(f.materialName()); row.setQuantity(f.quantity());
      row.setFinanceUnitPrice(f.unitPrice()); row.setOaUnitPrice(c.unitPrice());
      row.setFinanceAmount(f.amount()); row.setOaAmount(c.amount()); row.setDiffAmount(c.amount().subtract(f.amount()));
      row.setCuAffected(affected(exposure, f.monthlyPriceIds()) ? 1 : 0);
      row.setPriceFormulaRefType(f.referenceType()); row.setPriceFormulaRefId(f.referenceId());
      row.setTraceJson(codec.unwrap(codec.encode(Map.of("monthlyPriceIds", f.monthlyPriceIds(),
          "financeComponent", f.traceJson(), "customerComponent", c.traceJson()))));
      requireCuDifference(row); result.add(row);
    }
    return result;
  }

  private static boolean affected(Map<Long, Boolean> exposure, List<Long> ids) {
    boolean affected = false;
    for (long id : ids) affected |= Objects.requireNonNull(exposure.get(id), "Missing captured price exposure");
    return affected;
  }
  private static void requireCuDifference(QuoteCuMaterialDiffItemResult row) {
    if (row.getDiffAmount().signum() != 0 && !Integer.valueOf(1).equals(row.getCuAffected()))
      throw new IllegalArgumentException("Non-Cu monthly input has a nonzero difference");
  }
  private static QuoteCuMaterialDiffItemResult base(QuoteCostRunVersion version, String path,
      MonthlyCostVersionService.PreparationBinding binding) {
    var row = new QuoteCuMaterialDiffItemResult();
    row.setCostRunVersionId(version.getId()); row.setCostRunNo(version.getCostRunNo());
    row.setSettlementKey(path); row.setTopProductCode(version.getProductCode());
    row.setFinancePrepareItemId(binding.financialItemId()); row.setOaPrepareItemId(binding.customerItemId());
    row.setBusinessUnitType(version.getBusinessUnitType()); return row;
  }
}
