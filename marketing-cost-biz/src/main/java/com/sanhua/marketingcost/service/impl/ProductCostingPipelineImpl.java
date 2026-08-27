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
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.enums.QuoteCostRunStatus;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.CostingAlgorithmVersionProvider;
import com.sanhua.marketingcost.service.CostInputRevisionService;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import com.sanhua.marketingcost.service.ProductCostingPipeline;
import com.sanhua.marketingcost.service.ProductCostingCollaborationService;
import com.sanhua.marketingcost.service.ProductCostingStateService;
import com.sanhua.marketingcost.service.QuoteCurrentSuccessMatcher;
import com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService;
import com.sanhua.marketingcost.service.QuoteCostingWorkbenchService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.collaboration.CollaborationCostingPendingException;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.dao.TransientDataAccessException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLTransientException;

@Service
public class ProductCostingPipelineImpl implements ProductCostingPipeline {

  static final String STEP_BOM = "QUOTE_BOM";
  static final String STEP_PRICE_TYPE = "PRICE_TYPE_CONFIRMATION";
  static final String STEP_PRICE = "PRICE_PREPARE";
  static final String STEP_COST = "COST_RUN";

  private final QuoteCostingWorkbenchService costingWorkbenchService;
  private final QuotePricePrepareWorkbenchService pricePrepareService;
  private final QuoteCostRunWorkbenchService costRunService;
  private final ProductCostingStateService stateService;
  private final QuoteCostingWorkspaceService workspaceService;
  private final OaFormItemMapper itemMapper;
  private final OaFormMapper formMapper;
  private final QuoteCostRunVersionMapper versionMapper;
  private final ProductCostingCollaborationService collaborationService;
  private final CostingAlgorithmVersionProvider algorithmVersionProvider;
  private final CostInputRevisionService inputRevisionService;
  private final MaterialMasterSyncService materialMasterSyncService;

  @Autowired
  public ProductCostingPipelineImpl(
      QuoteCostingWorkbenchService costingWorkbenchService,
      QuotePricePrepareWorkbenchService pricePrepareService,
      QuoteCostRunWorkbenchService costRunService,
      ProductCostingStateService stateService,
      QuoteCostingWorkspaceService workspaceService,
      OaFormItemMapper itemMapper,
      OaFormMapper formMapper,
      QuoteCostRunVersionMapper versionMapper,
      ProductCostingCollaborationService collaborationService,
      CostingAlgorithmVersionProvider algorithmVersionProvider,
      CostInputRevisionService inputRevisionService,
      MaterialMasterSyncService materialMasterSyncService) {
    this.costingWorkbenchService = costingWorkbenchService;
    this.pricePrepareService = pricePrepareService;
    this.costRunService = costRunService;
    this.stateService = stateService;
    this.workspaceService = workspaceService;
    this.itemMapper = itemMapper;
    this.formMapper = formMapper;
    this.versionMapper = versionMapper;
    this.collaborationService = collaborationService;
    this.algorithmVersionProvider = algorithmVersionProvider;
    this.inputRevisionService = inputRevisionService;
    this.materialMasterSyncService = materialMasterSyncService;
  }

  ProductCostingPipelineImpl(
      QuoteCostingWorkbenchService costingWorkbenchService,
      QuotePricePrepareWorkbenchService pricePrepareService,
      QuoteCostRunWorkbenchService costRunService,
      ProductCostingStateService stateService,
      QuoteCostingWorkspaceService workspaceService,
      OaFormItemMapper itemMapper,
      QuoteCostRunVersionMapper versionMapper,
      ProductCostingCollaborationService collaborationService,
      CostingAlgorithmVersionProvider algorithmVersionProvider) {
    this(
        costingWorkbenchService,
        pricePrepareService,
        costRunService,
        stateService,
        workspaceService,
        itemMapper,
        null,
        versionMapper,
        collaborationService,
        algorithmVersionProvider,
        null,
        null);
  }

