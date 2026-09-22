package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingResult;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunTrialRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkflowStatusResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionSummaryResponse;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.enums.QuoteCostRunStatus;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import com.sanhua.marketingcost.service.ProductCostingPipeline;
import com.sanhua.marketingcost.service.ProductCostingStateService;
import com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService;
import com.sanhua.marketingcost.service.QuoteCostingWorkbenchService;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.service.costing.ProductCostingContext;
import com.sanhua.marketingcost.service.costing.ProductCostingContextResolver;
import com.sanhua.marketingcost.service.costing.ProductCostingSuccessLookup;
import com.sanhua.marketingcost.service.costing.ProductCostingFailurePolicy;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataAvailability;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataQuoteSourceReader;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 只编排BOM、价格类型、价格准备与成本发布；输入及失败规则由专职组件处理。 */
@Service
public class ProductCostingPipelineImpl implements ProductCostingPipeline {
  private com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy oaWorkflowAccess;
  @org.springframework.beans.factory.annotation.Autowired
  public void setOaWorkflowAccess(com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy policy) {
    this.oaWorkflowAccess = policy;
  }

  static final String STEP_BOM = "QUOTE_BOM";
  static final String STEP_PRICE_TYPE = "PRICE_TYPE_CONFIRMATION";
  static final String STEP_PRICE = "PRICE_PREPARE";
  static final String STEP_COST = "COST_RUN";

  private final QuoteCostingWorkbenchService costingWorkbenchService;
  private final QuotePricePrepareWorkbenchService pricePrepareService;
  private final QuoteCostRunWorkbenchService costRunService;
  private final ProductCostingStateService stateService;
  private final MaterialMasterSyncService materialMasterSyncService;
  private final ProductCostingContextResolver contextResolver;
  private final ProductCostingSuccessLookup successLookup;
  private final ProductCostingFailurePolicy failurePolicy;
  private final TechnicalDataQuoteSourceReader technicalSources;
  private final com.sanhua.marketingcost.service.EffectiveTechnicalDataQueryService effectiveTechnicalData;

  public ProductCostingPipelineImpl(
      QuoteCostingWorkbenchService costingWorkbenchService,
      QuotePricePrepareWorkbenchService pricePrepareService,
      QuoteCostRunWorkbenchService costRunService,
      ProductCostingStateService stateService,
      MaterialMasterSyncService materialMasterSyncService,
      ProductCostingContextResolver contextResolver,
      ProductCostingSuccessLookup successLookup,
      ProductCostingFailurePolicy failurePolicy,
      TechnicalDataQuoteSourceReader technicalSources,
      com.sanhua.marketingcost.service.EffectiveTechnicalDataQueryService effectiveTechnicalData) {
    this.costingWorkbenchService = costingWorkbenchService;
    this.pricePrepareService = pricePrepareService;
    this.costRunService = costRunService;
    this.stateService = stateService;
    this.materialMasterSyncService = materialMasterSyncService;
    this.contextResolver = contextResolver;
    this.successLookup = successLookup;
    this.failurePolicy = failurePolicy;
    this.technicalSources = technicalSources;
    this.effectiveTechnicalData = effectiveTechnicalData;
  }

  @Override
  public ProductCostingResult execute(ProductCostingRequest request) {
    // 请求归属错误直接拒绝；验证通过后，所有阶段故障均返回同一产品的结构化结果。
    ProductCostingContext context = contextResolver.resolve(request);
    oaWorkflowAccess.requireCosting(context.form().getId());
    ProductCostingResult result = executeResolved(context, request.force());
    if (result.getTechnicalDataCheck() != null) return result;
    try {
      result.setTechnicalDataCheck(technicalSources.afterCosting(context));
    } catch (RuntimeException exception) {
      // 来源检查未保存时不能让后续分派误用旧结论，核算原失败原因仍保留在工作区。
      result.setMessage(result.getMessage() + "；补录来源检查未完成，请重新检查资料");
    }
    return result;
  }

