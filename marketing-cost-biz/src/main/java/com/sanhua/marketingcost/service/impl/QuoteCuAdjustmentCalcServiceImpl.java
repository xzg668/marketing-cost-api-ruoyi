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
import com.sanhua.marketingcost.entity.QuoteCuMaterialDiffItem;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.QuoteCostPriceScenarioMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.CostRunEngine;
import com.sanhua.marketingcost.service.CostRunResultWriter;
import com.sanhua.marketingcost.service.FinancePricePrepareService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionService;
import com.sanhua.marketingcost.service.QuoteCuAdjustmentCalcService;
import com.sanhua.marketingcost.service.QuoteCuMaterialDiffService;
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
  private final CostRunResultWriter resultWriter;
  private final QuoteCuMaterialDiffService materialDiffService;
  private final QuoteCostPriceScenarioMapper scenarioMapper;

  public QuoteCuAdjustmentCalcServiceImpl(
      PricePrepareBatchMapper batchMapper,
      FinancePricePrepareService financePricePrepareService,
      QuoteCostRunVersionService versionService,
      QuoteCostRunVersionMapper versionMapper,
      CostRunEngine costRunEngine,
      CostRunResultWriter resultWriter,
      QuoteCuMaterialDiffService materialDiffService,
      QuoteCostPriceScenarioMapper scenarioMapper) {
    this.batchMapper = batchMapper;
    this.financePricePrepareService = financePricePrepareService;
    this.versionService = versionService;
    this.versionMapper = versionMapper;
    this.costRunEngine = costRunEngine;
    this.resultWriter = resultWriter;
    this.materialDiffService = materialDiffService;
    this.scenarioMapper = scenarioMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
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

    QuoteCuMaterialDiffResult materialDiff = materialDiffService.calculate(version.getId());
    BigDecimal adjustment = money(materialDiff.adjustmentAmount());
    validateMaterialDiff(scope.oaCuPrice(), materialDiff, adjustment);
    BigDecimal oaMaterialCost = money(financeMaterialCost.add(adjustment));
    BigDecimal finalQuoteAmount = money(totalCost.add(adjustment));
    validateIdentities(
        financeMaterialCost,
        oaMaterialCost,
        totalCost,
        adjustment,
        finalQuoteAmount,
        materialDiff);

    fillResultSummary(
        costResult.getResult(),
        financeMaterialCost,
        oaMaterialCost,
        totalCost,
        adjustment,
        finalQuoteAmount);
    resultWriter.writeQuoteResult(costResult);
    persistScenarios(
        version,
        oaBatch,
        financePrepare,
        materialDiff,
        scope.oaCuPrice(),
        financeMaterialCost,
        oaMaterialCost,
        totalCost);
    finishVersion(
        version,
        financeMaterialCost,
        oaMaterialCost,
        totalCost,
        adjustment,
        finalQuoteAmount,
        size(costResult.getPartItems()),
        size(costResult.getCostItems()));

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
    String productCode = requireText(item.getMaterialNo(), "productCode");
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
    if (financePrepare.financeBasePriceId() == null
        || financePrepare.financeCuPricePerKg() == null
        || financePrepare.financeCuPricePerKg().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalStateException("财务Cu基准结果不完整");
    }
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
    patch.setPricePrepareNo(financePrepare.financePrepareNo());
    patch.setOaPricePrepareNo(oaBatch.getPrepareNo());
    patch.setFinancePricePrepareNo(financePrepare.financePrepareNo());
    patch.setFinanceCuPrice(money(financePrepare.financeCuPricePerKg()));
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

  private void validateMaterialDiff(
      BigDecimal oaCuPrice, QuoteCuMaterialDiffResult diff, BigDecimal adjustment) {
    if (diff == null) {
      throw new IllegalStateException("Cu材料费差异计算没有返回结果");
    }
    if (diff.cuAffectedSettlementCount() > 0 && oaCuPrice == null) {
      throw new IllegalStateException("当前产品存在Cu材料，但OA报价单未锁定Cu价格");
    }
    if (diff.cuAffectedSettlementCount() == 0 && adjustment.signum() != 0) {
      throw new IllegalStateException("当前产品没有Cu材料，Cu材料费差额必须为0");
    }
  }

  private void validateIdentities(
      BigDecimal financeMaterialCost,
      BigDecimal oaMaterialCost,
      BigDecimal totalCost,
      BigDecimal adjustment,
      BigDecimal finalQuoteAmount,
      QuoteCuMaterialDiffResult diff) {
    BigDecimal detailSum = diff.items().stream()
        .filter(Objects::nonNull)
        .filter(item -> Integer.valueOf(1).equals(item.getContributesToAdjustment()))
        .map(QuoteCuMaterialDiffItem::getDiffAmount)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    requireDecimal("D_CU=差异结算行之和", adjustment, money(detailSum));
    requireDecimal("M_OA=M_FIN+D_CU", oaMaterialCost, money(financeMaterialCost.add(adjustment)));
    requireDecimal("D_CU=M_OA-M_FIN", adjustment, money(oaMaterialCost.subtract(financeMaterialCost)));
    requireDecimal("Q_FINAL=T_FIN+D_CU", finalQuoteAmount, money(totalCost.add(adjustment)));
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
        money(financePrepare.financeCuPricePerKg()),
        "FINANCE_QUOTE_BASE",
        financePrepare.financeBasePriceId(),
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
            .append('|').append(item.getQuantity()));
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(input.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("当前JVM不支持SHA-256", ex);
    }
  }

  private void finishVersion(
      QuoteCostRunVersion version,
      BigDecimal financeMaterialCost,
      BigDecimal oaMaterialCost,
      BigDecimal totalCost,
      BigDecimal adjustment,
      BigDecimal finalQuoteAmount,
      int partItemCount,
      int costItemCount) {
    LocalDateTime finishedAt = LocalDateTime.now();
    QuoteCostRunVersion patch = new QuoteCostRunVersion();
    patch.setId(version.getId());
    patch.setFinanceMaterialCost(financeMaterialCost);
    patch.setOaMaterialCost(oaMaterialCost);
    patch.setCuMaterialAdjustment(adjustment);
    patch.setFinalQuoteAmount(finalQuoteAmount);
    patch.setTotalCost(totalCost);
    patch.setPartItemCount(partItemCount);
    patch.setCostItemCount(costItemCount);
    patch.setTrialFinishedAt(finishedAt);
    if (versionMapper.updateById(patch) != 1) {
      throw new IllegalStateException("成本版本双场景汇总写入失败");
    }
    version.setFinanceMaterialCost(financeMaterialCost);
    version.setOaMaterialCost(oaMaterialCost);
    version.setCuMaterialAdjustment(adjustment);
    version.setFinalQuoteAmount(finalQuoteAmount);
    version.setTotalCost(totalCost);
    version.setPartItemCount(partItemCount);
    version.setCostItemCount(costItemCount);
    version.setTrialFinishedAt(finishedAt);
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

  private int size(List<?> values) {
    return values == null ? 0 : values.size();
  }

  private int value(Integer value) {
    return value == null ? 0 : value;
  }

  private void requireDecimal(String identity, BigDecimal expected, BigDecimal actual) {
    if (expected == null || actual == null || expected.compareTo(actual) != 0) {
      throw new IllegalStateException(identity + "校验失败");
    }
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