  @Override
  public ProductCostingResult execute(ProductCostingRequest request) {
    RequestScope scope = requireScope(request);
    ProductCostingResult reusable = request.force() ? null : reusableSuccess(scope);
    if (reusable != null) {
      return reusable;
    }

    String stage = STEP_BOM;
    try {
      QuoteCostingWorkbenchResponse workbench =
          costingWorkbenchService.launchWorkbench(scope.oaNo(), scope.itemId());
      ProductCostingResult bomBlocked = bomBlock(scope, workbench);
      if (bomBlocked != null) {
        return bomBlocked;
      }
      // BOM 行生成后再同步本轮实际涉及的料号，避免前置同步读取上一轮成本行。
      if (materialMasterSyncService != null) {
        materialMasterSyncService.syncByOaNoAndPeriod(scope.oaNo(), scope.periodMonth());
      }

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
      return success(
          scope,
          version,
          prepareNo,
          readiness.getWarningCount(),
          false,
          readiness.getWarningCount() > 0 ? readiness.getMessage() : "产品核算成功");
    } catch (RuntimeException exception) {
      ProductCostingResult concurrent = reusableSuccess(scope);
      if (concurrent != null) {
        return concurrent;
      }
      if (exception instanceof CollaborationCostingPendingException pending) {
        return blocked(
            scope,
            pending.blockingStatus(),
            stepFor(pending.blockingStatus()),
            pending.errorCode(),
            pending.getMessage(),
            pending.gapCount());
      }
      if (STEP_BOM.equals(stage) && isBomBusinessGap(exception)) {
        return blocked(
            scope,
            "WAIT_BOM",
            stage,
            "BOM_MISSING",
            message(exception),
            1);
      }
      if (STEP_PRICE_TYPE.equals(stage) && isPriceTypeGap(exception)) {
        return blocked(
            scope,
            "WAIT_PRICE_TYPE",
            stage,
            "PRICE_TYPE_MISSING",
            exception.getMessage(),
            1);
      }
      if (STEP_PRICE.equals(stage) && isPriceBusinessGap(exception)) {
        return blocked(
            scope,
            "WAIT_PRICE",
            stage,
            priceErrorCode(exception),
            exception.getMessage(),
            1);
      }
      String code = systemErrorCode(stage);
      stateService.markSystemFailed(
          scope.oaNo(), scope.itemId(), scope.periodMonth(), stage, code, exception.getMessage());
      ProductCostingResult failed = base(scope);
      failed.setPipelineStatus("FAILED");
      failed.setBlockingStatus("SYSTEM_FAILED");
      failed.setCurrentStep(stage);
      failed.setErrorCode(code);
      failed.setMessage(message(exception));
      failed.setGapCount(0);
      failed.setWarningCount(0);
      failed.setRetryable(isRetryableSystemFailure(exception));
      return failed;
    }
  }

  private ProductCostingResult bomBlock(
      RequestScope scope, QuoteCostingWorkbenchResponse workbench) {
    QuoteCostingWorkflowStatusResponse workflow =
        workbench == null ? null : workbench.getWorkflowStatus();
    if (workflow != null && "DONE".equals(workflow.getQuoteBomStatus())) {
      return null;
    }
    QuoteBomStatusItemResponse bom = workbench == null ? null : workbench.getBomStatus();
    String message = firstText(
        bom == null ? null : bom.getErrorMessage(),
        "当前产品没有可用于核算的 BOM，请由产品技术补录后重试");
    return blocked(scope, "WAIT_BOM", STEP_BOM, "BOM_MISSING", message, 1);
  }

  private ProductCostingResult priceTypeBlock(
      RequestScope scope, QuoteCostingWorkbenchResponse workbench) {
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
      RequestScope scope, QuotePricePrepareWorkbenchResponse prices) {
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
    String transientPrepareNo = prices == null || prices.getGeneratedResult() == null
        ? null : prices.getGeneratedResult().getPrepareNo();
    return blocked(
        scope, "WAIT_PRICE", STEP_PRICE, "PRICE_MISSING", message, gaps,
        transientPrepareNo);
  }

  private ProductCostingResult blocked(
      RequestScope scope,
      String blockingStatus,
      String step,
      String errorCode,
      String message,
      int gapCount) {
    return blocked(scope, blockingStatus, step, errorCode, message, gapCount, null);
  }

  private ProductCostingResult blocked(
      RequestScope scope,
      String blockingStatus,
      String step,
      String errorCode,
      String message,
      int gapCount,
      String transientPrepareNo) {
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
    ProductCostingCollaborationService.CoordinationResult coordination =
        collaborationService.coordinate(
            new ProductCostingCollaborationService.CoordinationCommand(
                scope.oaNo(),
                scope.itemId(),
                scope.periodMonth(),
                blockingStatus,
                errorCode,
                scope.initiatedBy(),
                transientPrepareNo));
    if (coordination != null) {
      result.setCollaborationTaskId(coordination.productTaskId());
      result.setCollaborationStatus(coordination.status());
      result.setCollaborationAssigneeName(coordination.assigneeName());
      result.setCollaborationMessage(coordination.message());
    }
    return result;
  }

