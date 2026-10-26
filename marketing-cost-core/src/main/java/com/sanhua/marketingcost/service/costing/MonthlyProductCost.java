package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.dto.costing.ProductFeeCode;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyBomPricingScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Pure versioned computation. MAKE/PACK amounts and non-BOM material retain their existing rules. */
public final class MonthlyProductCost {
  public static final String ALGORITHM_VERSION = "MONTHLY_PRODUCT_COST_V1";
  public static final int SNAPSHOT_VERSION = 1;
  private MonthlyProductCost() {}

  /** A settlement contributes once; its raw-component explanation rows do not contribute. */
  public record MaterialLine(String path, String materialCode, BigDecimal quantity,
      List<Long> monthlyPriceIds, BigDecimal financeAmount,
      BigDecimal customerAmount, String explanationOf) {
    public MaterialLine {
      path = MonthlyBomPricingScope.pathKey(path);
      if (materialCode == null || materialCode.isBlank() || quantity == null || quantity.signum() == 0)
        throw new IllegalArgumentException("Material identity and nonzero signed quantity required");
      monthlyPriceIds = List.copyOf(monthlyPriceIds);
      if (monthlyPriceIds.isEmpty() || monthlyPriceIds.stream().anyMatch(id -> id == null || id <= 0))
        throw new IllegalArgumentException("Material amounts require adopted price evidence");
      Objects.requireNonNull(financeAmount); Objects.requireNonNull(customerAmount);
      if (explanationOf != null) explanationOf = MonthlyBomPricingScope.pathKey(explanationOf);
    }
  }

  /** Immutable ordinary detail, including existing department modes and original source coordinates. */
  public record OrdinaryDetail(String costCode, String costName, BigDecimal baseAmount,
      BigDecimal rate, BigDecimal amount, String sourceTable, Long sourceId, String remark, String category) {
    public OrdinaryDetail {
      if (costCode == null || costCode.isBlank() || costName == null || costName.isBlank())
        throw new IllegalArgumentException("Stable ordinary detail code/name required");
      for (var fee : ProductFeeCode.values()) if (fee.costCode().equals(costCode))
        throw new IllegalArgumentException("Product fees must come from the selected PROFILE, not ordinary details");
      if (costCode.equals("TOTAL") || costCode.equals("FINAL_QUOTE") || costCode.startsWith("HOUSEHOLD_") || costCode.startsWith("CU_"))
        throw new IllegalArgumentException("Ordinary details cannot supply final totals, household or Cu contributions");
      if (category != null && !Set.of("EXPENSE", "BOM_BUCKET").contains(category))
        throw new IllegalArgumentException("Unknown ordinary detail category");
    }
    public static OrdinaryDetail from(com.sanhua.marketingcost.dto.CostRunCostItemDto row) {
      return new OrdinaryDetail(row.getCostCode(), row.getCostName(), row.getBaseAmount(), row.getRate(),
          row.getAmount(), row.getSourceTable(), row.getSourceId(), row.getRemark(), row.getCategory());
    }
  }

  /** Already resolved amounts: nonBomMaterial includes water/power and FINAL_QUOTE department fees. */
  public record Ordinary(List<MaterialLine> materials, BigDecimal nonBomMaterial,
      BigDecimal directLabor, BigDecimal indirectLabor, BigDecimal lossRate,
      BigDecimal manufactureRate, BigDecimal coefficient, BigDecimal managementRate,
      BigDecimal salesRate, BigDecimal financeRate, BigDecimal otherIndependentFees,
      List<OrdinaryDetail> details) {
    public Ordinary(List<MaterialLine> materials, BigDecimal nonBomMaterial,
        BigDecimal directLabor, BigDecimal indirectLabor, BigDecimal lossRate,
        BigDecimal manufactureRate, BigDecimal coefficient, BigDecimal managementRate,
        BigDecimal salesRate, BigDecimal financeRate, BigDecimal otherIndependentFees) {
      this(materials, nonBomMaterial, directLabor, indirectLabor, lossRate, manufactureRate,
          coefficient, managementRate, salesRate, financeRate, otherIndependentFees, List.of());
    }
    public Ordinary {
      materials = List.copyOf(materials);
      details = List.copyOf(details);
      var codes = new HashSet<String>();
      for (var detail : details) if (!codes.add(detail.costCode()))
        throw new IllegalArgumentException("Duplicate ordinary detail identity");
      for (var amount : new BigDecimal[]{nonBomMaterial, directLabor, indirectLabor, otherIndependentFees})
        Objects.requireNonNull(amount, "Resolved ordinary amount required");
      for (var rate : new BigDecimal[]{lossRate, manufactureRate, managementRate, salesRate, financeRate})
        if (rate == null || rate.signum() < 0) throw new IllegalArgumentException("Valid resolved rates required");
      if (manufactureRate.compareTo(BigDecimal.ONE) >= 0 || coefficient == null || coefficient.signum() <= 0)
        throw new IllegalArgumentException("Invalid manufacture rate or product coefficient");
    }
  }

