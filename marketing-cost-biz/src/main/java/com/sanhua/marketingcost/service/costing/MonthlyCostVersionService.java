package com.sanhua.marketingcost.service.costing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.mapper.monthly.MonthlyCostVersionMapper;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost.*;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MPP-08 application seam. MPP-12 connects the ordinary quotation orchestrator to this operation. */
@Service
public class MonthlyCostVersionService {
  public record PreparationBinding(long financialItemId, long customerItemId) {}
  public record Snapshot(int schemaVersion, String businessUnitType, long versionId, String costRunNo,
      String oaNo, long oaFormItemId, String productCode, String pricingMonth, Result calculation,
      List<MonthlyPriceScenarios.Pair> priceScenarios, Map<String, PreparationBinding> preparations) {
    public Snapshot { priceScenarios = List.copyOf(priceScenarios); preparations = Map.copyOf(preparations); }
  }
  /** Legacy values are returned as saved, without inventing new monthly/fee/algorithm facts. */
  public record Stored(QuoteCostRunVersion version, Snapshot snapshot,
      List<CostRunPartItem> parts, List<CostRunCostItem> costs) {}

  private final QuoteCostRunVersionMapper versions;
  private final MonthlyCostVersionMapper locks;
  private final PricePrepareItemMapper preparations;
  private final CostRunPartItemMapper parts;
  private final CostRunCostItemMapper costs;
  private final MonthlyMaterialPrices prices;
  private final MonthlyPriceStorage storage;
  private final MonthlyPriceDocumentCodec codec;
  private final MonthlyPriceScenarios scenarios;
  private final TransactionTemplate transaction;

  public MonthlyCostVersionService(QuoteCostRunVersionMapper versions, MonthlyCostVersionMapper locks,
      PricePrepareItemMapper preparations, CostRunPartItemMapper parts, CostRunCostItemMapper costs,
      MonthlyMaterialPrices prices, MonthlyPriceStorage storage, MonthlyPriceDocumentCodec codec,
      MonthlyPriceScenarios scenarios, PlatformTransactionManager transactions) {
    this.versions = versions; this.locks = locks; this.preparations = preparations; this.parts = parts;
    this.costs = costs; this.prices = prices; this.storage = storage; this.codec = codec;
    this.scenarios = scenarios; transaction = new TransactionTemplate(transactions);
    transaction.setTimeout(30);
  }

