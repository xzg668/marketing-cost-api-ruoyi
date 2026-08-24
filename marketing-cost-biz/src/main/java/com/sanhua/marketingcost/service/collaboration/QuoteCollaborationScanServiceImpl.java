package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductTypeResolveResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.enums.QuoteProductType;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.service.QuoteProductTypeResolveService;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductForm;
import com.sanhua.marketingcost.service.collaboration.scan.ApprovedSourceInspection;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import com.sanhua.marketingcost.service.collaboration.scan.CurrentU9BomResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationApprovedSourceInspector;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationCurrentU9BomGateway;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationPriceScanGateway;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanAction;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanContext;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanErrorCode;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanStage;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanStatus;
import com.sanhua.marketingcost.service.ingest.QuoteBomContext;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteCollaborationScanServiceImpl implements QuoteCollaborationScanService {

  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  private final OaFormItemMapper itemMapper;
  private final OaFormMapper formMapper;
  private final QuoteBomPreparationRecordMapper preparationRecordMapper;
  private final QuoteBomContextResolver contextResolver;
  private final QuoteCollaborationCurrentU9BomGateway u9BomGateway;
  private final QuoteProductTypeResolveService productTypeService;
  private final QuoteCollaborationTaskRepository taskRepository;
  private final QuoteCollaborationReviewRepository reviewRepository;
  private final QuoteCollaborationApprovedSourceInspector approvedSourceInspector;
  private final QuoteCollaborationPriceScanGateway priceScanGateway;
  private final Clock clock;

  @Autowired
  public QuoteCollaborationScanServiceImpl(
      OaFormItemMapper itemMapper,
      OaFormMapper formMapper,
      QuoteBomPreparationRecordMapper preparationRecordMapper,
      QuoteBomContextResolver contextResolver,
      QuoteCollaborationCurrentU9BomGateway u9BomGateway,
      QuoteProductTypeResolveService productTypeService,
      QuoteCollaborationTaskRepository taskRepository,
      QuoteCollaborationReviewRepository reviewRepository,
      QuoteCollaborationApprovedSourceInspector approvedSourceInspector,
      QuoteCollaborationPriceScanGateway priceScanGateway) {
    this(
        itemMapper,
        formMapper,
        preparationRecordMapper,
        contextResolver,
        u9BomGateway,
        productTypeService,
        taskRepository,
        reviewRepository,
        approvedSourceInspector,
        priceScanGateway,
        Clock.system(BUSINESS_ZONE));
  }

  QuoteCollaborationScanServiceImpl(
      OaFormItemMapper itemMapper,
      OaFormMapper formMapper,
      QuoteBomPreparationRecordMapper preparationRecordMapper,
      QuoteBomContextResolver contextResolver,
      QuoteCollaborationCurrentU9BomGateway u9BomGateway,
      QuoteProductTypeResolveService productTypeService,
      QuoteCollaborationTaskRepository taskRepository,
      QuoteCollaborationReviewRepository reviewRepository,
      QuoteCollaborationApprovedSourceInspector approvedSourceInspector,
      QuoteCollaborationPriceScanGateway priceScanGateway,
      Clock clock) {
    this.itemMapper = itemMapper;
    this.formMapper = formMapper;
    this.preparationRecordMapper = preparationRecordMapper;
    this.contextResolver = contextResolver;
    this.u9BomGateway = u9BomGateway;
    this.productTypeService = productTypeService;
    this.taskRepository = taskRepository;
    this.reviewRepository = reviewRepository;
    this.approvedSourceInspector = approvedSourceInspector;
    this.priceScanGateway = priceScanGateway;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
  public QuoteCollaborationScanResult scanQuoteItem(Long oaFormItemId) {
    return scanQuoteItem(oaFormItemId, null);
  }

  @Override
  @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
  public QuoteCollaborationScanResult scanQuoteItem(
      Long oaFormItemId, String requestedAccountingMonth) {
    QuoteCollaborationScanContext context = loadContext(oaFormItemId, requestedAccountingMonth);
    List<QuoteCollaborationScanStage> stages = new ArrayList<>();

    CurrentU9BomResult u9 = readU9(context, stages);
    if (u9 == null) {
      return blocked(
          context,
          ProductForm.UNKNOWN,
          stages,
          QuoteCollaborationScanErrorCode.U9_DATA_EMPTY,
          "U9当前BOM查询没有返回结果");
    }
    QuoteCollaborationScanResult u9Failure = u9Failure(context, u9, stages);
    if (u9Failure != null) {
      return u9Failure;
    }
    if (!StringUtils.hasText(context.productCode())) {
      return scanTemporaryProduct(context, u9, stages);
    }

    QuoteProductTypeResolveResult typeResult;
    try {
      typeResult =
          productTypeService.resolve(
              context.productCode(), context.materialOrganizationCode());
    } catch (RuntimeException exception) {
      stages.add(QuoteCollaborationScanStage.PRODUCT_FORM);
      return blocked(
          context,
          ProductForm.UNKNOWN,
          stages,
          QuoteCollaborationScanErrorCode.PRODUCT_FORM_DATA_MISSING,
          "产品形态读取失败：" + exceptionMessage(exception));
    }
    stages.add(QuoteCollaborationScanStage.PRODUCT_FORM);
    ProductForm productForm = toProductForm(typeResult);
    if (productForm == ProductForm.UNKNOWN) {
      return blocked(
          context,
          productForm,
          stages,
          QuoteCollaborationScanErrorCode.PRODUCT_FORM_DATA_MISSING,
          firstText(typeResult == null ? null : typeResult.errorMessage(), "产品形态无法判断"));
    }

    boolean u9Available = u9.status() == CurrentU9BomResult.Status.AVAILABLE;
    PrimaryScope structuralScope =
        u9Available
            ? (productForm == ProductForm.BARE ? PrimaryScope.BARE_PACKAGE : PrimaryScope.PRICE_ONLY)
            : PrimaryScope.FULL_BOM;
    CollaborationScope persistenceScope =
        new CollaborationScope(context.businessUnitType(), context.priceOrgCode());

    QuoteCollaborationProductTask active;
    try {
      active = findActiveTask(context, persistenceScope, structuralScope);
    } catch (RuntimeException exception) {
      stages.add(QuoteCollaborationScanStage.SAME_MONTH_ACTIVE_TASK);
      return blocked(
          context,
          productForm,
          stages,
          QuoteCollaborationScanErrorCode.ACTIVE_TASK_QUERY_ERROR,
          "同月协作任务读取失败：" + exceptionMessage(exception));
    }
    stages.add(QuoteCollaborationScanStage.SAME_MONTH_ACTIVE_TASK);
    if (active != null) {
      return activeTaskResult(context, productForm, u9, structuralScope, active, stages);
    }

    QuoteCollaborationApprovedResult reusable = null;
    ApprovedSourceInspection inspectedSource = null;
    Long expiredReferenceId = null;
    String invalidSourceMessage = null;
    if (!u9Available || productForm == ProductForm.BARE) {
      List<QuoteCollaborationApprovedResult> approvedResults;
      try {
        approvedResults =
            reviewRepository.findValidResults(
                context.productCode(),
                structuralScope.code(),
                context.scanAt(),
                persistenceScope);
      } catch (RuntimeException exception) {
        stages.add(QuoteCollaborationScanStage.SIX_MONTH_APPROVED_RESULT);
        return blocked(
            context,
            productForm,
            stages,
            QuoteCollaborationScanErrorCode.APPROVED_RESULT_QUERY_ERROR,
            "半年审核结果读取失败：" + exceptionMessage(exception));
      }
      stages.add(QuoteCollaborationScanStage.SIX_MONTH_APPROVED_RESULT);
      for (QuoteCollaborationApprovedResult candidate : safeList(approvedResults)) {
        ApprovedSourceInspection inspection;
        try {
          inspection = approvedSourceInspector.inspect(candidate, context, u9);
        } catch (RuntimeException exception) {
          stages.add(QuoteCollaborationScanStage.APPROVED_SOURCE);
          return blocked(
              context,
              productForm,
              stages,
              QuoteCollaborationScanErrorCode.APPROVED_SOURCE_ERROR,
              "已审核来源读取失败：" + exceptionMessage(exception));
        }
        if (!stages.contains(QuoteCollaborationScanStage.APPROVED_SOURCE)) {
          stages.add(QuoteCollaborationScanStage.APPROVED_SOURCE);
        }
        if (inspection == null || inspection.status() == ApprovedSourceInspection.Status.ERROR) {
          return blocked(
              context,
              productForm,
              stages,
              QuoteCollaborationScanErrorCode.APPROVED_SOURCE_ERROR,
              inspection == null ? "已审核来源检查没有返回结果" : inspection.message());
        }
        if (inspection.status() == ApprovedSourceInspection.Status.READY) {
          reusable = candidate;
          inspectedSource = inspection;
          break;
        }
        invalidSourceMessage = firstText(
            inspection.message(), "已审核结果来源发生变化，请重新确认");
      }
      if (reusable == null && invalidSourceMessage == null) {
        try {
          QuoteCollaborationApprovedResult expired = reviewRepository
              .findLatestExpiredReference(
                  context.productCode(), structuralScope.code(),
                  context.scanAt(), persistenceScope)
              .orElse(null);
          if (expired != null) {
            ApprovedSourceInspection inspection = approvedSourceInspector.inspect(
                expired, context, u9);
            if (inspection != null
                && inspection.status() == ApprovedSourceInspection.Status.READY) {
              expiredReferenceId = expired.getId();
            }
          }
        } catch (RuntimeException ignored) {
          // 到期结果只用于可选预填；读取失败不能把真实的新技术任务伪装成系统阻断。
          expiredReferenceId = null;
        }
      }
    }

    boolean candidateBomReady =
        u9Available && productForm == ProductForm.NORMAL
            || productForm == ProductForm.BARE && inspectedSource != null
            || !u9Available && inspectedSource != null;
    if (!candidateBomReady) {
      PrimaryScope required = u9Available ? PrimaryScope.BARE_PACKAGE : PrimaryScope.FULL_BOM;
      String source = u9Available ? "U9_BODY" : null;
      String message =
          invalidSourceMessage != null
              ? invalidSourceMessage + "，请技术重新确认"
              : expiredReferenceId != null
                  ? "最近审核结果已到期，可作为预填参考，必须由技术重新确认"
                  : required == PrimaryScope.BARE_PACKAGE
                      ? "U9本体BOM已取得，请技术补包装"
                      : "U9无当前有效BOM，请技术补完整BOM";
      CollaborationPriceScanResult price =
          required == PrimaryScope.BARE_PACKAGE
              ? CollaborationPriceScanResult.pendingPackage("目标包装形成后再检查真实缺价")
              : CollaborationPriceScanResult.pendingBom("完整BOM形成后再检查真实缺价");
      return result(
          context,
          productForm,
          QuoteCollaborationScanStatus.COLLABORATION_REQUIRED,
          QuoteCollaborationScanAction.CREATE_COLLABORATION,
          required,
          source,
          u9Available ? u9.bomVersion() : null,
          u9Available ? u9.lineCount() : 0,
          null,
          null,
          expiredReferenceId,
          price,
          stages,
          null,
          message);
    }

    CollaborationPriceScanResult price;
    try {
      price = priceScanGateway.check(context);
    } catch (RuntimeException exception) {
      stages.add(QuoteCollaborationScanStage.PRICE_PREPARATION);
      return blocked(
          context,
          productForm,
          stages,
          QuoteCollaborationScanErrorCode.PRICE_PREPARATION_ERROR,
          "价格准备只读检查失败：" + exceptionMessage(exception));
    }
    stages.add(QuoteCollaborationScanStage.PRICE_PREPARATION);
    if (price == null || price.status() == CollaborationPriceScanResult.Status.ERROR) {
      return blocked(
          context,
          productForm,
          stages,
          QuoteCollaborationScanErrorCode.PRICE_PREPARATION_ERROR,
          price == null ? "价格准备检查没有返回结果" : price.message());
    }

    String bomSource =
        inspectedSource != null
            ? (productForm == ProductForm.BARE
                ? "U9_BODY+" + inspectedSource.source()
                : inspectedSource.source())
            : "U9";
    int bomLineCount =
        inspectedSource != null
            ? u9.lineCount() + inspectedSource.lineCount()
            : u9.lineCount();
    Long approvedResultId = reusable == null ? null : reusable.getId();
    if (price.status() == CollaborationPriceScanResult.Status.GAPS) {
      boolean onlyMissingPriceType = !price.gaps().isEmpty()
          && price.gaps().stream().allMatch(gap ->
              "MISSING_PRICE_TYPE".equals(gap.gapType()));
      return result(
          context,
          productForm,
          QuoteCollaborationScanStatus.COLLABORATION_REQUIRED,
          onlyMissingPriceType
              ? QuoteCollaborationScanAction.MAINTAIN_PRICE_TYPE
              : QuoteCollaborationScanAction.CREATE_COLLABORATION,
          PrimaryScope.PRICE_ONLY,
          bomSource,
          u9.bomVersion(),
          bomLineCount,
          null,
          null,
          approvedResultId,
          price,
          stages,
          null,
          onlyMissingPriceType
              ? "BOM已准备，存在" + price.gapCount() + "项缺价格类型，请财务维护物料价格类型"
              : "BOM已准备，本次报价存在" + price.gapCount() + "项真实缺价");
    }
    if (price.status() != CollaborationPriceScanResult.Status.READY) {
      return blocked(
          context,
          productForm,
          stages,
          QuoteCollaborationScanErrorCode.PRICE_PREPARATION_ERROR,
          firstText(price.message(), "价格准备结果状态不完整"));
    }
    return result(
        context,
        productForm,
        reusable == null
            ? QuoteCollaborationScanStatus.READY
            : QuoteCollaborationScanStatus.REUSABLE_RESULT,
        reusable == null
            ? QuoteCollaborationScanAction.NO_COLLABORATION_REQUIRED
            : QuoteCollaborationScanAction.REUSE_APPROVED_RESULT,
        null,
        bomSource,
        u9.bomVersion(),
        bomLineCount,
        null,
        null,
        approvedResultId,
        price,
        stages,
        null,
        reusable == null ? "U9 BOM和价格均已准备" : "已审核结果可复用，本次价格重新检查通过");
  }

  private QuoteCollaborationScanContext loadContext(
      Long oaFormItemId, String requestedAccountingMonth) {
    if (oaFormItemId == null || oaFormItemId <= 0) {
      throw new IllegalArgumentException("报价产品行ID必须为正数");
    }
    OaFormItem item = itemMapper.selectById(oaFormItemId);
    if (item == null) {
      throw new IllegalArgumentException("报价产品行不存在: " + oaFormItemId);
    }
    OaForm form = formMapper.selectById(item.getOaFormId());
    if (form == null) {
      throw new IllegalArgumentException("报价单不存在: " + item.getOaFormId());
    }
    String productCode = trimToNull(item.getMaterialNo());
    QuoteBomPreparationRecord existingPreparation = preparationRecordMapper.selectOne(
        com.baomidou.mybatisplus.core.toolkit.Wrappers.<QuoteBomPreparationRecord>lambdaQuery()
            .eq(QuoteBomPreparationRecord::getOaFormItemId, item.getId())
            .eq(QuoteBomPreparationRecord::getActiveFlag, 1)
            .orderByDesc(QuoteBomPreparationRecord::getUpdatedAt)
            .orderByDesc(QuoteBomPreparationRecord::getId)
            .last("LIMIT 1"));
    String existingCostPeriodMonth = existingPreparation == null
        ? null : trimToNull(existingPreparation.getCostPeriodMonth());
    String requestedMonth = normalizeRequestedMonth(requestedAccountingMonth);
    String accountingMonth;
    QuoteDataOrganization organization;
    if (productCode == null) {
      accountingMonth = requestedMonth == null
          ? contextResolver.resolveCostPeriodMonth(form) : requestedMonth;
      organization = contextResolver.resolveOrganization(form, item);
    } else {
      String effectiveExistingMonth = requestedMonth == null
          ? existingCostPeriodMonth : requestedMonth;
      QuoteBomContext bomContext = effectiveExistingMonth == null
          ? contextResolver.resolve(form, item)
          : contextResolver.resolveWithExistingCostPeriod(form, item, effectiveExistingMonth);
      accountingMonth = bomContext.costPeriodMonth();
      organization = bomContext.organization();
      productCode = bomContext.productCode();
    }
    String businessUnitType =
        firstText(item.getBusinessUnitType(), form.getBusinessUnitType());
    if (!StringUtils.hasText(businessUnitType)) {
      throw new IllegalArgumentException("报价产品缺少业务单元");
    }
    LocalDateTime now = LocalDateTime.now(clock);
    return new QuoteCollaborationScanContext(
        form.getId(),
        item.getId(),
        form.getOaNo(),
        accountingMonth,
        businessUnitType,
        productCode,
        item.getProductName(),
        item.getSpec(),
        item.getSunlModel(),
        organization.priceOrgCode(),
        organization.materialOrganizationCode(),
        LocalDate.now(clock),
        now);
  }

  private String normalizeRequestedMonth(String value) {
    String month = trimToNull(value);
    if (month == null) return null;
    if (!month.matches("\\d{4}-(0[1-9]|1[0-2])")) {
      throw new IllegalArgumentException("核算月份必须为YYYY-MM");
    }
    return month;
  }

  private CurrentU9BomResult readU9(
      QuoteCollaborationScanContext context,
      List<QuoteCollaborationScanStage> stages) {
    try {
      if (!StringUtils.hasText(context.productCode())) {
        return CurrentU9BomResult.notFound("新品暂无正式料号，无法查询U9 BOM");
      }
      return u9BomGateway.read(context);
    } catch (RuntimeException exception) {
      return CurrentU9BomResult.error(exceptionMessage(exception));
    } finally {
      stages.add(QuoteCollaborationScanStage.U9_CURRENT_BOM);
    }
  }

  private QuoteCollaborationScanResult scanTemporaryProduct(
      QuoteCollaborationScanContext context,
      CurrentU9BomResult u9,
      List<QuoteCollaborationScanStage> stages) {
    stages.add(QuoteCollaborationScanStage.PRODUCT_FORM);
    CollaborationScope persistenceScope =
        new CollaborationScope(context.businessUnitType(), context.priceOrgCode());
    QuoteCollaborationProductTask active;
    try {
      active = findActiveTask(context, persistenceScope, PrimaryScope.FULL_BOM);
    } catch (RuntimeException exception) {
      stages.add(QuoteCollaborationScanStage.SAME_MONTH_ACTIVE_TASK);
      return blocked(
          context,
          ProductForm.UNKNOWN,
          stages,
          QuoteCollaborationScanErrorCode.ACTIVE_TASK_QUERY_ERROR,
          "同月协作任务读取失败：" + exceptionMessage(exception));
    }
    stages.add(QuoteCollaborationScanStage.SAME_MONTH_ACTIVE_TASK);
    if (active != null) {
      return activeTaskResult(
          context, ProductForm.UNKNOWN, u9, PrimaryScope.FULL_BOM, active, stages);
    }
    return result(
        context,
        ProductForm.UNKNOWN,
        QuoteCollaborationScanStatus.COLLABORATION_REQUIRED,
        QuoteCollaborationScanAction.CREATE_COLLABORATION,
        PrimaryScope.FULL_BOM,
        null,
        null,
        0,
        null,
        null,
        null,
        CollaborationPriceScanResult.pendingBom("完整BOM形成后再检查真实缺价"),
        stages,
        null,
        "新品暂无正式料号，请技术补完整BOM");
  }

  private QuoteCollaborationScanResult u9Failure(
      QuoteCollaborationScanContext context,
      CurrentU9BomResult u9,
      List<QuoteCollaborationScanStage> stages) {
    return switch (u9.status()) {
      case AVAILABLE, NOT_FOUND -> null;
      case TIMEOUT ->
          blocked(
              context,
              ProductForm.UNKNOWN,
              stages,
              QuoteCollaborationScanErrorCode.U9_TIMEOUT,
              firstText(u9.message(), "U9当前BOM查询超时"));
      case ORGANIZATION_MISMATCH ->
          blocked(
              context,
              ProductForm.UNKNOWN,
              stages,
              QuoteCollaborationScanErrorCode.U9_ORGANIZATION_MISMATCH,
              firstText(u9.message(), "U9 BOM组织不匹配"));
      case DATA_EMPTY ->
          blocked(
              context,
              ProductForm.UNKNOWN,
              stages,
              QuoteCollaborationScanErrorCode.U9_DATA_EMPTY,
              firstText(u9.message(), "U9当前BOM返回空数据"));
      case ERROR ->
          blocked(
              context,
              ProductForm.UNKNOWN,
              stages,
              QuoteCollaborationScanErrorCode.U9_ERROR,
              firstText(u9.message(), "U9当前BOM查询失败"));
    };
  }

  private QuoteCollaborationProductTask findActiveTask(
      QuoteCollaborationScanContext context,
      CollaborationScope scope,
      PrimaryScope primaryScope) {
    QuoteCollaborationProductTask linked = activeTaskLinkedToQuoteItem(context, scope);
    if (linked != null) {
      return linked;
    }
    String temporaryProductKey = StringUtils.hasText(context.productCode())
        ? null
        : CollaborationTemporaryProductKeyFactory.fromQuoteItem(context.oaFormItemId());
    String activeLockKey = CollaborationActiveLockKeyFactory.create(
        context.accountingMonth(), context.productCode(), temporaryProductKey, scope, primaryScope);
    QuoteCollaborationProductTask exact =
        taskRepository.findActiveProductTaskByLockKey(activeLockKey, scope).orElse(null);
    if (exact != null || !StringUtils.hasText(context.productCode())) {
      return exact;
    }
    return taskRepository.findProductTasksByProductAndMonth(
            context.productCode(), context.accountingMonth(), scope).stream()
        .filter(task -> Integer.valueOf(1).equals(task.getActiveFlag()))
        .findFirst()
        .orElse(null);
  }

  private QuoteCollaborationProductTask activeTaskLinkedToQuoteItem(
      QuoteCollaborationScanContext context, CollaborationScope scope) {
    for (QuoteCollaborationQuoteLink link :
        taskRepository.findActiveLinksByQuoteItem(context.oaFormItemId(), scope)) {
      QuoteCollaborationProductTask task =
          taskRepository.findProductTaskById(link.getProductTaskId(), scope).orElse(null);
      if (task != null && Integer.valueOf(1).equals(task.getActiveFlag())) {
        return task;
      }
    }
    return null;
  }

  private QuoteCollaborationScanResult activeTaskResult(
      QuoteCollaborationScanContext context,
      ProductForm productForm,
      CurrentU9BomResult u9,
      PrimaryScope scope,
      QuoteCollaborationProductTask active,
      List<QuoteCollaborationScanStage> stages) {
    String bomSource =
        u9.status() == CurrentU9BomResult.Status.AVAILABLE
            ? (productForm == ProductForm.BARE ? "U9_BODY" : "U9")
            : null;
    CollaborationPriceScanResult price =
        scope == PrimaryScope.BARE_PACKAGE
            ? CollaborationPriceScanResult.pendingPackage("等待同月原包装任务完成")
            : scope == PrimaryScope.FULL_BOM
                ? CollaborationPriceScanResult.pendingBom("等待同月原BOM任务完成")
                : new CollaborationPriceScanResult(
                    CollaborationPriceScanResult.Status.NOT_CHECKED,
                    0,
                    List.of(),
                    "等待同月原补价任务完成");
    return result(
        context,
        productForm,
        QuoteCollaborationScanStatus.WAITING_EXISTING_TASK,
        QuoteCollaborationScanAction.LINK_ACTIVE_TASK,
        scope,
        bomSource,
        u9.bomVersion(),
        u9.lineCount(),
        active.getId(),
        active.getCurrentAssigneeName(),
        null,
        price,
        stages,
        null,
        "同月同产品已有任务，由"
            + firstText(active.getCurrentAssigneeName(), "原技术人员")
            + "处理中，不重复发起");
  }

  private ProductForm toProductForm(QuoteProductTypeResolveResult result) {
    if (result == null || result.productType() == null) {
      return ProductForm.UNKNOWN;
    }
    return switch (result.productType()) {
      case BARE -> ProductForm.BARE;
      case NON_BARE -> ProductForm.NORMAL;
      case DATA_MISSING, UNKNOWN -> ProductForm.UNKNOWN;
    };
  }

  private QuoteCollaborationScanResult blocked(
      QuoteCollaborationScanContext context,
      ProductForm productForm,
      List<QuoteCollaborationScanStage> stages,
      QuoteCollaborationScanErrorCode errorCode,
      String message) {
    return result(
        context,
        productForm,
        QuoteCollaborationScanStatus.SYSTEM_BLOCKED,
        QuoteCollaborationScanAction.SYSTEM_BLOCKED,
        null,
        null,
        null,
        0,
        null,
        null,
        null,
        CollaborationPriceScanResult.error(message),
        stages,
        errorCode,
        message);
  }

  private QuoteCollaborationScanResult result(
      QuoteCollaborationScanContext context,
      ProductForm productForm,
      QuoteCollaborationScanStatus status,
      QuoteCollaborationScanAction action,
      PrimaryScope requiredScope,
      String authoritativeBomSource,
      String bomVersion,
      int bomLineCount,
      Long activeProductTaskId,
      String activeAssigneeName,
      Long approvedResultId,
      CollaborationPriceScanResult price,
      List<QuoteCollaborationScanStage> stages,
      QuoteCollaborationScanErrorCode errorCode,
      String message) {
    return new QuoteCollaborationScanResult(
        context.oaFormItemId(),
        context.oaNo(),
        context.accountingMonth(),
        context.productCode(),
        context.businessUnitType(),
        context.priceOrgCode(),
        context.materialOrganizationCode(),
        productForm,
        status,
        action,
        requiredScope,
        authoritativeBomSource,
        bomVersion,
        bomLineCount,
        activeProductTaskId,
        activeAssigneeName,
        approvedResultId,
        price,
        stages,
        errorCode,
        message);
  }

  private String exceptionMessage(RuntimeException exception) {
    return firstText(exception == null ? null : exception.getMessage(),
        exception == null ? "未知异常" : exception.getClass().getSimpleName());
  }

  private String firstText(String first, String fallback) {
    return StringUtils.hasText(first) ? first.trim() : fallback;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private <T> List<T> safeList(List<T> values) {
    return values == null ? List.of() : values;
  }
}