  public record HouseholdLine(long monthlyPriceId, String materialCode, String pricingUnit,
      String sourceEvidenceHash, BigDecimal price, BigDecimal householdRate, BigDecimal salesRate,
      BigDecimal quantity, BigDecimal amount, List<String> paths) {
    public HouseholdLine { paths = List.copyOf(paths); }
  }
  public record FeeLine(ProductFeeCode code, BigDecimal amount, ProductFeeContract.State state,
      Long profileVersionId) {}
  public record AdoptedPrice(com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceRequest request, Selection selection) {}
  public record Result(String algorithmVersion, String feePolicyVersion, Ordinary ordinaryInputs,
      OrdinaryCostAmounts.Result ordinary, List<AdoptedPrice> adoptedPrices, List<Use> effectiveUses,
      Set<String> suppressedPaths,
      List<HouseholdLine> household, List<FeeLine> fees, BigDecimal financeMaterialCost,
      BigDecimal householdSubtotal, BigDecimal householdContribution, BigDecimal productFeeTotal,
      BigDecimal totalCost, BigDecimal cuMaterialAdjustment, BigDecimal finalQuoteAmount,
      EffectiveTechnicalDataInput technicalInput) {
    public Result {
      adoptedPrices = List.copyOf(adoptedPrices); effectiveUses = List.copyOf(effectiveUses);
      suppressedPaths = Set.copyOf(suppressedPaths); household = List.copyOf(household); fees = List.copyOf(fees);
    }
  }