  private ProductCostingResult executeResolved(ProductCostingContext context, boolean force) {
    try {
      context = contextResolver.resolveRevision(context);
      ProductCostingResult reusable = force ? null : reusableSuccess(context);
      if (reusable != null) return reusable;
    } catch (com.sanhua.marketingcost.service.EffectiveTechnicalDataException pendingApproval) {
      // 审批尚未齐全仍可检查 BOM/价格缺口；正式成本阶段必须再次核验批准输入。
      return executeStages(context);
    } catch (RuntimeException exception) {
      return failure(context, "INPUT_CHECK", exception);
    }
    return executeStages(context);
  }

  private ProductCostingResult executeStages(ProductCostingContext scope) {
    String stage = STEP_BOM;
    try {
      QuoteCostingWorkbenchResponse workbench =
          costingWorkbenchService.launchWorkbench(scope.oaNo(), scope.itemId());
      ProductCostingResult bomBlocked = bomBlock(scope, workbench);
      if (bomBlocked != null) {
        return bomBlocked;
      }
      // BOM 行生成后再同步本轮实际涉及的料号，避免前置同步读取上一轮成本行。
      materialMasterSyncService.syncByOaNoAndPeriod(scope.oaNo(), scope.periodMonth());

      stage = STEP_PRICE_TYPE;
      ProductCostingResult typeBlocked = priceTypeBlock(scope, workbench);
      if (typeBlocked != null) {
        return typeBlocked;
      }

      stage = STEP_PRICE;
      QuotePricePrepareGenerateRequest priceRequest = new QuotePricePrepareGenerateRequest();
      priceRequest.setPeriodMonth(scope.periodMonth());
      QuotePricePrepareWorkbenchResponse prices =
          pricePrepareService.generate(scope.oaNo(), scope.itemId(), priceRequest);
      ProductCostingResult priceBlocked = priceBlock(scope, prices);
      if (priceBlocked != null) {
        return priceBlocked;
      }
      PricePrepareReadinessResult readiness = prices.getReadiness();
      String prepareNo = required(readiness.getPrepareNo(), "最终价格批次");
      stateService.bindCurrentPriceFingerprint(
          scope.oaNo(), scope.itemId(), scope.periodMonth(), prepareNo);

      stage = STEP_COST;
      var sourceCheck = technicalSources.afterCosting(scope);
      var technicalBlocked = technicalBlock(scope, sourceCheck);
      if (technicalBlocked != null) return technicalBlocked;
      // 价格准备可能切换实际补录来源，成本版本必须绑定本轮最终输入。
      scope = contextResolver.resolveRevision(scope);
      QuoteCostRunTrialRequest costRequest = new QuoteCostRunTrialRequest();
      costRequest.setPeriodMonth(scope.periodMonth());
      costRequest.setPricePrepareNo(prepareNo);
      costRequest.setSourceRevision(scope.sourceRevision());
      QuoteCostRunWorkbenchResponse cost =
          costRunService.runToSuccess(
              scope.oaNo(), scope.itemId(), costRequest, scope.initiatedBy());
      QuoteCostRunSummaryResponse version = cost.getCurrentDisplayVersion();
      if (version == null || !QuoteCostRunStatus.isCurrentSuccess(version.getStatus())) {
        throw new IllegalStateException("成本核算完成后没有生成当前成功版本");
      }
      var result = success(
          scope,
          version,
          prepareNo,
          readiness.getWarningCount(),
          false,
          readiness.getWarningCount() > 0 ? readiness.getMessage() : "产品核算成功");
      result.setTechnicalDataCheck(sourceCheck);
      return result;
    } catch (RuntimeException exception) {
      return failure(scope, stage, exception);
    }
  }

  private ProductCostingResult failure(
      ProductCostingContext scope, String stage, RuntimeException exception) {
    try {
      // 原补录退回或财务确认失效时，不能用失败前的输入版本冒充本次成功。
      ProductCostingResult concurrent = reusableSuccess(contextResolver.resolveRevision(scope));
      if (concurrent != null) return concurrent;
    } catch (RuntimeException lookupFailure) {
      // 故障后的并发结果探测不能覆盖本次真正的错误及其重试属性。
      if (lookupFailure != exception) exception.addSuppressed(lookupFailure);
    }
    var failure = failurePolicy.classify(stage, exception);
    if (failure.blocked()) {
      return blocked(scope, failure.status(), failure.step(), failure.errorCode(),
          failure.message(), failure.gapCount());
    }
    stateService.markSystemFailed(scope.oaNo(), scope.itemId(), scope.periodMonth(),
        failure.step(), failure.errorCode(), failure.message());
    ProductCostingResult result = base(scope);
    result.setPipelineStatus("FAILED");
    result.setBlockingStatus(failure.status());
    result.setCurrentStep(failure.step());
    result.setErrorCode(failure.errorCode());
    result.setMessage(failure.message());
    result.setGapCount(0);
    result.setWarningCount(0);
    result.setRetryable(failure.retryable());
    return result;
  }