  private ProductCostingResult reusableSuccess(RequestScope scope) {
    QuoteCostingWorkspace workspace =
        workspaceService.find(scope.itemId(), scope.periodMonth()).orElse(null);
    OaFormItem item = itemMapper.selectById(scope.itemId());
    QuoteCostRunVersion version =
        workspace == null || workspace.getCurrentCostVersionId() == null
            ? null
            : versionMapper.selectById(workspace.getCurrentCostVersionId());
    boolean current = inputRevisionService == null
        ? QuoteCurrentSuccessMatcher.matches(
            scope.oaNo(), scope.itemId(), scope.periodMonth(), item, workspace, version,
            algorithmVersionProvider.currentVersion())
        : QuoteCurrentSuccessMatcher.matches(
            scope.oaNo(), scope.itemId(), scope.periodMonth(), item, workspace, version,
            algorithmVersionProvider.currentVersion(), scope.sourceRevision());
    if (!current) {
      return null;
    }
    return success(
        scope,
        summary(version),
        firstText(version.getOaPricePrepareNo(), workspace.getCurrentPrepareNo()),
        valueOrZero(workspace.getCarriedForwardPriceCount()),
        true,
        "当前输入已核算成功，本次直接复用现有版本");
  }

  private ProductCostingResult success(
      RequestScope scope,
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

  private ProductCostingResult base(RequestScope scope) {
    ProductCostingResult result = new ProductCostingResult();
    result.setOaNo(scope.oaNo());
    result.setOaFormItemId(scope.itemId());
    result.setProductCode(scope.productCode());
    result.setPeriodMonth(scope.periodMonth());
    return result;
  }

  private RequestScope requireScope(ProductCostingRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("产品核算请求不能为空");
    }
    String oaNo = required(request.oaNo(), "OA单号");
    if (request.oaFormItemId() == null || request.oaFormItemId() <= 0) {
      throw new IllegalArgumentException("报价产品行 ID 必须大于0");
    }
    String period = CostPricingPeriodUtils.requireCurrentPricingMonth(request.periodMonth());
    OaFormItem item = itemMapper.selectById(request.oaFormItemId());
    if (item == null) {
      throw new QuoteIngestException("报价产品行不存在: " + request.oaFormItemId());
    }
    String productCode = QuoteProductIdentityUtils.resolveCostingCode(item);
    if (!StringUtils.hasText(productCode)) {
      throw new QuoteIngestException("产品料号、三花型号和客户图号至少填写一个");
    }
    OaForm form = null;
    String sourceRevision = null;
    if (formMapper != null && inputRevisionService != null) {
      form = formMapper.selectOne(
          com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(OaForm.class)
              .eq(OaForm::getOaNo, oaNo)
              .last("LIMIT 1"));
      if (form == null || !java.util.Objects.equals(form.getId(), item.getOaFormId())) {
        throw new QuoteIngestException("报价产品行不存在或不属于当前报价单");
      }
      sourceRevision = inputRevisionService.currentRevision(form, item);
    }
    return new RequestScope(
        oaNo,
        request.oaFormItemId(),
        productCode,
        period,
        firstText(request.initiatedBy(), "system"),
        sourceRevision);
  }

  private boolean isPriceTypeGap(RuntimeException exception) {
    String text = message(exception);
    return text.contains("价格类型") || text.contains("无法识别");
  }

  private boolean isBomBusinessGap(RuntimeException exception) {
    if (!(exception instanceof QuoteIngestException)) {
      return false;
    }
    String text = message(exception);
    return text.contains("BOM 准备结果")
        || text.contains("正式 BOM 准备结果为空")
        || text.contains("完整 BOM 准备结果为空")
        || text.contains("没有可用于核算的 BOM")
        || text.contains("补录任务");
  }

  private boolean isPriceBusinessGap(RuntimeException exception) {
    String text = message(exception);
    return (exception instanceof QuoteIngestException
            || exception instanceof IllegalArgumentException)
        && (text.contains("价格")
            || text.contains("基准")
            || text.contains("供应商")
            || text.contains("供货"));
  }

  private String priceErrorCode(RuntimeException exception) {
    String text = message(exception);
    return text.contains("财务") && text.contains("基准")
        ? "FINANCE_BASE_PRICE_MISSING"
        : "PRICE_MISSING";
  }

  private String systemErrorCode(String stage) {
    return switch (stage) {
      case STEP_BOM -> "BOM_SYSTEM_ERROR";
      case STEP_PRICE_TYPE -> "PRICE_TYPE_SYSTEM_ERROR";
      case STEP_PRICE -> "PRICE_PREPARE_SYSTEM_ERROR";
      default -> "COST_RUN_SYSTEM_ERROR";
    };
  }

  private boolean isRetryableSystemFailure(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof TransientDataAccessException
          || current instanceof SQLTransientException
          || current instanceof SocketTimeoutException
          || current instanceof ConnectException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private String stepFor(String blockingStatus) {
    return switch (blockingStatus) {
      case "WAIT_PRICE_TYPE" -> STEP_PRICE_TYPE;
      case "WAIT_PRICE" -> STEP_PRICE;
      default -> STEP_BOM;
    };
  }

  private String message(Throwable throwable) {
    return firstText(throwable == null ? null : throwable.getMessage(), "产品核算失败");
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

  private record RequestScope(
      String oaNo,
      Long itemId,
      String productCode,
      String periodMonth,
      String initiatedBy,
      String sourceRevision) {}
}