  public static Result calculate(MonthlyAdoptionPlan plan, Ordinary input,
      EffectiveTechnicalDataInput technical) {
    Objects.requireNonNull(plan); Objects.requireNonNull(input);
    validateScope(plan, input.materials());
    BigDecimal material = input.nonBomMaterial();
    BigDecimal difference = BigDecimal.ZERO;
    for (var line : input.materials()) if (line.explanationOf() == null) {
      // Ordinary part amounts retain the existing per-line six-place boundary.
      material = material.add(OrdinaryCostAmounts.money(line.financeAmount()));
      difference = difference.add(line.customerAmount().subtract(line.financeAmount()));
    }
    material = OrdinaryCostAmounts.money(material);
    var amounts = OrdinaryCostAmounts.calculate(material, input.directLabor(), input.indirectLabor(),
        input.lossRate(), input.manufactureRate(), input.coefficient(), input.managementRate(),
        input.salesRate(), input.financeRate(), input.otherIndependentFees());
    var groups = new TreeMap<Long, List<Use>>();
    for (var use : plan.uses()) {
      var price = plan.selections().get(use.request());
      if (price.fact().type() == PricingType.HOUSEHOLD_INQUIRY)
        groups.computeIfAbsent(price.monthlyPriceId(), ignored -> new ArrayList<>()).add(use);
    }
    var household = new ArrayList<HouseholdLine>();
    BigDecimal subtotal = BigDecimal.ZERO;
    for (var entry : groups.entrySet()) {
      var uses = entry.getValue();
      var first = uses.getFirst();
      var selection = plan.selections().get(first.request());
      var fact = selection.fact();
      BigDecimal quantity = uses.stream().map(Use::cumulativeQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
      // Keep the rational calculation intact until the line boundary, including fractional Q.
      BigDecimal amount = fact.price().multiply(BigDecimal.ONE.add(fact.householdRate()).add(input.salesRate()))
          .multiply(quantity).divide(BigDecimal.ONE.add(fact.householdRate()), 6, RoundingMode.HALF_UP);
      household.add(new HouseholdLine(entry.getKey(), first.request().family().materialCode(),
          first.request().family().unit(), fact.evidence().sha256(), fact.price(), fact.householdRate(),
          input.salesRate(), quantity, amount, uses.stream().map(Use::path).sorted().toList()));
      subtotal = subtotal.add(amount);
    }
    subtotal = OrdinaryCostAmounts.money(subtotal);
    BigDecimal contribution = OrdinaryCostAmounts.money(subtotal.multiply(input.coefficient()));
    var fees = fees(technical);
    BigDecimal feeTotal = fees.stream().map(FeeLine::amount).filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    feeTotal = OrdinaryCostAmounts.money(feeTotal);
    BigDecimal total = OrdinaryCostAmounts.money(amounts.total().add(contribution).add(feeTotal));
    difference = OrdinaryCostAmounts.money(difference);
    return new Result(ALGORITHM_VERSION, ProductFeeContract.POLICY_VERSION, input, amounts, plan.selections().entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().family().canonical() + entry.getKey().priceOrgCode() + entry.getKey().conditions()))
            .map(entry -> new AdoptedPrice(entry.getKey(), entry.getValue())).toList(), plan.uses(), plan.suppressedPaths(),
        household, fees, material, subtotal, contribution, feeTotal, total, difference,
        OrdinaryCostAmounts.money(total.add(difference)), technical);
  }

  private static List<FeeLine> fees(EffectiveTechnicalDataInput technical) {
    var values = technical == null ? null : technical.productFees();
    if (values != null && (!EffectiveTechnicalDataInput.SOURCE_EFFECTIVE_VERSION.equals(technical.sourceType())
        || technical.sourceVersionId("PROFILE") == null || technical.sourceVersionId("PROFILE") <= 0
        || !"CNY".equals(values.currency())))
      throw new IllegalArgumentException("Product fees require the selected effective CNY PROFILE");
    var result = new ArrayList<FeeLine>();
    for (var code : ProductFeeCode.values()) {
      BigDecimal amount = values == null ? null : switch (code) {
        case MOULD -> values.unitMouldFee();
        case CERTIFICATION -> values.unitCertificationFee();
        case DEPRECIATION -> values.unitDepreciationFee();
        case TOOLING -> values.unitToolingFee();
      };
      var state = ProductFeeContract.state(amount, values == null ? null : values.encodingVersion());
      result.add(new FeeLine(code, amount, state, values == null ? null : technical.sourceVersionId("PROFILE")));
    }
    return List.copyOf(result);
  }

  private static void validateScope(MonthlyAdoptionPlan plan, List<MaterialLine> materials) {
    var reachable = new HashSet<com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceRequest>();
    var pending = new ArrayDeque<com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceRequest>();
    plan.uses().forEach(use -> pending.add(use.request()));
    while (!pending.isEmpty()) {
      var request = pending.removeFirst();
      if (!reachable.add(request)) continue;
      var selected = plan.selections().get(request);
      if (selected == null || !selected.applicability().matches(request))
        throw new IllegalArgumentException("Missing applicable adopted price dependency");
      pending.addAll(selected.fact().dependencies());
    }
    if (!reachable.equals(plan.selections().keySet()))
      throw new IllegalArgumentException("Only consumed prices and their dependencies belong in a cost version");
    var families = new HashMap<Long, com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceFamilyKey>();
    plan.selections().forEach((request, selected) -> {
      var previous = families.putIfAbsent(selected.monthlyPriceId(), request.family());
      if (previous != null && !previous.equals(request.family()))
        throw new IllegalArgumentException("One monthly price cannot belong to different material families");
    });
    var ids = new HashMap<Long, Selection>();
    for (var selection : plan.selections().values()) {
      if (selection.monthlyPriceId() == null || selection.monthlyPriceId() <= 0)
        throw new IllegalArgumentException("Costing requires persisted monthly facts");
      var previous = ids.putIfAbsent(selection.monthlyPriceId(), selection);
      if (previous != null && !previous.equals(selection))
        throw new IllegalArgumentException("One monthly identity cannot contain different facts");
    }
    var householdPaths = new HashSet<String>();
    var uses = new HashSet<String>();
    for (var use : plan.uses()) {
      String path = MonthlyBomPricingScope.pathKey(use.path());
      var selected = plan.selections().get(use.request());
      if (!uses.add(path) || selected == null || !selected.applicability().matches(use.request()))
        throw new IllegalArgumentException("Invalid adopted use at " + path);
      if (selected.fact().type() == PricingType.HOUSEHOLD_INQUIRY) householdPaths.add(path);
    }
    for (String suppressed : plan.suppressedPaths()) {
      String path = MonthlyBomPricingScope.pathKey(suppressed);
      boolean covered = false;
      for (String ancestor = parent(path); ancestor != null; ancestor = parent(ancestor)) {
        if (householdPaths.contains(ancestor)) { covered = true; break; }
      }
      if (!covered) throw new IllegalArgumentException("Suppressed path must be below a household use");
    }
    var suppressedPaths = new HashSet<String>();
    plan.suppressedPaths().forEach(path -> suppressedPaths.add(MonthlyBomPricingScope.pathKey(path)));
    var rows = new HashMap<String, MaterialLine>();
    for (var row : materials) {
      if (rows.putIfAbsent(row.path(), row) != null) throw new IllegalArgumentException("Duplicate material path");
      for (String path = row.path(); path != null; path = parent(path)) {
        if (householdPaths.contains(path)) throw new IllegalArgumentException("Household scope must be excluded before ordinary costing");
        if (suppressedPaths.contains(path)) throw new IllegalArgumentException("Household scope includes a suppressed path");
      }
      for (long id : row.monthlyPriceIds()) if (!ids.containsKey(id) || ids.get(id).fact().type() != PricingType.NORMAL)
        throw new IllegalArgumentException("Ordinary amounts must reference adopted NORMAL facts");
    }
    var contributing = new HashMap<String, MaterialLine>();
    for (var row : materials) if (row.explanationOf() == null) contributing.put(row.path(), row);
    for (var row : materials) {
      if (row.explanationOf() != null) {
        var parent = contributing.get(row.explanationOf());
        if (parent == null || !inside(row.path(), parent.path()) || row.path().equals(parent.path()))
          throw new IllegalArgumentException("Explanation requires its contributing settlement parent");
      } else for (String ancestor = parent(row.path()); ancestor != null; ancestor = parent(ancestor)) {
        if (contributing.containsKey(ancestor))
          throw new IllegalArgumentException("Nested material settlements would be counted twice");
      }
    }
    for (var use : plan.uses()) {
      String path = MonthlyBomPricingScope.pathKey(use.path());
      var selection = plan.selections().get(use.request());
      for (String ancestor = parent(path); ancestor != null; ancestor = parent(ancestor)) {
        if (householdPaths.contains(ancestor)) throw new IllegalArgumentException("Adopted use below household cut");
      }
      if (selection.fact().type() == PricingType.NORMAL) {
        MaterialLine settlement = null;
        for (String ancestor = path; ancestor != null; ancestor = parent(ancestor)) {
          if (contributing.containsKey(ancestor)) { settlement = contributing.get(ancestor); break; }
        }
        if (settlement == null || !settlement.monthlyPriceIds().contains(selection.monthlyPriceId()))
          throw new IllegalArgumentException("Ordinary use is missing from costing: " + use.path());
        if (settlement.path().equals(path) && (!settlement.materialCode().equals(use.request().family().materialCode())
            || settlement.quantity().abs().compareTo(use.cumulativeQuantity()) != 0))
          throw new IllegalArgumentException("Direct ordinary settlement differs from its adopted use");
      }
    }
  }

  private static String parent(String path) {
    int slash = path.lastIndexOf('/'); return slash < 0 ? null : path.substring(0, slash);
  }

  private static boolean inside(String path, String ancestor) {
    return path.equals(ancestor) || path.startsWith(ancestor + "/");
  }
}
