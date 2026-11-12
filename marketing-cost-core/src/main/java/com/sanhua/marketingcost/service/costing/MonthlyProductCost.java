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
  // Configured parts are captured in the product's source revision. Keep the running V3
  // identity so unchanged products remain reusable and new saved costs match the worker.
  public static final String ALGORITHM_VERSION = "MONTHLY_PRODUCT_COST_V3";
  public static final int SNAPSHOT_VERSION = 1;
  public static boolean isMonthlyVersion(String version) {
    return ALGORITHM_VERSION.equals(version) || "MONTHLY_PRODUCT_COST_V2".equals(version)
        || "MONTHLY_PRODUCT_COST_V1".equals(version);
  }
  private MonthlyProductCost() {}

  /** A settlement contributes once; its raw-component explanation rows do not contribute. */
  public record MaterialLine(String path, String materialCode, BigDecimal quantity,
      List<Long> monthlyPriceIds, BigDecimal financeAmount,
      BigDecimal customerAmount, String explanationOf, ProductPartCostRule separateRule,
      List<String> priceUsePaths) {
    public MaterialLine(String path, String code, BigDecimal quantity, List<Long> ids,
        BigDecimal finance, BigDecimal customer, String explanation) {
      this(path, code, quantity, ids, finance, customer, explanation, null);
    }
    public MaterialLine(String path, String code, BigDecimal quantity, List<Long> ids,
        BigDecimal finance, BigDecimal customer, String explanation, ProductPartCostRule separateRule) {
      this(path, code, quantity, ids, finance, customer, explanation, separateRule, null);
    }
    public MaterialLine {
      path = MonthlyBomPricingScope.pathKey(path);
      materialCode = materialCode == null ? "" : materialCode.trim();
      if (quantity == null || quantity.signum() == 0)
        throw new IllegalArgumentException("Material identity and nonzero signed quantity required");
      monthlyPriceIds = List.copyOf(monthlyPriceIds);
      if (monthlyPriceIds.isEmpty() || monthlyPriceIds.stream().anyMatch(id -> id == null || id <= 0))
        throw new IllegalArgumentException("Material amounts require adopted price evidence");
      Objects.requireNonNull(financeAmount); Objects.requireNonNull(customerAmount);
      if (explanationOf != null) explanationOf = MonthlyBomPricingScope.pathKey(explanationOf);
      if (separateRule != null && explanationOf != null)
        throw new IllegalArgumentException("说明行不能作为独立零件重复计价");
      // Null is retained for old saved versions. New preparations carry their actual
      // consumption occurrences; shared unit-price IDs do not imply shared amounts.
      if (priceUsePaths != null) priceUsePaths = priceUsePaths.stream().map(MonthlyBomPricingScope::pathKey).toList();
    }
  }

  /** Immutable ordinary detail, including existing department modes and original source coordinates. */
  public record OrdinaryDetail(String costCode, String costName, BigDecimal baseAmount,
      BigDecimal rate, BigDecimal amount, String sourceTable, Long sourceId, String remark, String category,
      com.sanhua.marketingcost.dto.CostAmountComposition composition) {
    public OrdinaryDetail(String costCode, String costName, BigDecimal baseAmount, BigDecimal rate,
        BigDecimal amount, String sourceTable, Long sourceId, String remark, String category) {
      this(costCode, costName, baseAmount, rate, amount, sourceTable, sourceId, remark, category, null);
    }
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
          row.getAmount(), row.getSourceTable(), row.getSourceId(), row.getRemark(), row.getCategory(), row.getComposition());
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
      BigDecimal quantity, BigDecimal amount, List<String> paths, BigDecimal customerPrice, BigDecimal customerAmount) {
    public HouseholdLine(long id,String material,String unit,String hash,BigDecimal price,BigDecimal h,BigDecimal sales,BigDecimal quantity,BigDecimal amount,List<String> paths) {
      this(id,material,unit,hash,price,h,sales,quantity,amount,paths,null,null);
    }
    public HouseholdLine { paths = List.copyOf(paths); }
    public BigDecimal effectiveCustomerPrice() { return customerPrice==null?price:customerPrice; }
    public BigDecimal effectiveCustomerAmount() { return customerAmount==null?amount:customerAmount; }
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

  /** Public monthly price coordinates are shareable; private PROFILE/source coordinates are not. */
  public record MaterialAmounts(String code, BigDecimal quantity, List<Long> monthlyPriceIds,
      BigDecimal financeAmount, BigDecimal customerAmount, boolean contributes, BigDecimal separateManufactureRate) {
    public MaterialAmounts(String code, BigDecimal quantity, List<Long> ids, BigDecimal finance,
        BigDecimal customer, boolean contributes) { this(code, quantity, ids, finance, customer, contributes, null); }
    public MaterialAmounts { monthlyPriceIds = monthlyPriceIds.stream().sorted().toList(); }
  }
  public record DetailAmounts(String code, String category, BigDecimal base, BigDecimal rate, BigDecimal amount) {}
  public record FeeAmounts(ProductFeeCode code, BigDecimal amount, ProductFeeContract.State state) {}
  public record HouseholdInput(long monthlyPriceId, String materialCode, String pricingUnit,
      String sourceEvidenceHash, BigDecimal price, BigDecimal householdRate, BigDecimal salesRate,
      BigDecimal quantity, BigDecimal customerPrice) {
    public HouseholdInput {
      if (customerPrice == null || customerPrice.signum() < 0)
        throw new IllegalArgumentException("Invalid household customer price");
    }
  }
  public record HouseholdAmount(long monthlyPriceId, BigDecimal amount, BigDecimal customerAmount) {}
  public record NumericInputs(List<MaterialAmounts> materials, BigDecimal nonBomMaterial,
      BigDecimal directLabor, BigDecimal indirectLabor, BigDecimal lossRate, BigDecimal manufactureRate,
      BigDecimal coefficient, BigDecimal managementRate, BigDecimal salesRate, BigDecimal financeRate,
      BigDecimal otherIndependentFees, List<DetailAmounts> details, List<HouseholdInput> household,
      List<FeeAmounts> fees) {
    public NumericInputs {
      materials = List.copyOf(materials); details = List.copyOf(details);
      household = List.copyOf(household); fees = List.copyOf(fees);
    }
  }
  public record Numbers(NumericInputs inputs, OrdinaryCostAmounts.Result ordinary,
      List<HouseholdAmount> household, BigDecimal financeMaterialCost, BigDecimal householdSubtotal,
      BigDecimal householdContribution, BigDecimal productFeeTotal, BigDecimal totalCost,
      BigDecimal cuMaterialAdjustment, BigDecimal finalQuoteAmount) {
    public Numbers { household = List.copyOf(household); }
  }

  // Exact addition of material contributions is independent of their private preparation keys.
  // Sort only the numerical comparison input; retain every occurrence and the consumer's own rows.
  private static final Comparator<MaterialAmounts> MATERIAL_AMOUNTS_ORDER = Comparator
      .comparing(MaterialAmounts::code).thenComparing(line -> line.monthlyPriceIds().toString())
      .thenComparing(MaterialAmounts::quantity).thenComparing(MaterialAmounts::financeAmount)
      .thenComparing(MaterialAmounts::customerAmount).thenComparing(MaterialAmounts::contributes)
      .thenComparing(MaterialAmounts::separateManufactureRate, Comparator.nullsFirst(Comparator.naturalOrder()));

  /** Validated immutable customer binding. Creating it does not execute the cost formula. */
  public static final class Resolved {
    private final MonthlyAdoptionPlan plan;
    private final Ordinary ordinary;
    private final EffectiveTechnicalDataInput technical;
    private final List<FeeLine> fees;
    private final NumericInputs inputs;
    private Resolved(MonthlyAdoptionPlan plan, Ordinary ordinary, EffectiveTechnicalDataInput technical,
        List<FeeLine> fees, List<HouseholdInput> household) {
      this.plan = plan; this.ordinary = ordinary; this.technical = technical; this.fees = List.copyOf(fees);
      inputs = new NumericInputs(ordinary.materials().stream().map(line -> new MaterialAmounts(
          line.materialCode(), line.quantity(), line.monthlyPriceIds(), line.financeAmount(),
          line.customerAmount(), line.explanationOf() == null,
          line.separateRule() == null ? null : line.separateRule().manufactureRate())).sorted(MATERIAL_AMOUNTS_ORDER).toList(), ordinary.nonBomMaterial(),
          ordinary.directLabor(), ordinary.indirectLabor(), ordinary.lossRate(), ordinary.manufactureRate(),
          ordinary.coefficient(), ordinary.managementRate(), ordinary.salesRate(), ordinary.financeRate(),
          ordinary.otherIndependentFees(), ordinary.details().stream().map(row -> new DetailAmounts(
              row.costCode(), row.category(), row.baseAmount(), row.rate(), row.amount())).toList(),
          household, fees.stream().map(fee -> new FeeAmounts(fee.code(), fee.amount(), fee.state())).toList());
    }
    public NumericInputs inputs() { return inputs; }
    private List<HouseholdLine> bindHousehold(List<HouseholdAmount> numbers) {
      if (numbers.size() != inputs.household().size()) throw new IllegalArgumentException("Shared household scope differs");
      var pathsByPrice = new HashMap<Long, List<String>>();
      var separatePaths = ordinary.materials().stream().filter(line -> line.separateRule() != null)
          .map(MaterialLine::path).collect(java.util.stream.Collectors.toSet());
      for (var use : plan.uses()) {
        var selected = plan.selections().get(use.request());
        if (selected.fact().type() == PricingType.HOUSEHOLD_INQUIRY && !separatePaths.contains(MonthlyBomPricingScope.pathKey(use.path())))
          pathsByPrice.computeIfAbsent(selected.monthlyPriceId(), ignored -> new ArrayList<>()).add(use.path());
      }
      var result = new ArrayList<HouseholdLine>();
      for (int i = 0; i < numbers.size(); i++) {
        var number = numbers.get(i); var input = inputs.household().get(i);
        if (number.monthlyPriceId() != input.monthlyPriceId())
          throw new IllegalArgumentException("Shared household identity differs");
        var paths = pathsByPrice.get(input.monthlyPriceId()).stream().sorted().toList();
        result.add(new HouseholdLine(input.monthlyPriceId(), input.materialCode(), input.pricingUnit(),
            input.sourceEvidenceHash(), input.price(), input.householdRate(), input.salesRate(), input.quantity(), number.amount(), paths, input.customerPrice(), number.customerAmount()));
      }
      return List.copyOf(result);
    }
    public Result bind(Numbers numbers) {
      if (!CalculationInputEquality.same(inputs, numbers.inputs()))
        throw new IllegalArgumentException("Shared monthly calculation inputs differ from this consumer");
      return new Result(ALGORITHM_VERSION, ProductFeeContract.POLICY_VERSION, ordinary, numbers.ordinary(),
          plan.selections().entrySet().stream()
              .sorted(Comparator.comparing(entry -> entry.getKey().family().canonical() + entry.getKey().priceOrgCode() + entry.getKey().conditions()))
              .map(entry -> new AdoptedPrice(entry.getKey(), entry.getValue())).toList(), plan.uses(), plan.suppressedPaths(),
          bindHousehold(numbers.household()), fees, numbers.financeMaterialCost(), numbers.householdSubtotal(),
          numbers.householdContribution(), numbers.productFeeTotal(), numbers.totalCost(), numbers.cuMaterialAdjustment(),
          numbers.finalQuoteAmount(), technical);
    }
  }

  public static Result calculate(MonthlyAdoptionPlan plan, Ordinary input,
      EffectiveTechnicalDataInput technical) {
    return calculate(plan, input, technical, Map.of());
  }

  public static Result calculate(MonthlyAdoptionPlan plan, Ordinary input,
      EffectiveTechnicalDataInput technical, Map<Long, BigDecimal> householdCustomerPrices) {
    var resolved = resolve(plan, input, technical, householdCustomerPrices);
    return resolved.bind(calculate(resolved.inputs()));
  }

  public static Resolved resolve(MonthlyAdoptionPlan plan, Ordinary input, EffectiveTechnicalDataInput technical) {
    return resolve(plan, input, technical, Map.of());
  }

  public static Resolved resolve(MonthlyAdoptionPlan plan, Ordinary input,
      EffectiveTechnicalDataInput technical, Map<Long, BigDecimal> householdCustomerPrices) {
    Objects.requireNonNull(householdCustomerPrices);
    Objects.requireNonNull(plan); Objects.requireNonNull(input);
    validateScope(plan, input.materials());
    var fees = fees(technical);
    var separatePaths = input.materials().stream().filter(line -> line.separateRule() != null)
        .map(MaterialLine::path).collect(java.util.stream.Collectors.toSet());
    var groups = new TreeMap<Long, List<Use>>();
    for (var use : plan.uses()) {
      var price = plan.selections().get(use.request());
      if (price.fact().type() == PricingType.HOUSEHOLD_INQUIRY && !separatePaths.contains(MonthlyBomPricingScope.pathKey(use.path())))
        groups.computeIfAbsent(price.monthlyPriceId(), ignored -> new ArrayList<>()).add(use);
    }
    var household = new ArrayList<HouseholdInput>();
    for (var entry : groups.entrySet()) {
      var uses = entry.getValue(); var first = uses.getFirst(); var fact = plan.selections().get(first.request()).fact();
      BigDecimal quantity = uses.stream().map(Use::cumulativeQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
      household.add(new HouseholdInput(entry.getKey(), first.request().family().materialCode(),
          first.request().family().unit(), fact.evidence().sha256(), fact.price(), fact.householdRate(),
          input.salesRate(), quantity, householdCustomerPrices.getOrDefault(entry.getKey(), fact.price())));
    }
    return new Resolved(plan, input, technical, fees, household);
  }

  /** Original arithmetic and rounding, executed once per exact resolved input. No customer sources. */
  public static Numbers calculate(NumericInputs input) {
    BigDecimal material = input.nonBomMaterial();
    BigDecimal difference = BigDecimal.ZERO;
    BigDecimal separateTotal = BigDecimal.ZERO;
    for (var line : input.materials()) if (line.contributes()) {
      if (line.separateManufactureRate() != null) {
        var financial = SeparatePartCost.amount(line.financeAmount(), input.lossRate(), line.separateManufactureRate(),
            input.managementRate(), input.salesRate(), input.financeRate());
        var customer = SeparatePartCost.amount(line.customerAmount(), input.lossRate(), line.separateManufactureRate(),
            input.managementRate(), input.salesRate(), input.financeRate());
        separateTotal = separateTotal.add(financial);
        difference = difference.add(customer.subtract(financial));
        continue;
      }
      material = material.add(OrdinaryCostAmounts.money(line.financeAmount()));
      difference = difference.add(line.customerAmount().subtract(line.financeAmount()));
    }
    material = OrdinaryCostAmounts.money(material);
    var amounts = OrdinaryCostAmounts.calculate(material, input.directLabor(), input.indirectLabor(),
        input.lossRate(), input.manufactureRate(), input.coefficient(), input.managementRate(),
        input.salesRate(), input.financeRate(), input.otherIndependentFees());
    var household = new ArrayList<HouseholdAmount>();
    BigDecimal subtotal = BigDecimal.ZERO;
    for (var line : input.household()) {
      BigDecimal amount = line.price().multiply(BigDecimal.ONE.add(line.householdRate()).add(input.salesRate()))
          .multiply(line.quantity()).divide(BigDecimal.ONE.add(line.householdRate()), 6, RoundingMode.HALF_UP);
      BigDecimal customerAmount = line.customerPrice().multiply(BigDecimal.ONE.add(line.householdRate()).add(input.salesRate()))
          .multiply(line.quantity()).divide(BigDecimal.ONE.add(line.householdRate()), 6, RoundingMode.HALF_UP);
      difference = difference.add(customerAmount.subtract(amount));
      household.add(new HouseholdAmount(line.monthlyPriceId(), amount, customerAmount));
      subtotal = subtotal.add(amount);
    }
    subtotal = OrdinaryCostAmounts.money(subtotal);
    BigDecimal contribution = OrdinaryCostAmounts.money(subtotal.multiply(input.coefficient()));
    BigDecimal feeTotal = input.fees().stream().map(FeeAmounts::amount).filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    feeTotal = OrdinaryCostAmounts.money(feeTotal);
    BigDecimal total = OrdinaryCostAmounts.money(amounts.total().add(contribution).add(feeTotal).add(separateTotal));
    difference = OrdinaryCostAmounts.money(difference);
    return new Numbers(input, amounts, household, material, subtotal, contribution, feeTotal, total, difference,
        OrdinaryCostAmounts.money(total.add(difference)));
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
        if (householdPaths.contains(path) && !(row.separateRule() != null && row.path().equals(path)))
          throw new IllegalArgumentException("Household scope must be excluded before ordinary costing");
        if (suppressedPaths.contains(path)) throw new IllegalArgumentException("Household scope includes a suppressed path");
      }
      for (long id : row.monthlyPriceIds()) if (!ids.containsKey(id)
          || row.separateRule() == null && ids.get(id).fact().type() != PricingType.NORMAL)
        throw new IllegalArgumentException("Ordinary amounts must reference adopted NORMAL facts");
    }
    var contributing = new HashMap<String, MaterialLine>();
    for (var row : materials) if (row.explanationOf() == null) contributing.put(row.path(), row);
    var actualUses = new HashMap<String, MonthlyAdoptionPlan.Use>();
    plan.uses().forEach(use -> actualUses.put(MonthlyBomPricingScope.pathKey(use.path()), use));
    var useOwners = new HashMap<String, MaterialLine>();
    for (var row : contributing.values()) if (row.priceUsePaths() != null) {
      for (String path : row.priceUsePaths()) {
        var use = actualUses.get(path);
        if (use == null || !inside(path, row.path())
            || !row.monthlyPriceIds().contains(plan.selections().get(use.request()).monthlyPriceId()))
          throw new IllegalArgumentException("材料行采用范围与保存依据不一致：" + row.materialCode());
        var previous = useOwners.putIfAbsent(path, row);
        if (previous != null)
          throw new IllegalArgumentException("材料成本重复计入：" + previous.materialCode() + "与" + row.materialCode());
      }
    }
    for (var row : materials) {
      if (row.explanationOf() != null) {
        var parent = contributing.get(row.explanationOf());
        if (parent == null || !inside(row.path(), parent.path()) || row.path().equals(parent.path()))
          throw new IllegalArgumentException("Explanation requires its contributing settlement parent");
      } else for (String ancestor = parent(row.path()); ancestor != null; ancestor = parent(ancestor)) {
        // New preparation scopes above prove occurrence ownership. BOM ancestry alone
        // is not overlap: a MAKE/PACK parent can exclude separately priced descendants.
        // Preserve the existing conservative check for old rows without that evidence.
        var parent = contributing.get(ancestor);
        if (parent != null && !uses.contains(ancestor)
            && (parent.priceUsePaths() == null || row.priceUsePaths() == null))
          throw new IllegalArgumentException("材料成本重复计入：" + parent.materialCode() + "与下级" + row.materialCode());
      }
    }
    for (var use : plan.uses()) {
      String path = MonthlyBomPricingScope.pathKey(use.path());
      var selection = plan.selections().get(use.request());
      for (String ancestor = parent(path); ancestor != null; ancestor = parent(ancestor)) {
        if (householdPaths.contains(ancestor)) throw new IllegalArgumentException("Adopted use below household cut");
      }
      if (selection.fact().type() == PricingType.NORMAL) {
        MaterialLine settlement = useOwners.get(path);
        for (String ancestor = path; settlement == null && ancestor != null; ancestor = parent(ancestor)) {
          var candidate = contributing.get(ancestor);
          if (candidate != null && candidate.priceUsePaths() == null) { settlement = candidate; break; }
        }
        if (settlement == null || !settlement.monthlyPriceIds().contains(selection.monthlyPriceId()))
          throw new IllegalArgumentException("Ordinary use is missing from costing: " + use.path());
        if (settlement.path().equals(path) && (!use.request().matchesBomMaterialCode(settlement.materialCode())
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