  private ProductCostingResult bomBlock(
      ProductCostingContext scope,
      QuoteCostingWorkbenchResponse workbench) {
    QuoteCostingWorkflowStatusResponse workflow =
        workbench == null ? null : workbench.getWorkflowStatus();
    if (workflow != null && "DONE".equals(workflow.getQuoteBomStatus())) {
      return null;
    }
    QuoteBomStatusItemResponse bom = workbench == null ? null : workbench.getBomStatus();
    if (bom != null && "CHECK_FAILED".equals(bom.getBomStatus())) {
      throw new IllegalStateException(firstText(bom.getErrorMessage(), "BOM 来源检查失败，请核实后重试"));
    }
    String message = firstText(
        bom == null ? null : bom.getErrorMessage(),
        "当前产品没有可用于核算的 BOM，请由产品技术补录后重试");
    return blocked(
        scope, "WAIT_BOM", STEP_BOM, "BOM_MISSING", message, 1);
  }

  private ProductCostingResult priceTypeBlock(
      ProductCostingContext scope, QuoteCostingWorkbenchResponse workbench) {
    QuoteCostingWorkflowStatusResponse workflow =
        workbench == null ? null : workbench.getWorkflowStatus();
    QuotePriceTypeRecognitionSummaryResponse type =
        workbench == null ? null : workbench.getLatestPriceTypeRecognition();
    int gaps = valueOrZero(type == null ? null : type.getGapCount());
    if (workflow != null
        && "DONE".equals(workflow.getPriceTypeConfirmationStatus())
        && gaps == 0) {
      return null;
    }
    String message = firstText(
        type == null ? null : type.getMessage(),
        gaps > 0 ? "存在 " + gaps + " 项物料无法识别价格类型" : "价格类型尚未识别完成");
    return blocked(
        scope,
        "WAIT_PRICE_TYPE",
        STEP_PRICE_TYPE,
        "PRICE_TYPE_MISSING",
        message,
        Math.max(1, gaps));
  }

  private ProductCostingResult priceBlock(
      ProductCostingContext scope, QuotePricePrepareWorkbenchResponse prices) {
    var correction=prices==null?null:prices.getTechnicalPriceCorrection();
    if(correction!=null && !correction.items().isEmpty())return blocked(scope,"WAIT_PRICE",STEP_PRICE,"TECH_PRICE_CORRECTION_REQUIRED",
        "本产品有 "+correction.items().size()+" 项自行公式尚未形成可用价格，请在价格源维护下载修正后导入",correction.items().size());
    PricePrepareReadinessResult readiness = prices == null ? null : prices.getReadiness();
    if (readiness != null
        && "READY".equals(readiness.getStatus())
        && "SUCCESS".equals(readiness.getBatchStatus())
        && readiness.getGapCount() == 0
        && StringUtils.hasText(readiness.getPrepareNo())) {
      return null;
    }
    int gaps = readiness == null ? 1 : Math.max(1, readiness.getGapCount());
    String message = firstText(
        readiness == null ? null : readiness.getMessage(),
        "存在 " + gaps + " 项最终价格缺口");
    return blocked(scope, "WAIT_PRICE", STEP_PRICE, "PRICE_MISSING", message, gaps);
  }

  private ProductCostingResult blocked(
      ProductCostingContext scope,
      String blockingStatus,
      String step,
      String errorCode,
      String message,
      int gapCount) {
    stateService.markBlocked(
        scope.oaNo(),
        scope.itemId(),
        scope.periodMonth(),
        blockingStatus,
        step,
        errorCode,
        message,
        gapCount);
    ProductCostingResult result = base(scope);
    result.setPipelineStatus("BLOCKED");
    result.setBlockingStatus(blockingStatus);
    result.setCurrentStep(step);
    result.setErrorCode(errorCode);
    result.setMessage(message);
    result.setGapCount(gapCount);
    result.setWarningCount(0);
    return result;
  }