  public Stored calculateAndSave(String unit, long versionId, MonthlyAdoptionPlan plan,
      Ordinary ordinary, EffectiveTechnicalDataInput technical, BigDecimal customerCopperPerTon,
      Map<String, PreparationBinding> bindings) {
    checkUnit(unit);
    var normalizedBindings = new TreeMap<String, PreparationBinding>();
    var preparationIds = new HashSet<Long>();
    bindings.forEach((path, binding) -> {
      if (normalizedBindings.putIfAbsent(MonthlyBomPricingScope.pathKey(path), binding) != null
          || !preparationIds.add(binding.financialItemId()) || !preparationIds.add(binding.customerItemId()))
        throw new IllegalArgumentException("Each path/scenario requires a distinct preparation identity");
    });
    var settlementRoots = ordinary.materials().stream().filter(line -> line.explanationOf() == null)
        .map(MaterialLine::path).collect(java.util.stream.Collectors.toSet());
    var expectedPaths = new HashSet<String>();
    plan.uses().forEach(use -> expectedPaths.add(
        plan.selections().get(use.request()).fact().type() == PricingType.HOUSEHOLD_INQUIRY
            ? MonthlyBomPricingScope.pathKey(use.path()) : bindingPath(use.path(), settlementRoots)));
    ordinary.materials().stream().filter(line -> line.explanationOf() == null)
        .forEach(line -> expectedPaths.add(line.path()));
    if (!expectedPaths.equals(normalizedBindings.keySet()))
      throw new IllegalArgumentException("Preparation bindings must match the effective costing scope exactly");
    var stableBindings = Map.copyOf(normalizedBindings);
    return transaction.execute(status -> {
      var version = locks.lock(unit, versionId);
      if (version == null || !Set.of("RUNNING", "TRIAL").contains(version.getStatus())
          || storage.versionSnapshot(unit, versionId).jianjiSnapshotJson() != null)
        throw new IllegalStateException("A fresh running/trial cost version is required");
      if (!parts.selectList(Wrappers.lambdaQuery(CostRunPartItem.class)
          .eq(CostRunPartItem::getCostRunVersionId, versionId)).isEmpty()
          || !costs.selectList(Wrappers.lambdaQuery(CostRunCostItem.class)
          .eq(CostRunCostItem::getCostRunVersionId, versionId)).isEmpty())
        throw new IllegalStateException("Cost version already has partial output");
      var ids = plan.selections().values().stream().map(Selection::monthlyPriceId).toList();
      var saved = prices.readByIds(unit, ids);
      plan.selections().forEach((request, selected) -> {
        if (!unit.equals(request.family().businessUnitType())
            || !version.getPricingMonth().equals(request.family().month().toString())
            || !selected.equals(saved.get(selected.monthlyPriceId())))
          throw new IllegalArgumentException("Adopted plan differs from persisted monthly facts/version");
      });
      if (technical != null && (!version.getPricingMonth().equals(technical.accountingMonth())
          || !EffectiveTechnicalDataInput.SOURCE_EFFECTIVE_VERSION.equals(technical.sourceType())))
        throw new IllegalArgumentException("Technical input must be the selected effective version for this month");
      var preparedRows = new HashMap<Long, PricePrepareItem>();
      var allIds = preparationIds.stream().sorted().toList();
      for (int i = 0; i < allIds.size(); i += 256)
        preparations.selectBatchIds(allIds.subList(i, Math.min(i + 256, allIds.size())))
            .forEach(row -> preparedRows.put(row.getId(), row));
      var adoptionDates = new HashMap<Long, LocalDate>();
      for (var price : storage.readByIds(unit, ids)) {
        var firstUse = codec.decode(codec.wrap(price.firstUseJson()), MonthlyMaterialPriceService.FirstUse.class);
        adoptionDates.put(price.id(), firstUse.origin().priceAsOfTime().toLocalDate());
      }
      var pairedPrices = scenarios.calculate(plan.selections(), customerCopperPerTon, adoptionDates);
      var monthlyEvidence = new MonthlyPreparationEvidence(codec);
      var validation = new com.sanhua.marketingcost.service.impl.MonthlyPreparationValidation(
          new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), codec);
      var validatedRoots = new HashSet<String>();
      stableBindings.forEach((path, binding) -> {
        var finance = preparation(preparedRows, version, binding.financialItemId(), version.getFinancePricePrepareNo());
        var customer = preparation(preparedRows, version, binding.customerItemId(), version.getOaPricePrepareNo());
        if (monthlyEvidence.read(finance) != null || monthlyEvidence.read(customer) != null) {
          validation.validate(finance, true, plan, pairedPrices, path);
          validation.validate(customer, false, plan, pairedPrices, path);
          validatedRoots.add(path);
        }
      });
      var bound = new HashMap<String, PricePrepareItem>();
      for (var use : plan.uses()) {
        var selection = plan.selections().get(use.request());
        String path = MonthlyBomPricingScope.pathKey(use.path());
        String root = selection.fact().type() == PricingType.HOUSEHOLD_INQUIRY
            ? path : bindingPath(path, settlementRoots);
        var binding = Objects.requireNonNull(stableBindings.get(root), "Preparation binding required at " + root);
        var finance = preparation(preparedRows, version, binding.financialItemId(), version.getFinancePricePrepareNo());
        var customer = preparation(preparedRows, version, binding.customerItemId(), version.getOaPricePrepareNo());
        if (!root.equals(path)) {
          if (!validatedRoots.contains(root)) throw new IllegalArgumentException("聚合准备必须保存基础月价及实际用量");
          bound.put(path, finance);
          continue;
        }
        for (var row : List.of(finance, customer)) {
          if (!use.request().family().materialCode().equals(row.getMaterialCode())
              || !use.request().priceOrgCode().equals(row.getPriceOrgCode())
              || row.getQuantity() == null || row.getQuantity().abs().compareTo(use.cumulativeQuantity()) != 0)
            throw new IllegalArgumentException("Preparation identity/quantity differs from adopted use");
          storage.attachPreparation(unit, row.getId(), selection.monthlyPriceId(), selection.fact().type());
        }
        var pair = pairedPrices.get(selection.monthlyPriceId());
        requireDecimal(finance.getUnitPrice(), pair.financialPrice(), 8, "Preparation P0");
        requireDecimal(customer.getUnitPrice(), pair.customerPrice(), 8, "Preparation P1");
        if (selection.fact().type() == PricingType.NORMAL) {
          requireDecimal(finance.getAmount(), finance.getQuantity().multiply(pair.financialPrice()), 8, "Preparation P0 amount");
          requireDecimal(customer.getAmount(), customer.getQuantity().multiply(pair.customerPrice()), 8, "Preparation P1 amount");
        }
        bound.put(path, finance);
      }
      for (var line : ordinary.materials()) if (line.explanationOf() == null) {
        var binding = Objects.requireNonNull(stableBindings.get(line.path()), "Settlement preparation required");
        var finance = preparation(preparedRows, version, binding.financialItemId(), version.getFinancePricePrepareNo());
        var customer = preparation(preparedRows, version, binding.customerItemId(), version.getOaPricePrepareNo());
        if (!line.materialCode().equals(finance.getMaterialCode()) || !line.materialCode().equals(customer.getMaterialCode()))
          throw new IllegalArgumentException("Settlement material differs from preparation");
        requireDecimal(finance.getQuantity(), line.quantity(), 16, "Settlement Q");
        requireDecimal(customer.getQuantity(), line.quantity(), 16, "Settlement customer Q");
        requireDecimal(finance.getAmount(), line.financeAmount(), 8, "Settlement financial amount");
        requireDecimal(customer.getAmount(), line.customerAmount(), 8, "Settlement customer amount");
        bound.put(line.path(), finance);
      }
      var calculated = MonthlyProductCost.calculate(plan, ordinary, technical);
      var snapshot = new Snapshot(MonthlyProductCost.SNAPSHOT_VERSION, unit, versionId, version.getCostRunNo(),
          version.getOaNo(), Objects.requireNonNull(version.getOaFormItemId()), version.getProductCode(),
          version.getPricingMonth(), calculated, pairedPrices.values().stream()
              .sorted(Comparator.comparingLong(MonthlyPriceScenarios.Pair::monthlyPriceId)).toList(), stableBindings);
      storage.saveVersionSnapshot(unit, versionId, calculated.feePolicyVersion(), 1,
          codec.unwrap(codec.encode(snapshot)));
      int partCount = saveParts(version, calculated, bound);
      int costCount = saveCosts(version, calculated);
      version.setAlgorithmVersion(calculated.algorithmVersion());
      version.setFeePolicyVersion(calculated.feePolicyVersion());
      version.setTotalCost(calculated.totalCost());
      version.setFinanceMaterialCost(calculated.financeMaterialCost());
      version.setCuMaterialAdjustment(calculated.cuMaterialAdjustment());
      version.setOaMaterialCost(calculated.financeMaterialCost().add(calculated.cuMaterialAdjustment()));
      version.setFinalQuoteAmount(calculated.finalQuoteAmount());
      version.setPartItemCount(partCount); version.setCostItemCount(costCount);
      version.setTrialFinishedAt(LocalDateTime.now());
      if (technical != null) {
        version.setTechDataVersionId(technical.versionId()); version.setTechDataVersionNo(technical.versionNo());
        version.setTechDataSource(technical.sourceType()); version.setTechDataRetrievedAt(technical.retrievedAt());
        // Keep the established technical-input shape; fee serializers preserve large amounts.
        version.setTechDataInputJson(codec.unwrap(codec.encode(technical)));
      }
      if (versions.updateById(version) != 1) throw new IllegalStateException("Cost version publication failed");
      return read(unit, versionId);
    });
  }

  public Stored read(String unit, long id) {
    checkUnit(unit);
    var version = versions.selectById(id);
    if (version == null || !unit.equals(version.getBusinessUnitType())) throw new IllegalArgumentException("Cost version not found");
    var row = storage.versionSnapshot(unit, id);
    Snapshot snapshot = row.jianjiSnapshotJson() == null ? null : codec.decode(codec.wrap(row.jianjiSnapshotJson()), Snapshot.class);
    validateSavedSnapshot(version, snapshot);
    return new Stored(version, snapshot,
        List.copyOf(parts.selectList(Wrappers.lambdaQuery(CostRunPartItem.class).eq(CostRunPartItem::getCostRunVersionId, id).orderByAsc(CostRunPartItem::getId))),
        List.copyOf(costs.selectList(Wrappers.lambdaQuery(CostRunCostItem.class).eq(CostRunCostItem::getCostRunVersionId, id).orderByAsc(CostRunCostItem::getLineNo))));
  }

  /** Shared saved-version validation for history reads and atomic reprice association. */
  public static void validateSavedSnapshot(QuoteCostRunVersion version, Snapshot snapshot) {
    if (snapshot != null && (snapshot.schemaVersion() != 1 || snapshot.versionId() != version.getId()
        || !version.getBusinessUnitType().equals(snapshot.businessUnitType()) || !version.getCostRunNo().equals(snapshot.costRunNo())
        || !version.getOaNo().equals(snapshot.oaNo()) || !Objects.equals(version.getOaFormItemId(), snapshot.oaFormItemId())
        || !version.getProductCode().equals(snapshot.productCode()) || !version.getPricingMonth().equals(snapshot.pricingMonth())
        || !version.getAlgorithmVersion().equals(snapshot.calculation().algorithmVersion())
        || !version.getFeePolicyVersion().equals(snapshot.calculation().feePolicyVersion())))
      throw new IllegalStateException("Cost snapshot ownership or version conflict");
    if (snapshot == null && MonthlyProductCost.ALGORITHM_VERSION.equals(version.getAlgorithmVersion()))
      throw new IllegalStateException("Monthly cost version is missing its saved snapshot");
    if (snapshot != null) {
      requireDecimal(version.getFinanceMaterialCost(), snapshot.calculation().financeMaterialCost(), 6, "Stored material");
      requireDecimal(version.getTotalCost(), snapshot.calculation().totalCost(), 6, "Stored total1");
      requireDecimal(version.getCuMaterialAdjustment(), snapshot.calculation().cuMaterialAdjustment(), 6, "Stored Cu");
      requireDecimal(version.getFinalQuoteAmount(), snapshot.calculation().finalQuoteAmount(), 6, "Stored total2");
    }
  }

  private PricePrepareItem preparation(Map<Long, PricePrepareItem> rows, QuoteCostRunVersion version, long id, String prepareNo) {
    var row = rows.get(id);
    if (row == null || prepareNo == null || !prepareNo.equals(row.getPrepareNo())
        || !version.getBusinessUnitType().equals(row.getBusinessUnitType())
        || !version.getOaNo().equals(row.getOaNo()) || !Objects.equals(version.getOaFormItemId(), row.getOaFormItemId())
        || !version.getProductCode().equals(row.getTopProductCode()) || !version.getPricingMonth().equals(row.getPeriodMonth())
        || !"READY".equals(row.getStatus())
        || !(Integer.valueOf(1).equals(row.getCurrentFlag())
            || (version.getRepriceNo() != null || Objects.equals(prepareNo, version.getFinancePricePrepareNo()))
                && Integer.valueOf(0).equals(row.getCurrentFlag())))
      throw new IllegalArgumentException("Preparation does not belong to this ready cost version");
    return row;
  }

  private int saveParts(QuoteCostRunVersion version, Result result, Map<String, PricePrepareItem> bound) {
    int count = 0;
    var adoptedById = new HashMap<Long, AdoptedPrice>();
    result.adoptedPrices().forEach(value -> adoptedById.putIfAbsent(value.selection().monthlyPriceId(), value));
    for (var line : result.ordinaryInputs().materials()) if (line.explanationOf() == null) {
      var preparation = bound.get(line.path());
      var part = part(version, preparation, line.materialCode(), line.quantity(), line.financeAmount());
      part.setUnitPrice(preparation.getUnitPrice());
      if (parts.insert(part) != 1) throw new IllegalStateException("Cost part insert failed");
      var selected = line.monthlyPriceIds().stream().map(adoptedById::get)
          .filter(Objects::nonNull).filter(value -> value.request().family().materialCode().equals(line.materialCode())).findFirst();
      if (selected.isPresent()) storage.attachCostPart(version.getBusinessUnitType(), part.getId(),
          selected.get().selection().monthlyPriceId(), PricingType.NORMAL);
      count++;
    }
    for (var line : result.household()) {
      var preparation = bound.get(MonthlyBomPricingScope.pathKey(line.paths().getFirst()));
      var part = part(version, preparation, line.materialCode(), line.quantity(), line.amount());
      part.setPriceSource("HOUSEHOLD_INQUIRY"); part.setUnitPrice(line.price());
      if (parts.insert(part) != 1) throw new IllegalStateException("Household cost part insert failed");
      storage.attachCostPart(version.getBusinessUnitType(), part.getId(), line.monthlyPriceId(), PricingType.HOUSEHOLD_INQUIRY);
      count++;
    }
    return count;
  }

  private CostRunPartItem part(QuoteCostRunVersion v, PricePrepareItem prepared, String code, BigDecimal q, BigDecimal amount) {
    var row = new CostRunPartItem(); row.setOaNo(v.getOaNo()); row.setOaFormItemId(v.getOaFormItemId());
    row.setCostRunVersionId(v.getId()); row.setCostRunNo(v.getCostRunNo()); row.setProductCode(v.getProductCode());
    row.setBusinessUnitType(v.getBusinessUnitType()); row.setPartCode(code); row.setQty(q); row.setAmount(amount);
    row.setPricePrepareItemId(prepared.getId()); row.setBomRowId(prepared.getBomRowId());
    row.setPartName(prepared.getMaterialName()); row.setPriceSource(prepared.getPriceSource());
    row.setPriceOrgCode(prepared.getPriceOrgCode());
    row.setMaterialOrganizationCode("220".equals(prepared.getPriceOrgCode()) ? "PLATE" : "COMMERCIAL"); return row;
  }

  private int saveCosts(QuoteCostRunVersion v, Result r) {
    var details = new HashMap<String, OrdinaryDetail>();
    r.ordinaryInputs().details().forEach(detail -> details.put(detail.costCode(), detail));
    var values = expectedCostAmounts(r);
    int number = 0;
    for (var entry : values.entrySet()) {
      var item = new CostRunCostItem(); item.setOaNo(v.getOaNo()); item.setOaFormItemId(v.getOaFormItemId());
      item.setCostRunVersionId(v.getId()); item.setCostRunNo(v.getCostRunNo()); item.setProductCode(v.getProductCode());
      item.setBusinessUnitType(v.getBusinessUnitType()); item.setLineNo(++number); item.setCategory("EXPENSE");
      item.setCostCode(entry.getKey()); item.setCostName(entry.getKey()); item.setAmount(entry.getValue());
      for (var fee : r.fees()) if (fee.code().costCode().equals(entry.getKey())) {
        item.setCostName(fee.code().displayName()); item.setSourceTable(fee.profileVersionId() == null ? null : "lp_quote_tech_data_version");
        item.setSourceId(fee.profileVersionId()); item.setRemark(fee.state().name());
      }
      var detail = details.get(entry.getKey());
      if (detail != null) {
        item.setCostName(detail.costName()); item.setBaseAmount(detail.baseAmount()); item.setRate(detail.rate());
        item.setSourceTable(detail.sourceTable()); item.setSourceId(detail.sourceId()); item.setRemark(detail.remark());
        if (detail.category() != null) item.setCategory(detail.category());
      }
      if (costs.insert(item) != 1) throw new IllegalStateException("Cost item insert failed");
    }
    return number;
  }

  /** The checker uses the same saved projection as the publisher, never current price sources. */
  public static void validateStoredRows(Stored stored) {
    var snapshot = Objects.requireNonNull(stored.snapshot(), "调价成本缺少保存快照");
    var expected = expectedCostAmounts(snapshot.calculation());
    var actual = new HashMap<String, CostRunCostItem>();
    for (var row : stored.costs()) {
      if (actual.putIfAbsent(row.getCostCode(), row) != null)
        throw new IllegalStateException("保存的成本项目重复");
    }
    if (!expected.keySet().equals(actual.keySet()))
      throw new IllegalStateException("保存的成本明细范围不完整");
    expected.forEach((code, amount) -> sameSavedAmount(actual.get(code).getAmount(), amount,
        "保存的成本明细与投影不一致：" + actual.get(code).getCostName()));
    record ExpectedPart(String code, BigDecimal quantity, BigDecimal amount) {}
    var parts = new HashMap<Long, ExpectedPart>();
    for (var line : snapshot.calculation().ordinaryInputs().materials()) if (line.explanationOf() == null) {
      var binding = snapshot.preparations().get(line.path());
      if (binding == null || parts.putIfAbsent(binding.financialItemId(),
          new ExpectedPart(line.materialCode(), line.quantity(), line.financeAmount())) != null)
        throw new IllegalStateException("部品保存路径重复或缺少准备依据");
    }
    for (var line : snapshot.calculation().household()) {
      var binding = snapshot.preparations().get(MonthlyBomPricingScope.pathKey(line.paths().getFirst()));
      if (binding == null || parts.putIfAbsent(binding.financialItemId(),
          new ExpectedPart(line.materialCode(), line.quantity(), line.amount())) != null)
        throw new IllegalStateException("家用整体保存路径重复或缺少准备依据");
    }
    if (stored.parts().size() != parts.size()) throw new IllegalStateException("保存的部品范围不完整");
    var seen = new HashSet<Long>();
    for (var row : stored.parts()) {
      var wanted = parts.get(row.getPricePrepareItemId());
      if (wanted == null || !seen.add(row.getPricePrepareItemId()) || !wanted.code().equals(row.getPartCode()))
        throw new IllegalStateException("部品与实际计价路径不一致");
      sameSavedAmount(row.getQty(), wanted.quantity(), "保存的部品用量与投影不一致");
      sameSavedAmount(row.getAmount(), wanted.amount(), "保存的部品金额与投影不一致");
    }
  }

  private static void sameSavedAmount(BigDecimal actual, BigDecimal expected, String label) {
    if (actual == null && expected == null) return;
    if (actual == null || expected == null
        || actual.compareTo(expected.setScale(actual.scale(), RoundingMode.HALF_UP)) != 0)
      throw new IllegalStateException(label);
  }

  private static Map<String, BigDecimal> expectedCostAmounts(Result r) {
    var values = new LinkedHashMap<String, BigDecimal>();
    values.put("MATERIAL", r.financeMaterialCost()); values.put("DIRECT_LABOR", r.ordinaryInputs().directLabor());
    values.put("INDIRECT_LABOR", r.ordinaryInputs().indirectLabor()); values.put("LOSS", r.ordinary().loss());
    values.put("MANUFACTURE", r.ordinary().manufactureFee()); values.put("MANUFACTURE_COST", r.ordinary().manufactureCost());
    values.put("ADJUSTED_MANUFACTURE_COST", r.ordinary().adjustedManufactureCost());
    values.put("MGMT_EXP", r.ordinary().management()); values.put("SALES_EXP", r.ordinary().sales()); values.put("FIN_EXP", r.ordinary().finance());
    values.put("HOUSEHOLD_SUBTOTAL", r.householdSubtotal()); values.put("HOUSEHOLD_CONTRIBUTION", r.householdContribution());
    values.put("OTHER_INDEPENDENT", r.ordinaryInputs().otherIndependentFees());
    r.fees().forEach(fee -> values.put(fee.code().costCode(), fee.amount()));
    for (var detail : r.ordinaryInputs().details()) {
      if (values.containsKey(detail.costCode()) && !Objects.equals(values.get(detail.costCode()), detail.amount())
          && (values.get(detail.costCode()) == null || detail.amount() == null
              || values.get(detail.costCode()).compareTo(detail.amount()) != 0))
        throw new IllegalArgumentException("Ordinary detail amount differs from calculated value: " + detail.costCode());
      values.putIfAbsent(detail.costCode(), detail.amount());
    }
    values.put("TOTAL", r.totalCost());
    return java.util.Collections.unmodifiableMap(values);
  }

  private static void requireDecimal(BigDecimal actual, BigDecimal expected, int scale, String label) {
    if (actual == null || expected == null || actual.compareTo(expected.setScale(scale, RoundingMode.HALF_UP)) != 0)
      throw new IllegalArgumentException(label + " does not match saved calculation evidence");
  }
  private static String bindingPath(String path, Set<String> roots) {
    String normalized = MonthlyBomPricingScope.pathKey(path);
    return roots.stream().filter(root -> normalized.equals(root) || normalized.startsWith(root + "/"))
        .max(Comparator.comparingInt(String::length)).orElse(normalized);
  }
  private static void checkUnit(String unit) {
    String caller = com.sanhua.marketingcost.security.BusinessUnitContext.getCurrentBusinessUnitType();
    if (!Set.of("COMMERCIAL", "HOUSEHOLD").contains(unit) || caller != null && !caller.isBlank() && !caller.equals(unit))
      throw new IllegalArgumentException("Explicit authorized business unit required");
  }
}
