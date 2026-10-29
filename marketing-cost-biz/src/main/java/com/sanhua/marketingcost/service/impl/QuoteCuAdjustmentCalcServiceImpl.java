package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.financequote.FinancePricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcRequest;
import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcResult;
import com.sanhua.marketingcost.dto.financequote.QuoteCuMaterialDiffResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.QuoteCostPriceScenario;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.QuoteCostPriceScenarioMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.CostRunEngine;
import com.sanhua.marketingcost.service.FinancePricePrepareService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionService;
import com.sanhua.marketingcost.service.QuoteCuAdjustmentCalcService;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteCuAdjustmentCalcServiceImpl implements QuoteCuAdjustmentCalcService {
  private static final String COST_CODE_MATERIAL = "MATERIAL";
  private static final String COST_CODE_TOTAL = "TOTAL";
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final BigDecimal KG_PER_TON = new BigDecimal("1000");
  private static final int MONEY_SCALE = 8;

  private final PricePrepareBatchMapper batchMapper;
  private final FinancePricePrepareService financePricePrepareService;
  private final QuoteCostRunVersionService versionService;
  private final QuoteCostRunVersionMapper versionMapper;
  private final CostRunEngine costRunEngine;
  private final QuoteCostPriceScenarioMapper scenarioMapper;
  private final com.sanhua.marketingcost.service.costing.MonthlyQuotationCosting monthlyCosting;

  public QuoteCuAdjustmentCalcServiceImpl(
      PricePrepareBatchMapper batchMapper, FinancePricePrepareService financePricePrepareService,
      QuoteCostRunVersionService versionService, QuoteCostRunVersionMapper versionMapper,
      CostRunEngine costRunEngine, QuoteCostPriceScenarioMapper scenarioMapper,
      com.sanhua.marketingcost.service.costing.MonthlyQuotationCosting monthlyCosting) {
    this.batchMapper = batchMapper; this.financePricePrepareService = financePricePrepareService;
    this.versionService = versionService; this.versionMapper = versionMapper;
    this.costRunEngine = costRunEngine; this.scenarioMapper = scenarioMapper;
    this.monthlyCosting = monthlyCosting;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @com.sanhua.marketingcost.service.reprice.QuotationWrite(holdUntilCompletion=true)
  public QuoteCuAdjustmentCalcResult calculate(QuoteCuAdjustmentCalcRequest request) {
    Scope scope = requireScope(request);
    PricePrepareBatch oaBatch = loadAndValidateOaBatch(scope);
    FinancePricePrepareGenerateResult financePrepare =
        financePricePrepareService.loadPreparedFromOa(oaBatch.getPrepareNo());
    oaBatch = reloadOaBatchWithScenarioGroup(scope, financePrepare);
    validateFinancePrepare(oaBatch, financePrepare);

    QuoteCostRunVersion version = createVersion(scope, oaBatch, financePrepare);
    CostRunContext context = buildContext(scope, oaBatch, financePrepare, version);
    CostRunObjectResult costResult = requireCostResult(costRunEngine.run(context));
    validateNoAdjustmentCostItem(costResult);
    BigDecimal financeMaterialCost = uniqueCostAmount(costResult, COST_CODE_MATERIAL);
    BigDecimal totalCost = uniqueCostAmount(costResult, COST_CODE_TOTAL);
    validateResultTotal(costResult, totalCost);

    var stored = Objects.requireNonNull(monthlyCosting, "报价核算必须配置月价成本存储")
        .save(version, costResult, scope.form().getCopperPrice());
    version = stored.version();
    var calculation = stored.snapshot().calculation();
    financeMaterialCost = calculation.financeMaterialCost();
    totalCost = calculation.totalCost();
    QuoteCuMaterialDiffResult materialDiff = monthlyCosting.difference(stored);
    BigDecimal adjustment = calculation.cuMaterialAdjustment();
    BigDecimal oaMaterialCost = financeMaterialCost.add(adjustment);
    BigDecimal finalQuoteAmount = calculation.finalQuoteAmount();

    fillResultSummary(
        costResult.getResult(),
        financeMaterialCost,
        oaMaterialCost,
        totalCost,
        adjustment,
        finalQuoteAmount);
    costResult.setPartItems(stored.parts().stream().map(part -> {
      var row = new com.sanhua.marketingcost.dto.CostRunPartItemDto();
      row.setId(part.getId()); row.setBomRowId(part.getBomRowId()); row.setPricePrepareItemId(part.getPricePrepareItemId());
      row.setOaNo(part.getOaNo()); row.setProductCode(part.getProductCode()); row.setPartCode(part.getPartCode());
      row.setPartName(part.getPartName()); row.setPartQty(part.getQty()); row.setUnitPrice(part.getUnitPrice());
      row.setAmount(part.getAmount()); row.setPriceSource(part.getPriceSource()); row.setPriceOrgCode(part.getPriceOrgCode());
      row.setMaterialOrganizationCode(part.getMaterialOrganizationCode()); return row;
    }).toList());
    costResult.setCostItems(stored.costs().stream().map(cost -> {
      var row = new CostRunCostItemDto(); row.setId(cost.getId()); row.setCostCode(cost.getCostCode());
      row.setCostName(cost.getCostName()); row.setAmount(cost.getAmount()); row.setBaseAmount(cost.getBaseAmount());
      row.setRate(cost.getRate()); row.setCategory(cost.getCategory()); row.setRemark(cost.getRemark());
      row.setSourceTable(cost.getSourceTable()); row.setSourceId(cost.getSourceId()); return row;
    }).toList());
    persistScenarios(
        version,
        oaBatch,
        financePrepare,
        materialDiff,
        scope.oaCuPrice(),
        financeMaterialCost,
        oaMaterialCost,
        totalCost);
    return new QuoteCuAdjustmentCalcResult(
        version,
        costResult,
        materialDiff,
        financeMaterialCost,
        oaMaterialCost,
        totalCost,
        adjustment,
        finalQuoteAmount);
  }

  private Scope requireScope(QuoteCuAdjustmentCalcRequest request) {
    if (request == null || request.form() == null || request.item() == null) {
      throw new IllegalArgumentException("单产品报价表头和产品行不能为空");
    }
    OaForm form = request.form();
    OaFormItem item = request.item();
    if (form.getId() == null || item.getId() == null || !form.getId().equals(item.getOaFormId())) {
      throw new IllegalArgumentException("报价产品行不属于当前报价单");
    }
    String oaNo = requireText(form.getOaNo(), "oaNo");
    String productCode =
        requireText(QuoteProductIdentityUtils.resolveCostingCode(item), "productCode");
    String pricingMonth = requireText(request.pricingMonth(), "pricingMonth");
    String businessUnitType = firstText(item.getBusinessUnitType(), form.getBusinessUnitType());
    businessUnitType = requireText(businessUnitType, "businessUnitType");
    String oaPrepareNo = requireText(request.oaPricePrepareNo(), "oaPricePrepareNo");
    BigDecimal oaCuPrice = oaCuPrice(form.getCopperPrice());
    return new Scope(
        request,
        form,
        item,
        oaNo,
        productCode,
        pricingMonth,
        businessUnitType,
        oaPrepareNo,
        oaCuPrice);
  }

  private PricePrepareBatch loadAndValidateOaBatch(Scope scope) {
    PricePrepareBatch batch = batchMapper.selectOne(
        Wrappers.lambdaQuery(PricePrepareBatch.class)
            .eq(PricePrepareBatch::getPrepareNo, scope.oaPrepareNo())
            .last("LIMIT 1"));
    if (batch == null) {
      throw new IllegalArgumentException("OA价格准备批次不存在: " + scope.oaPrepareNo());
    }
    requireSame("OA价格准备", "场景", QuotePriceScenarioType.OA_LOCKED.name(), batch.getScenarioType());
    requireSame("OA价格准备", "状态", STATUS_SUCCESS, batch.getStatus());
    if (value(batch.getGapCount()) != 0) {
      throw new IllegalStateException("OA价格准备批次存在缺口");
    }
    requireSame("OA价格准备", "OA单号", scope.oaNo(), batch.getOaNo());
    requireSame("OA价格准备", "产品行", scope.item().getId(), batch.getOaFormItemId());
    requireSame("OA价格准备", "产品料号", scope.productCode(), batch.getTopProductCode());
    requireSame("OA价格准备", "计价月份", scope.pricingMonth(), batch.getPeriodMonth());
    requireSame("OA价格准备", "业务单元", scope.businessUnitType(), batch.getBusinessUnitType());
    if (batch.getPriceAsOfTime() == null) {
      throw new IllegalStateException("OA价格准备批次缺取价时点");
    }
    return batch;
  }

  private PricePrepareBatch reloadOaBatchWithScenarioGroup(
      Scope scope, FinancePricePrepareGenerateResult financePrepare) {
    PricePrepareBatch refreshed = loadAndValidateOaBatch(scope);
    if (financePrepare == null) {
      throw new IllegalStateException("财务Cu价格准备没有返回结果");
    }
    requireSame(
        "财务价格准备",
        "场景组",
        requireText(financePrepare.scenarioGroupNo(), "scenarioGroupNo"),
        refreshed.getScenarioGroupNo());
    return refreshed;
  }

  private void validateFinancePrepare(
      PricePrepareBatch oaBatch, FinancePricePrepareGenerateResult financePrepare) {
    if (financePrepare == null || financePrepare.prepareResult() == null) {
      throw new IllegalStateException("财务Cu价格准备没有返回结果");
    }
    requireSame("财务价格准备", "来源批次", oaBatch.getPrepareNo(), financePrepare.sourcePrepareNo());
    requireText(financePrepare.financePrepareNo(), "financePricePrepareNo");
    requireText(financePrepare.scenarioGroupNo(), "scenarioGroupNo");
    requireSame(
        "财务价格准备",
        "场景",
        QuotePriceScenarioType.FINANCE_QUOTE_BASE.name(),
        financePrepare.prepareResult().getScenarioType());
    requireSame(
        "财务价格准备",
        "批次号",
        financePrepare.financePrepareNo(),
        financePrepare.prepareResult().getPrepareNo());
    requireSame(
        "财务价格准备", "状态", STATUS_SUCCESS, financePrepare.prepareResult().getStatus());
    if (financePrepare.prepareResult().getGapCount() != 0) {
      throw new IllegalStateException("财务Cu价格准备批次存在缺口");
    }
    requireSame(
        "财务价格准备",
        "取价时点",
        oaBatch.getPriceAsOfTime(),
        financePrepare.prepareResult().getPriceAsOfTime());
    requireSame(
        "财务价格准备", "场景组", financePrepare.scenarioGroupNo(), oaBatch.getScenarioGroupNo());
  }

  private QuoteCostRunVersion createVersion(
      Scope scope,
      PricePrepareBatch oaBatch,
      FinancePricePrepareGenerateResult financePrepare) {
    QuoteCostRunVersion version;
    if (scope.request().automaticCompletion()) {
      version =
          versionService.createRunning(
              scope.oaNo(),
              scope.item().getId(),
              scope.productCode(),
              scope.pricingMonth(),
              scope.pricingMonth(),
              financePrepare.financePrepareNo(),
              scope.businessUnitType());
    } else {
      version =
          versionService.createTrial(
              scope.oaNo(),
              scope.item().getId(),
              scope.productCode(),
              scope.pricingMonth(),
              scope.pricingMonth(),
              financePrepare.financePrepareNo(),
              scope.businessUnitType());
    }
    if (version == null || version.getId() == null || !StringUtils.hasText(version.getCostRunNo())) {
      throw new IllegalStateException("成本试算版本创建失败");
    }
    QuoteCostRunVersion patch = new QuoteCostRunVersion();
    patch.setId(version.getId());
    patch.setRepriceNo(scope.request().reprice() == null ? null : scope.request().reprice().repriceNo());
    version.setRepriceNo(patch.getRepriceNo());
    patch.setPricePrepareNo(financePrepare.financePrepareNo());
    patch.setOaPricePrepareNo(oaBatch.getPrepareNo());
    patch.setFinancePricePrepareNo(financePrepare.financePrepareNo());
    patch.setFinanceCuPrice(financePrepare.financeCuPricePerKg());
    patch.setOaCuPrice(scope.oaCuPrice());
    patch.setFinanceBasePriceId(financePrepare.financeBasePriceId());
    if (versionMapper.updateById(patch) != 1) {
      throw new IllegalStateException("成本版本双场景引用写入失败");
    }
    version.setPricePrepareNo(patch.getPricePrepareNo());
    version.setOaPricePrepareNo(patch.getOaPricePrepareNo());
    version.setFinancePricePrepareNo(patch.getFinancePricePrepareNo());
    version.setFinanceCuPrice(patch.getFinanceCuPrice());
    version.setOaCuPrice(patch.getOaCuPrice());
    version.setFinanceBasePriceId(patch.getFinanceBasePriceId());
    return version;
  }

  private CostRunContext buildContext(
      Scope scope,
      PricePrepareBatch oaBatch,
      FinancePricePrepareGenerateResult financePrepare,
      QuoteCostRunVersion version) {
    CostRunContext context = CostRunContext.quote(
        scope.oaNo(),
        scope.item().getId(),
        scope.productCode(),
        scope.item().getPackageMethod(),
        scope.form().getCustomer(),
        scope.businessUnitType(),
        scope.pricingMonth(),
        financePrepare.prepareResult().getPriceAsOfTime(),
        firstText(scope.request().calcObjectKey(), "QUOTE:" + scope.item().getId()));
    QuoteDataOrganization organization = MaterialOrganization.quoteDataForQuoteProduct(
        scope.form().getProcessCode(),
        scope.form().getOaNo(),
        scope.item().getBusinessUnitType(),
        scope.item().getProductName(),
        scope.item().getSunlModel(),
        scope.item().getMaterialNo());
    if (scope.request().reprice() != null) {
      var saved = scope.request().reprice();
      context.setScene(CostRunContext.SCENE_MONTHLY_REPRICE);
      context.setRepriceNo(saved.repriceNo()); context.setBomSourcePolicy("MONTHLY_SNAPSHOT");
      organization = saved.input().context().organization();
    }
    context.setPriceOrgCode(organization.priceOrgCode());
    context.setMaterialOrganizationCode(organization.materialOrganizationCode());
    context.setCostRunVersionId(version.getId());
    context.setCostRunNo(version.getCostRunNo());
    context.setPricePrepareNo(financePrepare.financePrepareNo());
    context.setPriceScenarioType(QuotePriceScenarioType.FINANCE_QUOTE_BASE.name());
    context.setProgress(scope.request().progress());
    return context;
  }

  private CostRunObjectResult requireCostResult(CostRunObjectResult result) {
    if (result == null || result.getContext() == null || result.getResult() == null) {
      throw new IllegalStateException("财务场景成本核算结果不完整");
    }
    return result;
  }

  private BigDecimal uniqueCostAmount(CostRunObjectResult result, String costCode) {
    List<CostRunCostItemDto> matches = result.getCostItems() == null
        ? List.of()
        : result.getCostItems().stream()
            .filter(Objects::nonNull)
            .filter(item -> costCode.equals(trim(item.getCostCode())))
            .toList();
    if (matches.size() != 1 || matches.get(0).getAmount() == null) {
      throw new IllegalStateException("财务场景成本结果必须且只能有一条" + costCode + "成本项");
    }
    return money(matches.get(0).getAmount());
  }

  private void validateNoAdjustmentCostItem(CostRunObjectResult result) {
    if (result.getCostItems() == null) {
      return;
    }
    for (CostRunCostItemDto item : result.getCostItems()) {
      String code = item == null ? "" : trim(item.getCostCode());
      if ("CU_MATERIAL_ADJUSTMENT".equals(code)
          || "CU_ADJUSTMENT".equals(code)
          || "FINAL_QUOTE".equals(code)) {
        throw new IllegalStateException("Cu差额和最终报价不能写入普通成本项参与TOTAL计算");
      }
    }
  }

  private void validateResultTotal(CostRunObjectResult result, BigDecimal totalCost) {
    BigDecimal headerTotal = result.getResult().getTotalCost();
    if (headerTotal == null || money(headerTotal).compareTo(totalCost) != 0) {
      throw new IllegalStateException("成本结果totalCost与TOTAL成本项不一致");
    }
  }

  private void fillResultSummary(
      CostRunResultDto result,
      BigDecimal financeMaterialCost,
      BigDecimal oaMaterialCost,
      BigDecimal totalCost,
      BigDecimal adjustment,
      BigDecimal finalQuoteAmount) {
    result.setTotalCost(totalCost);
    result.setFinanceMaterialCost(financeMaterialCost);
    result.setOaMaterialCost(oaMaterialCost);
    result.setCuMaterialAdjustment(adjustment);
    result.setFinalQuoteAmount(finalQuoteAmount);
  }

  private void persistScenarios(
      QuoteCostRunVersion version,
      PricePrepareBatch oaBatch,
      FinancePricePrepareGenerateResult financePrepare,
      QuoteCuMaterialDiffResult materialDiff,
      BigDecimal oaCuPrice,
      BigDecimal financeMaterialCost,
      BigDecimal oaMaterialCost,
      BigDecimal totalCost) {
    String inputHash = inputSnapshotHash(oaBatch, materialDiff);
    QuoteCostPriceScenario oa = scenario(
        version,
        QuotePriceScenarioType.OA_LOCKED,
        oaBatch.getPrepareNo(),
        oaCuPrice,
        "OA_LOCKED",
        null,
        oaMaterialCost,
        null,
        inputHash);
    QuoteCostPriceScenario finance = scenario(
        version,
        QuotePriceScenarioType.FINANCE_QUOTE_BASE,
        financePrepare.financePrepareNo(),
        financePrepare.financeCuPricePerKg(),
        "MONTHLY_MATERIAL",
        financePrepare.financeFactorPriceId() == null
            ? financePrepare.financeBasePriceId() : financePrepare.financeFactorPriceId(),
        financeMaterialCost,
        totalCost,
        inputHash);
    if (scenarioMapper.insert(oa) != 1 || scenarioMapper.insert(finance) != 1) {
      throw new IllegalStateException("成本价格场景汇总写入失败");
    }
  }

  private QuoteCostPriceScenario scenario(
      QuoteCostRunVersion version,
      QuotePriceScenarioType type,
      String prepareNo,
      BigDecimal cuPrice,
      String cuPriceSource,
      Long cuSourceRefId,
      BigDecimal materialCost,
      BigDecimal totalCost,
      String inputHash) {
    QuoteCostPriceScenario scenario = new QuoteCostPriceScenario();
    scenario.setScenarioNo("FQS-" + UUID.randomUUID());
    scenario.setCostRunVersionId(version.getId());
    scenario.setCostRunNo(version.getCostRunNo());
    scenario.setScenarioType(type.name());
    scenario.setPricePrepareNo(prepareNo);
    scenario.setPricingMonth(version.getPricingMonth());
    scenario.setCuPrice(cuPrice);
    scenario.setCuPriceSource(cuPriceSource);
    scenario.setCuSourceRefId(cuSourceRefId);
    scenario.setMaterialCost(materialCost);
    scenario.setTotalCost(totalCost);
    scenario.setInputSnapshotHash(inputHash);
    scenario.setStatus(STATUS_SUCCESS);
    scenario.setBusinessUnitType(version.getBusinessUnitType());
    return scenario;
  }

  private String inputSnapshotHash(
      PricePrepareBatch batch, QuoteCuMaterialDiffResult materialDiff) {
    StringBuilder input = new StringBuilder()
        .append(text(batch.getScenarioGroupNo())).append('|')
        .append(text(batch.getOaNo())).append('|')
        .append(batch.getOaFormItemId()).append('|')
        .append(text(batch.getTopProductCode())).append('|')
        .append(text(batch.getPeriodMonth())).append('|')
        .append(batch.getPriceAsOfTime()).append('|')
        .append(text(batch.getBusinessUnitType())).append('|')
        .append(text(batch.getBomPurpose())).append('|')
        .append(text(batch.getSourceType()));
    materialDiff.items().stream()
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing(item -> text(item.getSettlementKey())))
        .forEach(item -> input
            .append('\n').append(text(item.getSettlementKey()))
            .append('|').append(text(item.getParentSettlementKey()))
            .append('|').append(item.getBomRowId())
            .append('|').append(text(item.getMaterialCode()))
            .append('|').append(text(item.getItemType()))
            .append('|').append(item.getQuantity())
            .append('|').append(text(item.getTraceJson())));
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(input.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("当前JVM不支持SHA-256", ex);
    }
  }

  private BigDecimal oaCuPrice(BigDecimal pricePerTon) {
    if (pricePerTon == null || pricePerTon.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return pricePerTon.divide(KG_PER_TON, MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal money(BigDecimal value) {
    if (value == null) {
      throw new IllegalStateException("金额不能为空");
    }
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private int value(Integer value) {
    return value == null ? 0 : value;
  }

  private void requireSame(String context, String field, Object expected, Object actual) {
    if (!Objects.equals(normalize(expected), normalize(actual))) {
      throw new IllegalStateException(context + field + "不一致");
    }
  }

  private Object normalize(Object value) {
    if (value instanceof String text) {
      return text.trim();
    }
    if (value instanceof BigDecimal decimal) {
      return decimal.stripTrailingZeros();
    }
    return value;
  }

  private String requireText(String value, String field) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return value.trim();
  }

  private String firstText(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : "";
  }

  private String text(String value) {
    return value == null ? "" : value.trim();
  }

  private record Scope(
      QuoteCuAdjustmentCalcRequest request,
      OaForm form,
      OaFormItem item,
      String oaNo,
      String productCode,
      String pricingMonth,
      String businessUnitType,
      String oaPrepareNo,
      BigDecimal oaCuPrice) {}
}