  private ProductCostingResult reusableSuccess(ProductCostingContext scope) {
    return successLookup.find(scope)
        .map(reused -> {
          var check = technicalSources.afterCosting(scope);
          var blocked = technicalBlock(scope, check);
          if (blocked != null) return blocked;
          var result = success(scope, summary(reused.version()), reused.prepareNo(),
              reused.warningCount(), true, "当前输入已核算成功，本次直接复用现有版本");
          result.setTechnicalDataCheck(check);
          return result;
        })
        .orElse(null);
  }

  private ProductCostingResult technicalBlock(ProductCostingContext scope, TechnicalDataSourceCheckResponse check) {
    if (check == null) throw new IllegalStateException("补录来源检查没有返回有效结论");
    var gaps = check.modules().stream().filter(module -> module.required()
        || module.availability() == TechnicalDataAvailability.ERROR
        || "SALARY".equals(module.moduleType()) && module.availability() == TechnicalDataAvailability.UNCONFIRMED).toList();
    if (gaps.isEmpty()) return null;
    boolean queryError = gaps.stream().anyMatch(module -> module.availability() == TechnicalDataAvailability.ERROR)
        || check.sharedModules().stream().anyMatch(source -> "ERROR".equals(source.status()));
    if (!queryError) {
      var input = effectiveTechnicalData.resolve(scope.itemId(), scope.periodMonth());
      if (input != null) {
        var supplied = input.moduleSources().stream().map(
            com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput.ModuleSource::moduleType).toList();
        if (gaps.stream().allMatch(gap -> supplied.contains(gap.moduleType()))) return null;
      }
    }
    var result = blocked(scope, "WAIT_TECH_DATA", "TECHNICAL_DATA",
        queryError ? "TECH_SOURCE_QUERY_FAILED" : "TECH_SOURCE_NOT_READY",
        String.join("；", gaps.stream().map(module -> check.sharedModules().stream()
            .filter(source -> source.moduleType().equals(module.moduleType()))
            .map(source -> source.message()).findFirst().orElse(module.reason())).distinct().toList()), gaps.size());
    result.setTechnicalDataCheck(check);
    return result;
  }

  private ProductCostingResult success(
      ProductCostingContext scope,
      QuoteCostRunSummaryResponse version,
      String prepareNo,
      int warningCount,
      boolean reused,
      String message) {
    ProductCostingResult result = base(scope);
    result.setPipelineStatus("SUCCESS");
    result.setBlockingStatus("NONE");
    result.setCurrentStep(STEP_COST);
    result.setMessage(message);
    result.setGapCount(0);
    result.setWarningCount(warningCount);
    result.setPricePrepareNo(prepareNo);
    result.setCostVersionId(version.getId());
    result.setCostRunNo(version.getCostRunNo());
    result.setVersionNo(version.getVersionNo());
    result.setTotalCost(version.getTotalCost());
    result.setReusedSuccess(reused);
    return result;
  }

  private QuoteCostRunSummaryResponse summary(QuoteCostRunVersion version) {
    QuoteCostRunSummaryResponse result = new QuoteCostRunSummaryResponse();
    result.setId(version.getId());
    result.setCostRunNo(version.getCostRunNo());
    result.setVersionNo(version.getVersionNo());
    result.setStatus(version.getStatus());
    result.setTotalCost(version.getTotalCost());
    return result;
  }

  private ProductCostingResult base(ProductCostingContext scope) {
    ProductCostingResult result = new ProductCostingResult();
    result.setOaNo(scope.oaNo());
    result.setOaFormItemId(scope.itemId());
    result.setProductCode(scope.productCode());
    result.setPeriodMonth(scope.periodMonth());
    return result;
  }

  private String required(String value, String label) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    return value.trim();
  }

  private String firstText(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private int valueOrZero(Integer value) {
    return value == null ? 0 : value;
  }

}
