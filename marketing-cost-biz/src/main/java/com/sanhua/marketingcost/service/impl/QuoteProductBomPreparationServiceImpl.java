package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureLineDto;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationBatchResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTechnicianTaskResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductTypeResolveResult;
import com.sanhua.marketingcost.dto.quotebom.SupplementBomReadResult;
import com.sanhua.marketingcost.entity.BomSupplementTask;
import com.sanhua.marketingcost.entity.BomSupplementTaskQuoteLink;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.enums.QuoteBomStatusCode;
import com.sanhua.marketingcost.enums.QuoteProductType;
import com.sanhua.marketingcost.mapper.BomSupplementTaskMapper;
import com.sanhua.marketingcost.mapper.BomSupplementTaskQuoteLinkMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.PackageComponentStructureReadService;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.service.QuoteProductTypeResolveService;
import com.sanhua.marketingcost.service.SupplementBomReadService;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteProductBomPreparationServiceImpl implements QuoteProductBomPreparationService {

  static final String PREPARATION_READY = "READY";
  static final String PREPARATION_NEED_TECH = "NEED_TECH";
  static final String PREPARATION_ERROR = "ERROR";
  static final String REVIEW_NOT_SUBMITTED = "NOT_SUBMITTED";
  static final String BODY_SOURCE_FORMAL = "FORMAL_BOM";
  static final String BODY_SOURCE_MANUAL = "MANUAL_SUPPLEMENT";
  static final String BODY_SOURCE_MONTHLY_LOCK = "MONTHLY_LOCK";
  static final String REUSE_TYPE_MANUAL_BOM = "MANUAL_BOM";
  static final String REUSE_TYPE_PACKAGE_REFERENCE = "PACKAGE_REFERENCE";
  static final String SCOPE_NON_BARE_FULL_BOM = "NON_BARE_FULL_BOM";
  static final String SCOPE_BARE_BODY_BOM = "BARE_BODY_BOM";
  static final String SCOPE_PACKAGE_REFERENCE = "PACKAGE_REFERENCE";
  static final int ACTIVE = 1;

  private static final DateTimeFormatter TASK_NO_DATE =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private final OaFormItemMapper oaFormItemMapper;
  private final OaFormMapper oaFormMapper;
  private final QuoteBomStatusMapper quoteBomStatusMapper;
  private final QuoteBomPreparationRecordMapper preparationRecordMapper;
  private final BomSupplementTaskMapper taskMapper;
  private final BomSupplementTaskQuoteLinkMapper taskQuoteLinkMapper;
  private final QuoteProductTypeResolveService productTypeResolveService;
  private final FormalBomReadService formalBomReadService;
  private final SupplementBomReadService supplementBomReadService;
  private final PackageComponentStructureReadService packageComponentStructureReadService;

  public QuoteProductBomPreparationServiceImpl(
      OaFormItemMapper oaFormItemMapper,
      OaFormMapper oaFormMapper,
      QuoteBomStatusMapper quoteBomStatusMapper,
      QuoteBomPreparationRecordMapper preparationRecordMapper,
      BomSupplementTaskMapper taskMapper,
      BomSupplementTaskQuoteLinkMapper taskQuoteLinkMapper,
      QuoteProductTypeResolveService productTypeResolveService,
      FormalBomReadService formalBomReadService,
      SupplementBomReadService supplementBomReadService,
      PackageComponentStructureReadService packageComponentStructureReadService) {
    this.oaFormItemMapper = oaFormItemMapper;
    this.oaFormMapper = oaFormMapper;
    this.quoteBomStatusMapper = quoteBomStatusMapper;
    this.preparationRecordMapper = preparationRecordMapper;
    this.taskMapper = taskMapper;
    this.taskQuoteLinkMapper = taskQuoteLinkMapper;
    this.productTypeResolveService = productTypeResolveService;
    this.formalBomReadService = formalBomReadService;
    this.supplementBomReadService = supplementBomReadService;
    this.packageComponentStructureReadService = packageComponentStructureReadService;
  }

  @Override
  @Transactional
  public QuoteProductBomPreparationPreview prepareByOaFormItem(Long itemId) {
    QuoteContext context = loadContext(itemId);
    String productCode = trimToNull(context.item().getMaterialNo());
    String periodMonth = resolvePeriodMonth(context.form());
    QuoteProductTypeResolveResult typeResult = productTypeResolveService.resolve(productCode);
    if (typeResult.productType() == QuoteProductType.DATA_MISSING
        || typeResult.productType() == QuoteProductType.UNKNOWN) {
      String error = firstText(typeResult.errorMessage(), "产品形态无法判断");
      QuoteBomStatus status = upsertStatus(context, productCode, periodMonth, typeResult, null, error);
      QuoteBomPreparationRecord record =
          upsertPreparationRecord(
              context,
              status,
              typeResult,
              periodMonth,
              PREPARATION_ERROR,
              false,
              null,
              null,
              null,
              null,
              null,
              error);
      return toPreview(
          record,
          status,
          false,
          false,
          true,
          null,
          false,
          List.of(),
          null,
          false,
          List.of(),
          List.of("PRODUCT_TYPE"),
          List.of(error));
    }

    QuoteBomPreparationRecord locked = findMonthlyLockedRecord(productCode, periodMonth);
    if (locked != null && Objects.equals(locked.getOaFormItemId(), itemId)) {
      QuoteBomStatus status =
          locked.getQuoteBomStatusId() == null
              ? null
              : quoteBomStatusMapper.selectById(locked.getQuoteBomStatusId());
      return toPreview(
          locked,
          status,
          true,
          false,
          false,
          BODY_SOURCE_MONTHLY_LOCK,
          true,
          List.of(),
          locked.getReferenceFinishedCode(),
          trimToNull(locked.getReferenceFinishedCode()) != null,
          List.of(),
          List.of(),
          List.of());
    }
    if (locked != null) {
      QuoteBomStatus status =
          upsertStatus(context, productCode, periodMonth, typeResult, locked.getReferenceFinishedCode(), null);
      QuoteBomPreparationRecord record =
          upsertPreparationRecord(
              context,
              status,
              typeResult,
              periodMonth,
              PREPARATION_READY,
              QuoteProductType.BARE == typeResult.productType(),
              locked.getReferenceFinishedCode(),
              locked.getSourceTopProductCode(),
              locked.getTaskId(),
              firstText(locked.getReuseType(), BODY_SOURCE_MONTHLY_LOCK),
              locked,
              null);
      return toPreview(
          record,
          status,
          true,
          false,
          false,
          BODY_SOURCE_MONTHLY_LOCK,
          true,
          List.of(),
          record.getReferenceFinishedCode(),
          record.getReferenceFinishedCode() != null,
          List.of(),
          List.of(),
          List.of());
    }

    if (typeResult.productType() == QuoteProductType.NON_BARE) {
      return prepareNonBare(context, typeResult, periodMonth);
    }
    return prepareBare(context, typeResult, periodMonth);
  }

  @Override
  @Transactional
  public QuoteProductBomPreparationBatchResult batchPrepare(Collection<Long> itemIds) {
    List<Long> ids = normalizeIds(itemIds);
    List<QuoteProductBomPreparationPreview> previews = new ArrayList<>();
    for (Long id : ids) {
      previews.add(prepareByOaFormItem(id));
    }
    return new QuoteProductBomPreparationBatchResult(
        ids.size(),
        previews.size(),
        (int) previews.stream().filter(QuoteProductBomPreparationPreview::ready).count(),
        (int) previews.stream().filter(QuoteProductBomPreparationPreview::needTechnicianTask).count(),
        (int) previews.stream().filter(QuoteProductBomPreparationPreview::abnormal).count(),
        previews);
  }

  @Override
  @Transactional
  public QuoteProductBomTechnicianTaskResult createTechnicianTask(Collection<Long> itemIds) {
    List<Long> ids = normalizeIds(itemIds);
    List<QuoteProductBomPreparationPreview> previews = new ArrayList<>();
    List<String> rejectedMessages = new ArrayList<>();
    int created = 0;
    int reused = 0;
    for (Long id : ids) {
      QuoteProductBomPreparationPreview preview = prepareByOaFormItem(id);
      if (!preview.needTechnicianTask()) {
        rejectedMessages.add("报价产品行 " + id + " 当前不需要技术员处理");
        previews.add(preview);
        continue;
      }
      QuoteBomPreparationRecord record = preparationRecordMapper.selectById(preview.preparationRecordId());
      BomSupplementTask task = record == null ? null : findActiveTask(record);
      boolean reusedTask = task != null;
      if (task == null) {
        QuoteContext context = loadContext(id);
        task = createTask(context, preview);
        taskMapper.insert(task);
        created++;
      }
      if (record != null) {
        record.setTaskId(task.getId());
        record.setUpdatedAt(LocalDateTime.now());
        preparationRecordMapper.updateById(record);
      }
      QuoteBomStatus status =
          quoteBomStatusMapper.selectOne(
              Wrappers.<QuoteBomStatus>lambdaQuery()
                  .eq(QuoteBomStatus::getOaFormItemId, id)
                  .last("LIMIT 1"));
      if (status != null) {
        ensureTaskLink(task, status);
        status.setSupplementTaskId(task.getId());
        status.setManualTaskNo(task.getTaskNo());
        status.setBomStatus(QuoteBomStatusCode.ENTRY_IN_PROGRESS.getCode());
        status.setUpdatedAt(LocalDateTime.now());
        quoteBomStatusMapper.updateById(status);
      }
      QuoteProductBomPreparationPreview refreshed = getPreparationPreview(id);
      previews.add(refreshed == null ? preview : refreshed);
      if (reusedTask) {
        reused++;
      }
    }
    return new QuoteProductBomTechnicianTaskResult(
        ids.size(), created, reused, rejectedMessages.size(), previews, rejectedMessages);
  }

  @Override
  public QuoteProductBomPreparationPreview getPreparationPreview(Long itemId) {
    if (itemId == null) {
      return null;
    }
    QuoteBomPreparationRecord record =
        preparationRecordMapper.selectOne(
            Wrappers.<QuoteBomPreparationRecord>lambdaQuery()
                .eq(QuoteBomPreparationRecord::getOaFormItemId, itemId)
                .eq(QuoteBomPreparationRecord::getActiveFlag, ACTIVE)
                .orderByDesc(QuoteBomPreparationRecord::getUpdatedAt)
                .orderByDesc(QuoteBomPreparationRecord::getId)
                .last("LIMIT 1"));
    if (record == null) {
      return null;
    }
    QuoteBomStatus status =
        record.getQuoteBomStatusId() == null
            ? null
            : quoteBomStatusMapper.selectById(record.getQuoteBomStatusId());
    boolean ready = PREPARATION_READY.equals(record.getPreparationStatus());
    boolean needTech = PREPARATION_NEED_TECH.equals(record.getPreparationStatus());
    boolean abnormal = PREPARATION_ERROR.equals(record.getPreparationStatus());
    String error = record.getErrorMessage();
    return toPreview(
        record,
        status,
        ready,
        needTech,
        abnormal,
        record.getReuseType(),
        ready,
        List.of(),
        record.getReferenceFinishedCode(),
        trimToNull(record.getReferenceFinishedCode()) != null,
        List.of(),
        needTech ? inferMissingScopes(record) : List.of(),
        error == null ? List.of() : List.of(error));
  }

  private QuoteProductBomPreparationPreview prepareNonBare(
      QuoteContext context, QuoteProductTypeResolveResult typeResult, String periodMonth) {
    String productCode = trimToNull(context.item().getMaterialNo());
    FormalBomReadResult formal = formalBomReadService.read(productCode, periodMonth, null);
    if (formal.found()) {
      QuoteBomStatus status = upsertStatus(context, productCode, periodMonth, typeResult, null, null);
      QuoteBomPreparationRecord record =
          upsertPreparationRecord(
              context,
              status,
              typeResult,
              periodMonth,
              PREPARATION_READY,
              false,
              null,
              null,
              null,
              null,
              null,
              null);
      return toPreview(
          record,
          status,
          true,
          false,
          false,
          BODY_SOURCE_FORMAL,
          true,
          formal.lines(),
          null,
          false,
          List.of(),
          List.of(),
          List.of());
    }

    SupplementBomReadResult supplement =
        supplementBomReadService.readApproved(
            productCode, QuoteProductType.NON_BARE.getCode(), SCOPE_NON_BARE_FULL_BOM, periodMonth);
    if (supplement.found()) {
      QuoteBomStatus status = upsertStatus(context, productCode, periodMonth, typeResult, null, null);
      status.setBomStatus(QuoteBomStatusCode.REUSED_CURRENT_MONTH.getCode());
      quoteBomStatusMapper.updateById(status);
      QuoteBomPreparationRecord record =
          upsertPreparationRecord(
              context,
              status,
              typeResult,
              periodMonth,
              PREPARATION_READY,
              false,
              null,
              null,
              supplement.taskId(),
              REUSE_TYPE_MANUAL_BOM,
              supplement,
              null);
      return toPreview(
          record,
          status,
          true,
          false,
          false,
          BODY_SOURCE_MANUAL,
          true,
          supplement.lines(),
          null,
          false,
          List.of(),
          List.of(),
          List.of());
    }

    List<String> gaps = compact(formal.gapMessage(), supplement.gapMessage(), "非裸品完整 BOM 缺失，需技术员补录");
    QuoteBomStatus status = upsertStatus(context, productCode, periodMonth, typeResult, null, String.join("；", gaps));
    QuoteBomPreparationRecord record =
        upsertPreparationRecord(
            context,
            status,
            typeResult,
            periodMonth,
            PREPARATION_NEED_TECH,
            false,
            null,
            null,
            null,
            null,
            null,
            String.join("；", gaps));
    return toPreview(
        record,
        status,
        false,
        true,
        false,
        null,
        false,
        List.of(),
        null,
        false,
        List.of(),
        List.of(SCOPE_NON_BARE_FULL_BOM),
        gaps);
  }

  private QuoteProductBomPreparationPreview prepareBare(
      QuoteContext context, QuoteProductTypeResolveResult typeResult, String periodMonth) {
    String productCode = trimToNull(context.item().getMaterialNo());
    FormalBomReadResult formal = formalBomReadService.read(productCode, periodMonth, null);
    boolean bodyReady = formal.found();
    String bodySource = bodyReady ? BODY_SOURCE_FORMAL : null;
    List<QuoteBomSourceLineDto> bodyLines = bodyReady ? formal.lines() : List.of();
    SupplementBomReadResult supplement = null;
    if (!bodyReady) {
      supplement =
          supplementBomReadService.readApproved(
              productCode, QuoteProductType.BARE.getCode(), SCOPE_BARE_BODY_BOM, periodMonth);
      bodyReady = supplement.found();
      bodySource = bodyReady ? BODY_SOURCE_MANUAL : null;
      bodyLines = bodyReady ? supplement.lines() : List.of();
    }

    PackageComponentStructureReadResult packageResult =
        packageComponentStructureReadService.readApprovedReferenceForBareProduct(productCode);
    boolean packageReady = packageResult.found();
    List<PackageComponentStructureLineDto> packageLines = packageReady ? packageResult.lines() : List.of();
    boolean ready = bodyReady && packageReady;
    List<String> missingScopes = new ArrayList<>();
    List<String> gaps = new ArrayList<>();
    if (!bodyReady) {
      missingScopes.add(SCOPE_BARE_BODY_BOM);
      gaps.add(firstText(formal.gapMessage(), "裸品本体正式 BOM 缺失"));
      if (supplement != null) {
        gaps.add(firstText(supplement.gapMessage(), "未找到可复用裸品本体补录 BOM"));
      }
    }
    if (!packageReady) {
      missingScopes.add(SCOPE_PACKAGE_REFERENCE);
      if (packageResult.gaps() == null || packageResult.gaps().isEmpty()) {
        gaps.add("裸品包装参考缺失");
      } else {
        gaps.addAll(packageResult.gaps());
      }
    }

    QuoteBomStatus status =
        upsertStatus(
            context,
            productCode,
            periodMonth,
            typeResult,
            packageResult.referenceFinishedCode(),
            ready ? null : String.join("；", gaps));
    QuoteBomPreparationRecord record =
        upsertPreparationRecord(
            context,
            status,
            typeResult,
            periodMonth,
            ready ? PREPARATION_READY : PREPARATION_NEED_TECH,
            true,
            packageResult.referenceFinishedCode(),
            packageResult.sourceTopProductCode(),
            supplement == null ? null : supplement.taskId(),
            supplement != null && supplement.found() ? REUSE_TYPE_MANUAL_BOM : null,
            supplement != null && supplement.found() ? supplement : null,
            ready ? null : String.join("；", gaps));
    if (packageReady && record.getReuseType() == null) {
      record.setReuseType(REUSE_TYPE_PACKAGE_REFERENCE);
      preparationRecordMapper.updateById(record);
    }
    return toPreview(
        record,
        status,
        ready,
        !ready,
        false,
        bodySource,
        bodyReady,
        bodyLines,
        packageResult.referenceFinishedCode(),
        packageReady,
        packageLines,
        missingScopes,
        gaps);
  }

  private QuoteContext loadContext(Long itemId) {
    if (itemId == null) {
      throw new IllegalArgumentException("报价产品行 ID 不能为空");
    }
    OaFormItem item = oaFormItemMapper.selectById(itemId);
    if (item == null) {
      throw new IllegalArgumentException("报价产品行不存在: " + itemId);
    }
    OaForm form = oaFormMapper.selectById(item.getOaFormId());
    if (form == null) {
      throw new IllegalArgumentException("报价单不存在: " + item.getOaFormId());
    }
    return new QuoteContext(form, item);
  }

  private QuoteBomStatus upsertStatus(
      QuoteContext context,
      String productCode,
      String periodMonth,
      QuoteProductTypeResolveResult typeResult,
      String referenceFinishedCode,
      String errorMessage) {
    QuoteBomStatus status =
        quoteBomStatusMapper.selectOne(
            Wrappers.<QuoteBomStatus>lambdaQuery()
                .eq(QuoteBomStatus::getOaFormItemId, context.item().getId())
                .last("LIMIT 1"));
    boolean inserting = status == null;
    if (status == null) {
      status = new QuoteBomStatus();
      status.setCreatedAt(LocalDateTime.now());
    }
    status.setOaFormId(context.form().getId());
    status.setOaFormItemId(context.item().getId());
    status.setOaNo(context.form().getOaNo());
    status.setProductCode(productCode);
    status.setProductType(typeResult.productTypeCode());
    status.setBareProductCode(typeResult.productType() == QuoteProductType.BARE ? productCode : null);
    status.setNeedPackage(typeResult.productType() == QuoteProductType.BARE ? 1 : 0);
    status.setReferenceFinishedCode(trimToNull(referenceFinishedCode));
    status.setCostPeriodMonth(periodMonth);
    status.setProductModel(context.item().getSunlModel());
    status.setCustomerCode(context.item().getCustomerCode());
    status.setPackageType(context.item().getPackageType());
    status.setPackageMethod(context.item().getPackageMethod());
    status.setTechnicianName(context.item().getTechnicianName());
    status.setReviewStatus(REVIEW_NOT_SUBMITTED);
    status.setCheckedAt(LocalDateTime.now());
    status.setUpdatedAt(LocalDateTime.now());
    status.setErrorMessage(trimToNull(errorMessage));
    if (typeResult.productType() == QuoteProductType.DATA_MISSING
        || typeResult.productType() == QuoteProductType.UNKNOWN) {
      status.setBomStatus(QuoteBomStatusCode.CHECK_FAILED.getCode());
    } else if (trimToNull(errorMessage) != null) {
      status.setBomStatus(QuoteBomStatusCode.ENTRY_PENDING.getCode());
    } else if (referenceFinishedCode != null || typeResult.productType() == QuoteProductType.BARE) {
      status.setBomStatus(QuoteBomStatusCode.REUSED_CURRENT_MONTH.getCode());
    } else {
      status.setBomStatus(QuoteBomStatusCode.SYNCED.getCode());
    }
    if (inserting) {
      quoteBomStatusMapper.insert(status);
    } else {
      quoteBomStatusMapper.updateById(status);
    }
    return status;
  }

  private QuoteBomPreparationRecord upsertPreparationRecord(
      QuoteContext context,
      QuoteBomStatus status,
      QuoteProductTypeResolveResult typeResult,
      String periodMonth,
      String preparationStatus,
      boolean needPackage,
      String referenceFinishedCode,
      String sourceTopProductCode,
      Long taskId,
      String reuseType,
      Object reuseSource,
      String errorMessage) {
    QuoteBomPreparationRecord record =
        preparationRecordMapper.selectOne(
            Wrappers.<QuoteBomPreparationRecord>lambdaQuery()
                .eq(QuoteBomPreparationRecord::getOaFormItemId, context.item().getId())
                .eq(QuoteBomPreparationRecord::getCostPeriodMonth, periodMonth)
                .eq(QuoteBomPreparationRecord::getActiveFlag, ACTIVE)
                .last("LIMIT 1"));
    boolean inserting = record == null;
    if (record == null) {
      record = new QuoteBomPreparationRecord();
      record.setCreatedAt(LocalDateTime.now());
      record.setActiveFlag(ACTIVE);
    }
    record.setQuoteBomStatusId(status.getId());
    record.setOaFormId(context.form().getId());
    record.setOaFormItemId(context.item().getId());
    record.setOaNo(context.form().getOaNo());
    record.setQuoteProductCode(trimToNull(context.item().getMaterialNo()));
    record.setProductType(typeResult.productTypeCode());
    record.setBareProductCode(typeResult.productType() == QuoteProductType.BARE ? trimToNull(context.item().getMaterialNo()) : null);
    record.setNeedPackage(needPackage ? 1 : 0);
    record.setReferenceFinishedCode(trimToNull(referenceFinishedCode));
    record.setSourceTopProductCode(trimToNull(sourceTopProductCode));
    record.setCostPeriodMonth(periodMonth);
    record.setPreparationStatus(preparationStatus);
    record.setReviewStatus(REVIEW_NOT_SUBMITTED);
    record.setTechnicianName(context.item().getTechnicianName());
    record.setTaskId(taskId);
    record.setReuseType(trimToNull(reuseType));
    record.setErrorMessage(trimToNull(errorMessage));
    applyReuseSource(record, reuseSource);
    record.setUpdatedAt(LocalDateTime.now());
    if (inserting) {
      preparationRecordMapper.insert(record);
      status.setPreparationRecordId(record.getId());
      quoteBomStatusMapper.updateById(status);
    } else {
      preparationRecordMapper.updateById(record);
    }
    return record;
  }

  private void applyReuseSource(QuoteBomPreparationRecord record, Object reuseSource) {
    record.setReusedFromTaskId(null);
    record.setReusedFromOaNo(null);
    record.setReusedFromOaFormItemId(null);
    record.setReuseValidUntil(null);
    if (reuseSource instanceof SupplementBomReadResult supplement) {
      record.setReusedFromTaskId(supplement.taskId());
      record.setReuseValidUntil(supplement.reuseValidUntil());
      return;
    }
    if (reuseSource instanceof QuoteBomPreparationRecord locked) {
      record.setReusedFromTaskId(locked.getTaskId());
      record.setReusedFromOaNo(locked.getOaNo());
      record.setReusedFromOaFormItemId(locked.getOaFormItemId());
      record.setReuseValidUntil(locked.getReuseValidUntil());
    }
  }

  private QuoteBomPreparationRecord findMonthlyLockedRecord(String productCode, String periodMonth) {
    if (trimToNull(productCode) == null || trimToNull(periodMonth) == null) {
      return null;
    }
    return preparationRecordMapper.selectOne(
        Wrappers.<QuoteBomPreparationRecord>lambdaQuery()
            .eq(QuoteBomPreparationRecord::getQuoteProductCode, productCode)
            .eq(QuoteBomPreparationRecord::getCostPeriodMonth, periodMonth)
            .eq(QuoteBomPreparationRecord::getActiveFlag, ACTIVE)
            .in(
                QuoteBomPreparationRecord::getPreparationStatus,
                List.of(PREPARATION_READY, "CONFIRMED"))
            .orderByDesc(QuoteBomPreparationRecord::getUpdatedAt)
            .orderByDesc(QuoteBomPreparationRecord::getId)
            .last("LIMIT 1"));
  }

  private BomSupplementTask findActiveTask(QuoteBomPreparationRecord record) {
    if (record == null) {
      return null;
    }
    if (record.getTaskId() != null) {
      BomSupplementTask task = taskMapper.selectById(record.getTaskId());
      if (task != null) {
        return task;
      }
    }
    return taskMapper.selectOne(
        Wrappers.<BomSupplementTask>lambdaQuery()
            .eq(BomSupplementTask::getProductCode, record.getQuoteProductCode())
            .eq(BomSupplementTask::getMissingBomScope, taskScope(record))
            .in(
                BomSupplementTask::getTaskStatus,
                List.of("TODO_PENDING", "TODO_PUSHED", "IN_PROGRESS", "FINANCE_REVIEW"))
            .last("LIMIT 1"));
  }

  private BomSupplementTask createTask(
      QuoteContext context, QuoteProductBomPreparationPreview preview) {
    LocalDateTime now = LocalDateTime.now();
    BomSupplementTask task = new BomSupplementTask();
    task.setTaskNo("QBP-" + TASK_NO_DATE.format(now) + "-" + preview.oaFormItemId());
    task.setBusinessUnitType(context.item().getBusinessUnitType());
    task.setProductCode(preview.quoteProductCode());
    task.setProductName(context.item().getProductName());
    task.setProductModel(context.item().getSunlModel());
    task.setCustomerCode(context.item().getCustomerCode());
    task.setPackageType(context.item().getPackageType());
    task.setPackageMethod(context.item().getPackageMethod());
    task.setMissingBomScope(String.join(",", preview.missingScopes()));
    task.setMissingReason(String.join("；", preview.gapMessages()));
    task.setTaskStatus("TODO_PENDING");
    task.setTechnicianName(context.item().getTechnicianName());
    task.setRemark("报价产品 BOM 准备待技术员处理");
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return task;
  }

  private void ensureTaskLink(BomSupplementTask task, QuoteBomStatus status) {
    if (task == null || status == null) {
      return;
    }
    BomSupplementTaskQuoteLink existing =
        taskQuoteLinkMapper.selectOne(
            Wrappers.<BomSupplementTaskQuoteLink>lambdaQuery()
                .eq(BomSupplementTaskQuoteLink::getTaskId, task.getId())
                .eq(BomSupplementTaskQuoteLink::getOaFormItemId, status.getOaFormItemId())
                .last("LIMIT 1"));
    if (existing != null) {
      return;
    }
    BomSupplementTaskQuoteLink link = new BomSupplementTaskQuoteLink();
    link.setTaskId(task.getId());
    link.setTaskNo(task.getTaskNo());
    link.setQuoteBomStatusId(status.getId());
    link.setOaFormId(status.getOaFormId());
    link.setOaFormItemId(status.getOaFormItemId());
    link.setOaNo(status.getOaNo());
    link.setProductCode(status.getProductCode());
    link.setCreatedAt(LocalDateTime.now());
    taskQuoteLinkMapper.insert(link);
  }

  private String taskScope(QuoteBomPreparationRecord record) {
    if (record.getProductType() != null && record.getProductType().equals(QuoteProductType.NON_BARE.getCode())) {
      return SCOPE_NON_BARE_FULL_BOM;
    }
    if (record.getNeedPackage() != null && record.getNeedPackage() == 1) {
      return SCOPE_BARE_BODY_BOM + "," + SCOPE_PACKAGE_REFERENCE;
    }
    return SCOPE_BARE_BODY_BOM;
  }

  private QuoteProductBomPreparationPreview toPreview(
      QuoteBomPreparationRecord record,
      QuoteBomStatus status,
      boolean ready,
      boolean needTechnicianTask,
      boolean abnormal,
      String bodyBomSource,
      boolean bodyBomReady,
      List<QuoteBomSourceLineDto> bodyLines,
      String referenceFinishedCode,
      boolean packageReferenceReady,
      List<PackageComponentStructureLineDto> packageLines,
      List<String> missingScopes,
      List<String> gapMessages) {
    return new QuoteProductBomPreparationPreview(
        record.getId(),
        status == null ? record.getQuoteBomStatusId() : status.getId(),
        record.getOaFormId(),
        record.getOaFormItemId(),
        record.getOaNo(),
        record.getQuoteProductCode(),
        record.getProductType(),
        record.getBareProductCode(),
        record.getNeedPackage() != null && record.getNeedPackage() == 1,
        record.getCostPeriodMonth(),
        record.getPreparationStatus(),
        record.getReviewStatus(),
        ready,
        needTechnicianTask,
        abnormal,
        bodyBomSource,
        bodyBomReady,
        bodyLines == null ? 0 : bodyLines.size(),
        firstText(referenceFinishedCode, record.getReferenceFinishedCode()),
        record.getSourceTopProductCode(),
        packageReferenceReady,
        packageLines == null ? 0 : packageLines.size(),
        record.getTaskId(),
        record.getReusedFromTaskId(),
        record.getReusedFromOaNo(),
        record.getReusedFromOaFormItemId(),
        record.getReuseType(),
        record.getReuseValidUntil(),
        missingScopes == null ? List.of() : List.copyOf(missingScopes),
        gapMessages == null ? List.of() : List.copyOf(gapMessages),
        record.getErrorMessage(),
        bodyLines == null ? List.of() : List.copyOf(bodyLines),
        packageLines == null ? List.of() : List.copyOf(packageLines));
  }

  private List<String> inferMissingScopes(QuoteBomPreparationRecord record) {
    if (record == null) {
      return List.of();
    }
    if (QuoteProductType.NON_BARE.getCode().equals(record.getProductType())) {
      return List.of(SCOPE_NON_BARE_FULL_BOM);
    }
    List<String> scopes = new ArrayList<>();
    if (trimToNull(record.getReuseType()) == null) {
      scopes.add(SCOPE_BARE_BODY_BOM);
    }
    if (record.getNeedPackage() != null && record.getNeedPackage() == 1
        && trimToNull(record.getReferenceFinishedCode()) == null) {
      scopes.add(SCOPE_PACKAGE_REFERENCE);
    }
    return scopes;
  }

  private List<Long> normalizeIds(Collection<Long> itemIds) {
    if (itemIds == null || itemIds.isEmpty()) {
      return List.of();
    }
    Set<Long> normalized = new LinkedHashSet<>();
    for (Long id : itemIds) {
      if (id != null) {
        normalized.add(id);
      }
    }
    return new ArrayList<>(normalized);
  }

  private String resolvePeriodMonth(OaForm form) {
    String accountingPeriod = trimToNull(form.getAccountingPeriodMonth());
    if (accountingPeriod != null) {
      return YearMonth.parse(accountingPeriod).toString();
    }
    if (form.getApplyDate() != null) {
      return YearMonth.from(form.getApplyDate()).toString();
    }
    return YearMonth.now().toString();
  }

  private List<String> compact(String... values) {
    List<String> result = new ArrayList<>();
    for (String value : values) {
      String text = trimToNull(value);
      if (text != null && !result.contains(text)) {
        result.add(text);
      }
    }
    return result;
  }

  private String firstText(String first, String second) {
    String value = trimToNull(first);
    return value == null ? trimToNull(second) : value;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private record QuoteContext(OaForm form, OaFormItem item) {}
}
