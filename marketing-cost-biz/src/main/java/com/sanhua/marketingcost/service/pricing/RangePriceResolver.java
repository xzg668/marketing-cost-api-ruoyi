package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.FinanceEffectivePrice;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 区间价 Resolver —— 查 lp_price_range_item，按数量段命中单价。
 *
 * <p>制造件原材料/废料取单位价时没有采购数量上下文，调用方会传 partQty=1。
 */
@Component
public class RangePriceResolver implements PriceResolver {
  private static final String RANGE_BASIS_QTY = "QTY";
  private static final String RANGE_BASIS_FACTOR = "FACTOR";

  private final PriceRangeItemMapper priceRangeItemMapper;
  private final PriceRangeFactorRuleMapper factorRuleMapper;
  private final OaFormMapper oaFormMapper;
  private final SupplierPreferredPriceSelector supplierPreferredPriceSelector;
  private final MaterialPriceMonthSelector monthSelector;
  private final FinanceEffectivePriceService effectivePrices;
  private final FinanceEffectivePriceSelectionService effectiveSelection;
  private final SupplierPriceSelectionService supplierPrices;
  private final com.sanhua.marketingcost.service.impl.OriginalRangePriceRuntime originalRuntime;

  @org.springframework.beans.factory.annotation.Autowired
  public RangePriceResolver(
      PriceRangeItemMapper priceRangeItemMapper,
      PriceRangeFactorRuleMapper factorRuleMapper,
      OaFormMapper oaFormMapper,
      SupplierPreferredPriceSelector supplierPreferredPriceSelector,
      MaterialPriceMonthSelector monthSelector,
      com.sanhua.marketingcost.service.impl.OriginalRangePriceRuntime originalRuntime,
      FinanceEffectivePriceService effectivePrices,
      FinanceEffectivePriceSelectionService effectiveSelection,
      SupplierPriceSelectionService supplierPrices) {
    this.priceRangeItemMapper = priceRangeItemMapper;
    this.monthSelector = monthSelector;
    this.originalRuntime = originalRuntime;
    this.effectivePrices = effectivePrices;
    this.effectiveSelection = effectiveSelection;
    this.supplierPrices = supplierPrices;
    this.factorRuleMapper = factorRuleMapper;
    this.oaFormMapper = oaFormMapper;
    this.supplierPreferredPriceSelector =
        Objects.requireNonNull(
            supplierPreferredPriceSelector, "supplierPreferredPriceSelector 不能为空");
  }

  @Override
  public PriceTypeEnum priceType() {
    return PriceTypeEnum.RANGE;
  }

  @Override
  public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
    return resolve(oaNo, item, route, null);
  }

  @Override
  public PriceResolveResult resolve(
      String oaNo, CostRunPartItemDto item, PriceTypeRoute route, CostRunContext context) {
    return resolve(oaNo, item, route, context, null);
  }

  private PriceResolveResult resolve(
      String oaNo,
      CostRunPartItemDto item,
      PriceTypeRoute route,
      CostRunContext context,
      BatchInputs batch) {
    String code = item == null ? null : item.getPartCode();
    if (!StringUtils.hasText(code)) {
      return PriceResolveResult.miss("partCode 为空，无法查区间价");
    }
    BigDecimal qty = item.getPartQty() == null ? BigDecimal.ONE : item.getPartQty();
    LocalDate priceDate = pricingDate(route, context);
    List<PriceRangeFactorRule> rules =
        batch == null
            ? findFactorRules(code.trim(), context)
            : batch.rules().getOrDefault(code.trim(), List.of());
    if (rules.stream().anyMatch(rule -> StringUtils.hasText(rule.getPricingMonth()))) {
      return resolveMonthlyFactorRange(oaNo, code.trim(), rules, priceDate, context, batch);
    }
    // 未迁移的旧行情规则没有导入月份；不能从历史生效日伪造月份。
    PriceRangeFactorRule factorRule =
        rules.stream()
            .filter(rule -> Integer.valueOf(1).equals(rule.getCurrentFlag()))
            .findFirst()
            .orElse(null);
    if (factorRule != null) {
      return resolveFactorRange(oaNo, code.trim(), factorRule, priceDate, context, null, batch);
    }
    return resolveQtyRange(code.trim(), qty, priceDate, context);
  }

  private record BatchScope(
      String businessUnit, String organization, String month, LocalDate date) {}

  private record BatchInputs(
      java.util.Map<String, List<PriceRangeFactorRule>> rules,
      java.util.Map<String, PriceMonthSelection> months,
      java.util.Map<String, com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult>
          suppliers,
      com.sanhua.marketingcost.service.impl.OriginalRangePriceRuntime.PreparedInputs original,
      java.util.Map<Long, FinanceEffectivePrice> effective,
      java.util.Map<String, List<SupplierSupplyRatio>> ratios,
      String effectiveError) {}

  @Override
  public java.util.Map<PriceResolveRequest, PriceResolveResult> resolveBatch(
      String oaNo, List<PriceResolveRequest> requests) {
    var result = new java.util.LinkedHashMap<PriceResolveRequest, PriceResolveResult>();
    var groups = new java.util.LinkedHashMap<BatchScope, List<PriceResolveRequest>>();
    for (var request : requests) {
      var run = request.context();
      if (run == null
          || request.item() == null
          || !StringUtils.hasText(request.item().getPartCode())) {
        result.put(request, resolve(oaNo, request.item(), request.route(), run));
        continue;
      }
      var key =
          new BatchScope(
              run.getBusinessUnitType(),
              run.getPriceOrgCode(),
              run.getPricingMonth(),
              pricingDate(request.route(), run));
      groups.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(request);
    }
    OaForm form =
        StringUtils.hasText(oaNo) && !groups.isEmpty()
            ? oaFormMapper.selectOne(
                Wrappers.<OaForm>lambdaQuery().eq(OaForm::getOaNo, oaNo).last("LIMIT 1"))
            : null;
    for (var group : groups.entrySet()) {
      var codes =
          group.getValue().stream()
              .map(r -> r.item().getPartCode().trim())
              .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
      var rules = findFactorRules(codes, group.getValue().getFirst().context());
      var ruleMap =
          rules.stream()
              .collect(
                  java.util.stream.Collectors.groupingBy(PriceRangeFactorRule::getMaterialCode));
      var nativeIds =
          rules.stream()
              .filter(com.sanhua.marketingcost.service.impl.OriginalRangeRuleSnapshot::isOriginal)
              .map(PriceRangeFactorRule::getId)
              .toList();
      java.util.Map<Long, List<PriceRangeItem>> bands =
          nativeIds.isEmpty()
              ? java.util.Map.of()
              : priceRangeItemMapper
                  .selectList(
                      Wrappers.<PriceRangeItem>lambdaQuery()
                          .in(PriceRangeItem::getFactorRuleId, nativeIds))
                  .stream()
                  .collect(java.util.stream.Collectors.groupingBy(PriceRangeItem::getFactorRuleId));
      boolean monthlyReprice =
          CostRunContext.SCENE_MONTHLY_REPRICE.equals(
              group.getValue().getFirst().context().getScene());
      var legacyCodes =
          rules.stream()
              .filter(
                  rule ->
                      monthlyReprice
                          || !com.sanhua.marketingcost.service.impl.OriginalRangeRuleSnapshot
                              .isOriginal(rule))
              .map(PriceRangeFactorRule::getMaterialCode)
              .collect(java.util.stream.Collectors.toSet());
      var supplierInputs =
          legacyCodes.isEmpty()
              ? java.util.Map
                  .<String, com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult>of()
              : supplierPreferredPriceSelector.prepareSuppliers(
                  group.getKey().businessUnit(), legacyCodes, group.getKey().date());
      java.util.Map<Long, FinanceEffectivePrice> effective = java.util.Map.of();
      String effectiveError = null;
      if (!nativeIds.isEmpty() && !monthlyReprice) {
        try {
          var first =
              rules.stream()
                  .filter(
                      com.sanhua.marketingcost.service.impl.OriginalRangeRuleSnapshot::isOriginal)
                  .findFirst()
                  .orElseThrow();
          effective =
              effectivePrices.read(
                  "RANGE",
                  nativeIds,
                  originalRuntime.financialSnapshot(first, group.getKey().month()));
        } catch (IllegalArgumentException failure) {
          effectiveError = failure.getMessage();
        }
      }
      var multipleCodes =
          ruleMap.entrySet().stream()
              .filter(
                  e ->
                      e.getValue().stream()
                              .filter(
                                  com.sanhua.marketingcost.service.impl.OriginalRangeRuleSnapshot
                                      ::isOriginal)
                              .map(PriceRangeFactorRule::getSupplierCode)
                              .distinct()
                              .count()
                          > 1)
              .map(java.util.Map.Entry::getKey)
              .collect(java.util.stream.Collectors.toSet());
      var ratios =
          monthlyReprice || multipleCodes.isEmpty()
              ? java.util.Map.<String, List<SupplierSupplyRatio>>of()
              : supplierPrices.prepareRatios(
                  group.getKey().businessUnit(), multipleCodes, group.getKey().date());
      var batch =
          new BatchInputs(
              ruleMap,
              new java.util.HashMap<>(),
              supplierInputs,
              new com.sanhua.marketingcost.service.impl.OriginalRangePriceRuntime.PreparedInputs(
                  form, bands),
              effective,
              ratios,
              effectiveError);
      for (var request : group.getValue())
        result.put(
            request, resolve(oaNo, request.item(), request.route(), request.context(), batch));
    }
    return result;
  }

  private PriceResolveResult resolveFactorRange(
      String oaNo,
      String materialCode,
      PriceRangeFactorRule factorRule,
      LocalDate priceDate,
      CostRunContext context,
      SupplierPreferredPriceSelection<PriceRangeFactorRule> selectedRule,
      BatchInputs batch) {
    if (com.sanhua.marketingcost.service.impl.OriginalRangeRuleSnapshot.isOriginal(factorRule)) {
      if (originalRuntime == null) throw new IllegalStateException("区间原稿运行时未配置");
      return originalRuntime.resolve(
          factorRule, oaNo, context, selectedRule, batch == null ? null : batch.original());
    }
    BigDecimal factorValue = resolveFactorValue(oaNo, factorRule);
    if (factorValue == null) {
      return PriceResolveResult.miss(
          "FACTOR_INPUT_MISSING",
          "行情因素区间价缺少报价单行情值: " + factorRule.getFactorCode() + ", material=" + materialCode);
    }
    var query =
        Wrappers.lambdaQuery(PriceRangeItem.class)
            .eq(PriceRangeItem::getRangeBasis, RANGE_BASIS_FACTOR)
            .eq(PriceRangeItem::getFactorRuleId, factorRule.getId());
    if (priceDate != null && !StringUtils.hasText(factorRule.getPricingMonth())) {
      query.and(
          q ->
              q.le(PriceRangeItem::getEffectiveFrom, priceDate)
                  .or()
                  .isNull(PriceRangeItem::getEffectiveFrom));
    }
    List<PriceRangeItem> queriedRows =
        priceRangeItemMapper.selectList(
            query
                .orderByDesc(PriceRangeItem::getEffectiveFrom)
                .orderByDesc(PriceRangeItem::getCreatedAt)
                .orderByDesc(PriceRangeItem::getId));
    List<PriceRangeItem> rows =
        queriedRows == null ? List.of() : queriedRows.stream().filter(Objects::nonNull).toList();
    SupplierPreferredPriceSelection<PriceRangeItem> selection =
        selectedRule == null
            ? selectPreferredFactorPrice(rows, materialCode, factorRule, priceDate, context)
            : new SupplierPreferredPriceSelection<>(
                null,
                selectedRule.traceMessage(),
                selectedRule.candidateSupplierCount(),
                selectedRule.mainSupplierName(),
                selectedRule.mainSupplierCode(),
                selectedRule.supplyRatio(),
                selectedRule.supplyRatioRecordId(),
                selectedRule.matchMode(),
                selectedRule.failureCode(),
                selectedRule.failureMessage());
    return resolveSelectedFactorRange(
        materialCode, factorRule, factorValue, priceDate, rows, selection);
  }

  /** 同一规则、供应商和财务快照分别计算P0/P1；材料规则的月份选择由调用方先完成。 */
  public FactorScenarioPrices resolveFactorScenarios(
      PriceRangeFactorRule rule,
      List<PriceRangeItem> rows,
      PriceFactorContext financial,
      BigDecimal customerCopperPerTon,
      LocalDate priceDate) {
    Objects.requireNonNull(rule, "区间规则必填");
    Objects.requireNonNull(financial, "财务输入必填");
    var scope = financial.financial().scope();
    if (!financial.materialCode().equals(rule.getMaterialCode())
        || !scope.businessUnitType().equals(rule.getBusinessUnitType())
        || !scope.priceOrgCode().equals(rule.getOrgCode())) {
      throw new IllegalArgumentException("区间规则与因素输入的料号、业务单元或组织不一致：" + financial.materialCode());
    }
    var months =
        new PriceMonthSelection(financial.months().requestedMonth(), rule.getPricingMonth());
    var selectedRows = List.copyOf(rows);
    if (selectedRows.stream()
        .anyMatch(
            row ->
                !Objects.equals(rule.getId(), row.getFactorRuleId())
                    || !rule.getMaterialCode().equals(row.getMaterialCode())
                    || !scope.businessUnitType().equals(row.getBusinessUnitType()))) {
      throw new IllegalArgumentException("区间明细不属于选定规则：" + rule.getId());
    }
    var selection =
        selectPreferredFactorPrice(selectedRows, financial.materialCode(), rule, priceDate, null);
    var p0 = financial.financialOnly();
    var p1 = p0.withCustomerCopperPerTon(customerCopperPerTon);
    String factorKey = rule.getFactorCode();
    if ("Cu".equalsIgnoreCase(factorKey) || "1#Cu".equalsIgnoreCase(factorKey)) {
      factorKey = PriceFactorContext.COPPER;
    }
    var unit = FactorPriceUnit.require(rule.getFactorUnit());
    var financialResult =
        resolveSelectedFactorRange(
            financial.materialCode(),
            rule,
            p0.requiredPrice(factorKey, unit, true),
            priceDate,
            selectedRows,
            selection);
    var customerResult =
        resolveSelectedFactorRange(
            financial.materialCode(),
            rule,
            p1.requiredPrice(factorKey, unit, true),
            priceDate,
            selectedRows,
            selection);
    return new FactorScenarioPrices(rule.getId(), months, p0, p1, financialResult, customerResult);
  }

  public record FactorScenarioPrices(
      Long ruleId,
      PriceMonthSelection ruleMonths,
      PriceFactorContext financialInputs,
      PriceFactorContext customerInputs,
      PriceResolveResult financialPrice,
      PriceResolveResult customerPrice) {}

  /** Reuses the existing band calculation with captured inputs; performs no source or OA reads. */
  public PriceResolveResult resolveFactorPrepared(
      PriceRangeFactorRule rule,
      List<PriceRangeItem> rows,
      BigDecimal factorValue,
      LocalDate priceDate,
      SupplierPreferredPriceSelection<PriceRangeItem> selection) {
    Objects.requireNonNull(rule, "区间规则必填");
    Objects.requireNonNull(factorValue, "行情因素值必填");
    Objects.requireNonNull(selection, "供应商选择依据必填");
    var captured = List.copyOf(rows);
    if (captured.stream().anyMatch(row ->
        !Objects.equals(rule.getId(), row.getFactorRuleId())
            || !Objects.equals(rule.getMaterialCode(), row.getMaterialCode())
            || !Objects.equals(rule.getBusinessUnitType(), row.getBusinessUnitType()))) {
      throw new IllegalArgumentException("区间明细不属于选定规则：" + rule.getId());
    }
    return resolveSelectedFactorRange(
        rule.getMaterialCode(), rule, factorValue, priceDate, captured, selection);
  }

  private PriceResolveResult resolveSelectedFactorRange(
      String materialCode,
      PriceRangeFactorRule factorRule,
      BigDecimal factorValue,
      LocalDate priceDate,
      List<PriceRangeItem> rows,
      SupplierPreferredPriceSelection<PriceRangeItem> selection) {
    if (selection.failed()) {
      return PriceResolveResult.miss(selection.failureCode(), selection.failureMessage());
    }
    PriceRangeItem row =
        rows.stream()
            .filter(
                candidate ->
                    selection.matchesSupplier(
                        candidate.getSupplierName(), candidate.getSupplierCode()))
            .filter(candidate -> matchesRange(candidate, factorValue))
            .findFirst()
            .orElse(null);
    if (row == null) {
      return PriceResolveResult.miss(
          "RANGE_BAND_MISSING",
          "行情因素区间价未命中当前区间: material="
              + materialCode
              + ", factor="
              + factorRule.getFactorCode()
              + ", value="
              + format(factorValue)
              + ", supplier="
              + selection.mainSupplierCode()
              + "/"
              + selection.mainSupplierName());
    }
    if (row.getPriceExclTax() == null) {
      return PriceResolveResult.miss(
          "RANGE_PRICE_MISSING",
          "行情因素区间价缺少不含税价: material=" + materialCode + ", factor=" + factorRule.getFactorCode());
    }
    String trace =
        "行情区间命中("
            + factorRule.getFactorCode()
            + "="
            + format(factorValue)
            + ",range="
            + format(row.getRangeLow())
            + "-"
            + format(row.getRangeHigh())
            + ",field=price_excl_tax)"
            + selectionTrace(selection.traceMessage())
            + pricingLedger(materialCode, factorRule, factorValue, row, selection);
    return resolved(row.getPriceExclTax(), row, trace, priceDate, selection.withRow(row));
  }

  private SupplierPreferredPriceSelection<PriceRangeItem> selectPreferredFactorPrice(
      List<PriceRangeItem> rows,
      String materialCode,
      PriceRangeFactorRule factorRule,
      LocalDate priceDate,
      CostRunContext context) {
    String businessUnitType = context == null ? null : trimToNull(context.getBusinessUnitType());
    if (businessUnitType == null) {
      businessUnitType = trimToNull(factorRule.getBusinessUnitType());
    }
    if (businessUnitType == null) {
      businessUnitType = firstText(rows, PriceRangeItem::getBusinessUnitType);
    }
    return supplierPreferredPriceSelector.select(
        rows,
        businessUnitType,
        materialCode,
        firstText(rows, PriceRangeItem::getMaterialName),
        firstText(rows, PriceRangeItem::getSpecModel),
        priceDate,
        PriceRangeItem::getSupplierName,
        PriceRangeItem::getSupplierCode);
  }

  private String selectionTrace(String traceMessage) {
    return StringUtils.hasText(traceMessage) ? ",supplierSelection=" + traceMessage.trim() : "";
  }

  private String pricingLedger(
      String materialCode,
      PriceRangeFactorRule factorRule,
      BigDecimal factorValue,
      PriceRangeItem row,
      SupplierPreferredPriceSelection<PriceRangeItem> selection) {
    return "；区间价取价底稿["
        + "物料代码="
        + text(materialCode)
        + "；影响因素代码="
        + text(factorRule == null ? null : factorRule.getFactorCode())
        + "；报价单行情值="
        + format(factorValue)
        + "；命中区间="
        + format(row == null ? null : row.getRangeLow())
        + "-"
        + format(row == null ? null : row.getRangeHigh())
        + "；候选供应商数量="
        + selection.candidateSupplierCount()
        + "；主供应商名称="
        + text(selection.mainSupplierName())
        + "；主供应商代码="
        + text(selection.mainSupplierCode())
        + "；供货比例="
        + format(selection.supplyRatio())
        + "；供应商匹配方式="
        + matchModeLabel(selection.matchMode())
        + "；最终价格行ID="
        + (row == null || row.getId() == null ? "" : row.getId())
        + "；最终不含税单价="
        + format(row == null ? null : row.getPriceExclTax())
        + "]";
  }

  private String matchModeLabel(String matchMode) {
    return switch (text(matchMode)) {
      case "CODE" -> "供应商代码";
      case "NAME_FALLBACK" -> "供应商名称兜底";
      case "SINGLE_SUPPLIER" -> "单一供应商";
      case "NO_SUPPLIER_DIMENSION" -> "无供应商维度";
      default -> "";
    };
  }

  private String text(String value) {
    return value == null ? "" : value.trim();
  }

  private String firstText(
      List<PriceRangeItem> rows, java.util.function.Function<PriceRangeItem, String> getter) {
    for (PriceRangeItem row : rows) {
      String value = getter.apply(row);
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private PriceResolveResult resolveQtyRange(
      String materialCode, BigDecimal qty, LocalDate priceDate, CostRunContext context) {
    var query =
        Wrappers.lambdaQuery(PriceRangeItem.class)
            .eq(PriceRangeItem::getMaterialCode, materialCode)
            .and(
                q ->
                    q.eq(PriceRangeItem::getRangeBasis, RANGE_BASIS_QTY)
                        .or()
                        .isNull(PriceRangeItem::getRangeBasis))
            .and(
                q ->
                    q.isNotNull(PriceRangeItem::getPriceInclTax)
                        .or()
                        .isNotNull(PriceRangeItem::getPriceExclTax));
    if (priceDate != null) {
      query.and(
          q ->
              q.le(PriceRangeItem::getEffectiveFrom, priceDate)
                  .or()
                  .isNull(PriceRangeItem::getEffectiveFrom));
    }
    if (context == null
        || !StringUtils.hasText(context.getPriceOrgCode())
        || !StringUtils.hasText(context.getBusinessUnitType())) {
      return PriceResolveResult.miss("RANGE_PRICE_SCOPE_MISSING", "区间价缺少取价组织或业务单元");
    }
    query
        .eq(PriceRangeItem::getOrgCode, context.getPriceOrgCode())
        .eq(PriceRangeItem::getBusinessUnitType, context.getBusinessUnitType());
    List<PriceRangeItem> rows =
        priceRangeItemMapper.selectList(
            query
                .orderByDesc(PriceRangeItem::getEffectiveFrom)
                .orderByDesc(PriceRangeItem::getCreatedAt)
                .orderByDesc(PriceRangeItem::getId));
    List<PriceRangeItem> matched =
        rows == null ? List.of() : rows.stream().filter(row -> matchesRange(row, qty)).toList();
    if (matched.isEmpty()) {
      return PriceResolveResult.miss("lp_price_range_item 无有效区间价: " + materialCode);
    }
    SupplierPreferredPriceSelection<PriceRangeItem> selection =
        selectPreferredQtyPrice(matched, materialCode, priceDate, context);
    if (selection.failed()) {
      return PriceResolveResult.miss(selection.failureCode(), selection.failureMessage());
    }
    PriceRangeItem row = selection.row();
    if (row == null) {
      return PriceResolveResult.miss("lp_price_range_item 无可用供应商区间价: " + materialCode);
    }
    BigDecimal price =
        row.getPriceInclTax() != null ? row.getPriceInclTax() : row.getPriceExclTax();
    String field = row.getPriceInclTax() != null ? "price_incl_tax" : "price_excl_tax";
    String trace =
        "区间命中("
            + format(row.getRangeLow())
            + "-"
            + format(row.getRangeHigh())
            + ",qty="
            + format(qty)
            + ",field="
            + field
            + ")"
            + selectionTrace(selection.traceMessage());
    return resolved(price, row, trace, priceDate, selection);
  }

  private SupplierPreferredPriceSelection<PriceRangeItem> selectPreferredQtyPrice(
      List<PriceRangeItem> rows, String materialCode, LocalDate priceDate, CostRunContext context) {
    String businessUnitType = context == null ? null : trimToNull(context.getBusinessUnitType());
    if (businessUnitType == null) {
      businessUnitType = firstText(rows, PriceRangeItem::getBusinessUnitType);
    }
    return supplierPreferredPriceSelector.select(
        rows,
        businessUnitType,
        materialCode,
        firstText(rows, PriceRangeItem::getMaterialName),
        firstText(rows, PriceRangeItem::getSpecModel),
        priceDate,
        PriceRangeItem::getSupplierName,
        PriceRangeItem::getSupplierCode);
  }

  private PriceResolveResult resolved(
      BigDecimal price,
      PriceRangeItem row,
      String trace,
      LocalDate priceDate,
      SupplierPreferredPriceSelection<PriceRangeItem> selection) {
    PriceResolveEvidence evidence =
        PriceResolveEvidenceFactory.create(
            row.getId(),
            row.getImportBatchNo(),
            selection.mainSupplierName(),
            selection.mainSupplierCode(),
            selection.supplyRatio(),
            selection.supplyRatioRecordId(),
            row.getEffectiveFrom(),
            row.getEffectiveTo(),
            priceDate);
    String warning = evidence.warningMessage();
    String remark = StringUtils.hasText(warning) ? trace + "；" + warning : trace;
    return PriceResolveResult.hit(price, "区间价", remark, row.getId(), evidence);
  }

  private List<PriceRangeFactorRule> findFactorRules(String materialCode, CostRunContext context) {
    return findFactorRules(java.util.Set.of(materialCode), context);
  }

  private List<PriceRangeFactorRule> findFactorRules(
      java.util.Set<String> materialCodes, CostRunContext context) {
    if (factorRuleMapper == null || materialCodes.isEmpty()) return List.of();
    var query =
        Wrappers.lambdaQuery(PriceRangeFactorRule.class)
            .in(PriceRangeFactorRule::getMaterialCode, materialCodes)
            .in(PriceRangeFactorRule::getRuleStatus, List.of("LEGACY", "PUBLISHED"));
    String businessUnit = context == null ? null : trimToNull(context.getBusinessUnitType());
    if (businessUnit == null) query.isNull(PriceRangeFactorRule::getBusinessUnitType);
    else query.eq(PriceRangeFactorRule::getBusinessUnitType, businessUnit);
    String org = context == null ? null : trimToNull(context.getPriceOrgCode());
    if (org != null) {
      // 未迁移的旧行情表没有组织；仅该历史分支暂留，正式月规则必须精确匹配组织。
      query.and(
          q ->
              q.eq(PriceRangeFactorRule::getOrgCode, org)
                  .or(
                      legacy ->
                          legacy
                              .isNull(PriceRangeFactorRule::getPricingMonth)
                              .isNull(PriceRangeFactorRule::getOrgCode)
                              .eq(PriceRangeFactorRule::getRuleStatus, "LEGACY")));
    }
    List<PriceRangeFactorRule> rules = factorRuleMapper.selectList(query);
    if (rules == null) return List.of();
    // 原稿快照可能很大，避免 MySQL 对完整 JSON 行做文件排序。
    return rules.stream()
        .sorted(
            java.util.Comparator.comparing(
                    PriceRangeFactorRule::getVersionNo,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                .thenComparing(
                    PriceRangeFactorRule::getId,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
        .toList();
  }

  private PriceResolveResult resolveMonthlyFactorRange(
      String oaNo,
      String material,
      List<PriceRangeFactorRule> rules,
      LocalDate priceDate,
      CostRunContext context,
      BatchInputs batch) {
    if (context == null || !StringUtils.hasText(context.getPricingMonth())) {
      return PriceResolveResult.miss("PRICE_MONTH_REQUIRED", "料号=" + material + "：行情原稿取价必须提供核算月份");
    }
    var dated = rules.stream().filter(rule -> StringUtils.hasText(rule.getPricingMonth())).toList();
    var organizations = dated.stream().map(PriceRangeFactorRule::getOrgCode).distinct().toList();
    if (organizations.size() != 1) {
      return PriceResolveResult.miss(
          "PRICE_ORGANIZATION_REQUIRED", "料号=" + material + "：行情原稿存在多个组织，必须明确取价组织");
    }
    String org = organizations.getFirst();
    PriceMonthSelection months =
        batch == null
            ? monthSelector.select(context.getBusinessUnitType(), org, context.getPricingMonth())
            : batch
                .months()
                .computeIfAbsent(
                    text(org),
                    ignored ->
                        monthSelector.select(
                            context.getBusinessUnitType(), org, context.getPricingMonth()));
    if (months == null) {
      return PriceResolveResult.miss(
          MaterialPriceMonthSelector.MATERIAL_MONTH_PRICE_MISSING,
          "料号=" + material + "，核算月=" + context.getPricingMonth() + "：没有适用原稿月份，不能取未来月");
    }
    var versions = new java.util.LinkedHashMap<String, PriceRangeFactorRule>();
    for (var rule : dated) {
      if (!months.sourceMonth().equals(rule.getPricingMonth())) continue;
      String key = rule.getRuleIdentityKey();
      if (!StringUtils.hasText(key)) {
        return PriceResolveResult.miss(
            "MATERIAL_RULE_IDENTITY_MISSING", "料号=" + material + "：月度行情规则缺少业务身份");
      }
      var previous = versions.get(key);
      if (previous == null || rule.getVersionNo() > previous.getVersionNo())
        versions.put(key, rule);
    }
    if (versions.isEmpty()) {
      return PriceResolveResult.miss(
          MaterialPriceMonthSelector.MATERIAL_MONTH_PRICE_MISSING,
          MaterialPriceMonthSelector.missingMessage(material, org, months));
    }
    var candidates = List.copyOf(versions.values());
    var first = candidates.getFirst();
    if (!CostRunContext.SCENE_MONTHLY_REPRICE.equals(context.getScene())
        && candidates.stream()
            .anyMatch(
                com.sanhua.marketingcost.service.impl.OriginalRangeRuleSnapshot::isOriginal)) {
      try {
        if (candidates.stream()
            .anyMatch(
                rule ->
                    !com.sanhua.marketingcost.service.impl.OriginalRangeRuleSnapshot.isOriginal(
                        rule)))
          throw new IllegalArgumentException("同月混入原稿与旧区间规则，必须明确适用版本：" + material);
        if (batch != null && batch.effectiveError() != null)
          throw new IllegalArgumentException(batch.effectiveError());
        var saved =
            batch == null
                ? effectivePrices.read(
                    "RANGE",
                    candidates.stream().map(PriceRangeFactorRule::getId).toList(),
                    originalRuntime.financialSnapshot(first, context.getPricingMonth()))
                : batch.effective();
        var values =
            candidates.stream()
                .map(
                    rule ->
                        effectivePrices.require(
                            FinanceEffectivePriceService.SourceRule.range(rule), saved))
                .toList();
        var reviews = context.getSupplierPriceReviewContext();
        var scope =
            new SupplierPriceScope(
                context.getBusinessUnitType(),
                oaNo,
                reviews == null ? context.getOaFormItemId() : reviews.oaFormItemId(),
                context.getPricingMonth(),
                priceDate,
                org,
                material,
                "RANGE");
        var chosen =
            effectiveSelection.select(
                scope,
                values,
                reviews,
                batch == null ? null : batch.ratios().getOrDefault(material, List.of()));
        if (chosen.price() == null)
          return PriceResolveResult.miss(
              SupplierPriceSelectionService.REVIEW_REQUIRED, chosen.supplier().message());
        var row =
            candidates.stream()
                .filter(rule -> rule.getId().equals(chosen.price().getOriginalId()))
                .findFirst()
                .orElseThrow();
        var ratio = chosen.supplier().ratio();
        var selected =
            new SupplierPreferredPriceSelection<>(
                row,
                chosen.supplier().message(),
                candidates.size(),
                row.getSupplierName(),
                row.getSupplierCode(),
                ratio == null ? null : ratio.getSupplyRatio(),
                ratio == null ? null : ratio.getId(),
                chosen.supplier().rule(),
                null,
                null);
        var resolved =
            originalRuntime.resolveSaved(
                row,
                oaNo,
                context,
                selected,
                batch == null ? null : batch.original(),
                chosen.price(),
                effectivePrices.calculation(chosen.price()));
        return withOriginalMonth(resolved, months);
      } catch (IllegalArgumentException | IllegalStateException failure) {
        return PriceResolveResult.miss(
            "ORIGINAL_RANGE_PRICE_INVALID", "物料=" + material + "：" + failure.getMessage());
      }
    }
    var selection =
        supplierPreferredPriceSelector.select(
            candidates,
            context.getBusinessUnitType(),
            material,
            first.getMaterialName(),
            first.getSpecModel(),
            priceDate,
            PriceRangeFactorRule::getSupplierName,
            PriceRangeFactorRule::getSupplierCode,
            batch == null
                ? null
                : batch
                    .suppliers()
                    .get(
                        com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils
                            .normalizeToNull(material)));
    if (selection.failed())
      return PriceResolveResult.miss(selection.failureCode(), selection.failureMessage());
    if (selection.row() == null)
      return PriceResolveResult.miss("RANGE_RULE_MISSING", "料号=" + material + "：无适用行情规则");
    if (candidates.stream()
            .filter(
                rule -> selection.matchesSupplier(rule.getSupplierName(), rule.getSupplierCode()))
            .count()
        > 1) {
      return PriceResolveResult.miss(
          "MATERIAL_RULE_AMBIGUOUS", "料号=" + material + "：同供应商存在多个规格或计价单位的行情规则");
    }
    var result =
        resolveFactorRange(oaNo, material, selection.row(), priceDate, context, selection, batch);
    return withOriginalMonth(result, months);
  }

  private PriceResolveResult withOriginalMonth(
      PriceResolveResult result, PriceMonthSelection months) {
    if (result.unitPrice() == null) return result;
    var evidence = result.evidence();
    String monthTrace = "核算月=" + months.requestedMonth() + "，原稿月份=" + months.sourceMonth();
    String warning =
        months.carriedForward()
            ? "原稿沿用历史月份："
                + monthTrace
                + (StringUtils.hasText(evidence.warningMessage())
                    ? "；" + evidence.warningMessage()
                    : "")
            : evidence.warningMessage();
    var monthlyEvidence =
        new PriceResolveEvidence(
            evidence.sourcePriceRecordId(),
            evidence.sourceBatchNo(),
            evidence.supplierName(),
            evidence.supplierCode(),
            evidence.supplyRatio(),
            evidence.supplyRatioRecordId(),
            evidence.effectiveFrom(),
            evidence.effectiveTo(),
            months.carriedForward() || evidence.carriedForward(),
            warning,
            evidence.inputSnapshotJson());
    return PriceResolveResult.hit(
        result.unitPrice(),
        result.priceSource(),
        result.remark() + "；" + monthTrace,
        result.resultRefId(),
        monthlyEvidence);
  }

  private BigDecimal resolveFactorValue(String oaNo, PriceRangeFactorRule factorRule) {
    if (oaFormMapper == null || factorRule == null || !StringUtils.hasText(oaNo)) {
      return null;
    }
    OaForm oaForm =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, oaNo.trim()).last("LIMIT 1"));
    if (oaForm == null) {
      return null;
    }
    String factorCode = trimToNull(factorRule.getFactorCode());
    if (factorCode == null) {
      return null;
    }
    return switch (factorCode.toUpperCase(Locale.ROOT)) {
      case "CU" -> oaForm.getCopperPrice();
      case "ZN" -> oaForm.getZincPrice();
      case "AL" -> oaForm.getAluminumPrice();
      case "GOLD" -> oaForm.getGoldPrice();
      case "SILVER" -> oaForm.getSilverPrice();
      case "SUS304" -> oaForm.getSus304Price();
      case "SUS316", "SUS316L" -> oaForm.getSus316lPrice();
      default -> null;
    };
  }

  private boolean matchesRange(PriceRangeItem row, BigDecimal qty) {
    BigDecimal low = row.getRangeLow();
    BigDecimal high = row.getRangeHigh();
    boolean lowInclusive = !Integer.valueOf(0).equals(row.getLowerInclusive());
    boolean highInclusive = !Integer.valueOf(0).equals(row.getUpperInclusive());
    return (low == null || (lowInclusive ? qty.compareTo(low) >= 0 : qty.compareTo(low) > 0))
        && (high == null || (highInclusive ? qty.compareTo(high) <= 0 : qty.compareTo(high) < 0));
  }

  private LocalDate pricingDate(PriceTypeRoute route, CostRunContext context) {
    if (context != null && context.getPriceAsOfTime() != null) {
      return context.getPriceAsOfTime().toLocalDate();
    }
    return route == null ? null : route.effectiveFrom();
  }

  private String format(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
