package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationContextResponse;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationSaveResponse;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationSubmitRequest;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationSubmitRequest.PackageLineSelection;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationSubmitRequest.PackageReferenceSelection;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationSubmitRequest.SupplementLine;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskDetailResponse;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskDetailResponse.ChangeLogLine;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskDetailResponse.CompleteBomLine;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskDetailResponse.PackageReference;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskDetailResponse.TaskHeader;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskQueryRequest;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskQueryResponse;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskReviewRequest;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskReviewResponse;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureLineDto;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomPackageReferenceDetailDto;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSupplementDetailDto;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTaskCreateRequest;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTaskCreateResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTaskCreateResponse.TaskLink;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTechnicianTaskResult;
import com.sanhua.marketingcost.entity.BomSupplementTask;
import com.sanhua.marketingcost.entity.BomSupplementTodo;
import com.sanhua.marketingcost.entity.BusinessChangeLog;
import com.sanhua.marketingcost.entity.QuoteBomPackageReference;
import com.sanhua.marketingcost.entity.QuoteBomPackageReferenceDetail;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.entity.system.LpCollaborationToken;
import com.sanhua.marketingcost.enums.QuoteBomStatusCode;
import com.sanhua.marketingcost.mapper.BomSupplementTaskMapper;
import com.sanhua.marketingcost.mapper.BomSupplementTodoMapper;
import com.sanhua.marketingcost.mapper.BusinessChangeLogMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.CollaborationTokenService;
import com.sanhua.marketingcost.service.PackageComponentStructureReadService;
import com.sanhua.marketingcost.service.QuoteBomSupplementCollaborationService;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteBomSupplementCollaborationServiceImpl
    implements QuoteBomSupplementCollaborationService {

  private static final String TOKEN_TYPE = "bom-supplement";
  private static final String COLLABORATION_URL = "/collaborate/bom-supplement?token=";
  private static final String STATUS_TODO_PENDING = "TODO_PENDING";
  private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
  private static final String STATUS_FINANCE_REVIEW = "FINANCE_REVIEW";
  private static final String STATUS_APPROVED = "APPROVED";
  private static final String PREPARATION_NEED_TECH = "NEED_TECH";
  private static final String PREPARATION_TECH_SUBMITTED = "TECH_SUBMITTED";
  private static final String PREPARATION_READY = "READY";
  private static final String REVIEW_NOT_SUBMITTED = "NOT_SUBMITTED";
  private static final String REVIEW_PENDING = "PENDING";
  private static final String REVIEW_APPROVED = "APPROVED";
  private static final String REVIEW_RETURNED = "RETURNED";
  private static final String VERSION_DRAFT = "DRAFT";
  private static final String VERSION_SUBMITTED = "SUBMITTED";
  private static final String VERSION_APPROVED = "APPROVED";
  private static final String VERSION_RETURNED = "RETURNED";
  private static final String REFERENCE_DRAFT = "DRAFT";
  private static final String REFERENCE_SUBMITTED = "SUBMITTED";
  private static final String REFERENCE_APPROVED = "APPROVED";
  private static final String REFERENCE_RETURNED = "RETURNED";
  private static final String PRODUCT_TYPE_NON_BARE = "NON_BARE";
  private static final String SCOPE_NON_BARE_FULL_BOM = "NON_BARE_FULL_BOM";
  private static final String SCOPE_BARE_BODY_BOM = "BARE_BODY_BOM";
  private static final String SCOPE_PACKAGE_REFERENCE = "PACKAGE_REFERENCE";
  private static final int ACTIVE = 1;

  private final QuoteProductBomPreparationService preparationService;
  private final CollaborationTokenService tokenService;
  private final PackageComponentStructureReadService packageReadService;
  private final BomSupplementTaskMapper taskMapper;
  private final BomSupplementTodoMapper todoMapper;
  private final QuoteBomPreparationRecordMapper preparationRecordMapper;
  private final QuoteBomStatusMapper statusMapper;
  private final QuoteBomSupplementVersionMapper supplementVersionMapper;
  private final QuoteBomSupplementDetailMapper supplementDetailMapper;
  private final QuoteBomPackageReferenceMapper packageReferenceMapper;
  private final QuoteBomPackageReferenceDetailMapper packageReferenceDetailMapper;
  private final BusinessChangeLogMapper changeLogMapper;
  private final ObjectMapper objectMapper;

  public QuoteBomSupplementCollaborationServiceImpl(
      QuoteProductBomPreparationService preparationService,
      CollaborationTokenService tokenService,
      PackageComponentStructureReadService packageReadService,
      BomSupplementTaskMapper taskMapper,
      BomSupplementTodoMapper todoMapper,
      QuoteBomPreparationRecordMapper preparationRecordMapper,
      QuoteBomStatusMapper statusMapper,
      QuoteBomSupplementVersionMapper supplementVersionMapper,
      QuoteBomSupplementDetailMapper supplementDetailMapper,
      QuoteBomPackageReferenceMapper packageReferenceMapper,
      QuoteBomPackageReferenceDetailMapper packageReferenceDetailMapper,
      BusinessChangeLogMapper changeLogMapper,
      ObjectMapper objectMapper) {
    this.preparationService = preparationService;
    this.tokenService = tokenService;
    this.packageReadService = packageReadService;
    this.taskMapper = taskMapper;
    this.todoMapper = todoMapper;
    this.preparationRecordMapper = preparationRecordMapper;
    this.statusMapper = statusMapper;
    this.supplementVersionMapper = supplementVersionMapper;
    this.supplementDetailMapper = supplementDetailMapper;
    this.packageReferenceMapper = packageReferenceMapper;
    this.packageReferenceDetailMapper = packageReferenceDetailMapper;
    this.changeLogMapper = changeLogMapper;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public QuoteProductBomTaskCreateResponse createTasks(QuoteProductBomTaskCreateRequest request) {
    List<Long> itemIds = normalizeIds(request == null ? null : request.oaFormItemIds());
    if (itemIds.isEmpty()) {
      throw new QuoteIngestException("请选择需要创建技术员任务的报价产品行");
    }
    QuoteProductBomTechnicianTaskResult result = preparationService.createTechnicianTask(itemIds);
    List<TaskLink> links = new ArrayList<>();
    int expireHours = normalizeExpireHours(request == null ? null : request.tokenExpireHours());
    for (QuoteProductBomPreparationPreview preview : result.previews()) {
      if (preview == null || preview.taskId() == null || preview.needTechnicianTask()) {
        continue;
      }
      TaskLink link = createTokenLink(preview, expireHours, false);
      links.add(link);
    }
    for (QuoteProductBomPreparationPreview preview : result.previews()) {
      if (preview == null || preview.taskId() == null || !preview.needTechnicianTask()) {
        continue;
      }
      TaskLink link = createTokenLink(preview, expireHours, false);
      links.add(link);
    }
    return new QuoteProductBomTaskCreateResponse(
        result.requestedCount(),
        result.createdTaskCount(),
        result.reusedTaskCount(),
        result.rejectedCount(),
        links,
        result.rejectedMessages());
  }

  @Override
  public BomSupplementTaskQueryResponse listTasks(BomSupplementTaskQueryRequest request) {
    BomSupplementTaskQueryRequest safe =
        request == null ? new BomSupplementTaskQueryRequest(null, null, null, null, null, null, null) : request;
    List<BomSupplementTask> tasks =
        taskMapper.selectList(
            Wrappers.<BomSupplementTask>lambdaQuery()
                .like(trimToNull(safe.taskNo()) != null, BomSupplementTask::getTaskNo, trimToNull(safe.taskNo()))
                .like(
                    trimToNull(safe.productCode()) != null,
                    BomSupplementTask::getProductCode,
                    trimToNull(safe.productCode()))
                .eq(
                    trimToNull(safe.taskStatus()) != null,
                    BomSupplementTask::getTaskStatus,
                    trimToNull(safe.taskStatus()))
                .orderByDesc(BomSupplementTask::getUpdatedAt)
                .orderByDesc(BomSupplementTask::getId));
    List<BomSupplementTaskQueryResponse.Row> matched = new ArrayList<>();
    for (BomSupplementTask task : tasks == null ? List.<BomSupplementTask>of() : tasks) {
      QuoteBomPreparationRecord record = loadPreparationByTask(task.getId());
      if (trimToNull(safe.oaNo()) != null
          && (record == null || !contains(record.getOaNo(), trimToNull(safe.oaNo())))) {
        continue;
      }
      if (trimToNull(safe.reviewStatus()) != null
          && (record == null || !trimToNull(safe.reviewStatus()).equals(record.getReviewStatus()))) {
        continue;
      }
      QuoteBomPackageReference reference = latestPackageReference(task.getId());
      matched.add(toTaskQueryRow(task, record, reference));
    }
    int pageNo = safe.pageNo() == null || safe.pageNo() < 1 ? 1 : safe.pageNo();
    int pageSize = safe.pageSize() == null || safe.pageSize() < 1 ? 20 : Math.min(safe.pageSize(), 200);
    int from = Math.min((pageNo - 1) * pageSize, matched.size());
    int to = Math.min(from + pageSize, matched.size());
    return new BomSupplementTaskQueryResponse(matched.size(), matched.subList(from, to));
  }

  @Override
  public BomSupplementTaskDetailResponse getTaskDetail(Long taskId) {
    BomSupplementTask task = loadTask(taskId);
    QuoteBomPreparationRecord record = loadPreparationByTask(taskId);
    QuoteProductBomPreparationPreview preview =
        record == null ? null : preparationService.getPreparationPreview(record.getOaFormItemId());
    QuoteBomSupplementVersion version = latestSupplementVersion(taskId);
    QuoteBomPackageReference packageReference = latestPackageReference(taskId);
    List<QuoteBomSupplementDetailDto> supplementLines =
        version == null ? List.of() : toSupplementLineDtos(version.getId());
    List<QuoteBomPackageReferenceDetailDto> packageLines =
        packageReference == null ? List.of() : toPackageLineDtos(packageReference.getId());
    return new BomSupplementTaskDetailResponse(
        toTaskHeader(task, record),
        preview,
        supplementLines,
        toPackageReferenceDto(packageReference),
        packageLines,
        toChangeLogLines(taskId),
        buildCompleteBomPreview(record, preview, supplementLines, packageLines));
  }

  @Override
  @Transactional
  public BomSupplementTaskReviewResponse review(Long taskId, BomSupplementTaskReviewRequest request) {
    return reviewInternal(taskId, request, true);
  }

  @Override
  @Transactional
  public BomSupplementTaskReviewResponse returnForRevision(
      Long taskId, BomSupplementTaskReviewRequest request) {
    return reviewInternal(taskId, request, false);
  }

  private BomSupplementTaskReviewResponse reviewInternal(
      Long taskId, BomSupplementTaskReviewRequest request, boolean approved) {
    BomSupplementTask task = loadTask(taskId);
    QuoteBomPreparationRecord record = loadPreparationByTask(taskId);
    if (record == null) {
      throw new QuoteIngestException("技术员任务未关联报价产品 BOM 准备记录");
    }
    if (!STATUS_FINANCE_REVIEW.equals(task.getTaskStatus())) {
      throw new QuoteIngestException("仅财务审核中的任务允许审核或退回");
    }
    LocalDateTime now = LocalDateTime.now();
    String reviewerName = trimToNull(request == null ? null : request.reviewerName());
    Long reviewerUserId = request == null ? null : request.reviewerUserId();
    String comment = trimToNull(request == null ? null : request.comment());

    QuoteBomSupplementVersion version = latestSupplementVersion(taskId);
    if (version != null) {
      version.setVersionStatus(approved ? VERSION_APPROVED : VERSION_RETURNED);
      version.setReviewerUserId(reviewerUserId);
      version.setReviewerName(reviewerName);
      version.setReviewedAt(now);
      version.setReviewComment(comment);
      if (approved) {
        version.setReuseValidUntil(now.toLocalDate().plusMonths(6));
      }
      version.setUpdatedAt(now);
      supplementVersionMapper.updateById(version);
    }

    QuoteBomPackageReference reference = latestPackageReference(taskId);
    if (reference != null) {
      reference.setReferenceStatus(approved ? REFERENCE_APPROVED : REFERENCE_RETURNED);
      reference.setUpdatedAt(now);
      packageReferenceMapper.updateById(reference);
    }

    task.setTaskStatus(approved ? STATUS_APPROVED : STATUS_IN_PROGRESS);
    task.setRemark(comment);
    task.setUpdatedAt(now);
    taskMapper.updateById(task);

    record.setPreparationStatus(approved ? PREPARATION_READY : PREPARATION_NEED_TECH);
    record.setReviewStatus(approved ? REVIEW_APPROVED : REVIEW_RETURNED);
    record.setReviewerUserId(reviewerUserId);
    record.setReviewerName(reviewerName);
    record.setReviewedAt(now);
    record.setUpdatedAt(now);
    preparationRecordMapper.updateById(record);

    QuoteBomStatus status = findQuoteBomStatus(record);
    if (status != null) {
      status.setReviewStatus(record.getReviewStatus());
      status.setReviewerUserId(reviewerUserId);
      status.setReviewerName(reviewerName);
      status.setReviewedAt(now);
      status.setBomStatus(
          approved
              ? QuoteBomStatusCode.MANUAL_ENTERED.getCode()
              : QuoteBomStatusCode.ENTRY_IN_PROGRESS.getCode());
      status.setUpdatedAt(now);
      statusMapper.updateById(status);
    }

    return new BomSupplementTaskReviewResponse(
        task.getId(),
        task.getTaskStatus(),
        record.getId(),
        record.getPreparationStatus(),
        record.getReviewStatus(),
        version == null ? null : version.getVersionStatus(),
        reference == null ? null : reference.getReferenceStatus(),
        now);
  }

  @Override
  public BomSupplementCollaborationContextResponse getContext(String token) {
    TokenScope scope = validateTokenScope(token, null);
    QuoteBomPreparationRecord record = loadPreparationByTask(scope.taskId());
    requireScopeMatchesRecord(scope, record);
    return new BomSupplementCollaborationContextResponse(
        scope.tokenId(),
        scope.expireTime(),
        scope.taskId(),
        scope.oaFormItemId(),
        scope.oaNo(),
        scope.quoteProductCode(),
        taskType(record),
        getTaskDetail(scope.taskId()));
  }

  @Override
  public PackageComponentStructureReadResult readPackageReference(
      String token,
      Long taskId,
      String referenceFinishedCode,
      String sourceTopProductCode,
      String periodMonth) {
    TokenScope scope = validateTokenScope(token, taskId);
    QuoteBomPreparationRecord record = loadPreparationByTask(scope.taskId());
    requireScopeMatchesRecord(scope, record);
    if (record == null || record.getNeedPackage() == null || record.getNeedPackage() != 1) {
      throw new QuoteIngestException("当前任务不允许选择包装参考");
    }
    return packageReadService.readByReference(referenceFinishedCode, sourceTopProductCode, periodMonth);
  }

  @Override
  @Transactional
  public BomSupplementCollaborationSaveResponse saveDraft(
      String token, Long taskId, BomSupplementCollaborationSubmitRequest request) {
    return saveOrSubmit(token, taskId, request, false);
  }

  @Override
  @Transactional
  public BomSupplementCollaborationSaveResponse submit(
      String token, Long taskId, BomSupplementCollaborationSubmitRequest request) {
    return saveOrSubmit(token, taskId, request, true);
  }

  private BomSupplementCollaborationSaveResponse saveOrSubmit(
      String token, Long taskId, BomSupplementCollaborationSubmitRequest request, boolean submit) {
    TokenScope scope = validateTokenScope(token, taskId);
    BomSupplementTask task = loadTask(scope.taskId());
    QuoteBomPreparationRecord record = loadPreparationByTask(scope.taskId());
    requireScopeMatchesRecord(scope, record);
    if (record == null) {
      throw new QuoteIngestException("技术员任务未关联报价产品 BOM 准备记录");
    }
    String versionStatus = submit ? VERSION_SUBMITTED : VERSION_DRAFT;
    String packageStatus = submit ? REFERENCE_SUBMITTED : REFERENCE_DRAFT;
    LocalDateTime now = LocalDateTime.now();
    Long supplementVersionId = null;
    int supplementLineCount = 0;
    if (request != null && request.supplementLines() != null && !request.supplementLines().isEmpty()) {
      QuoteBomSupplementVersion version =
          upsertSupplementVersion(record, task, request, versionStatus, now);
      supplementVersionId = version.getId();
      supplementLineCount = replaceSupplementDetails(version, record, task, request.supplementLines(), now);
    }

    Long packageReferenceId = null;
    int packageLineCount = 0;
    int changeLogCount = 0;
    if (request != null && request.packageReference() != null) {
      PackageSaveResult saved = savePackageReference(record, task, request, packageStatus, now);
      packageReferenceId = saved.packageReferenceId();
      packageLineCount = saved.lineCount();
      changeLogCount = saved.changeLogCount();
    }

    task.setTaskStatus(submit ? STATUS_FINANCE_REVIEW : STATUS_IN_PROGRESS);
    task.setUpdatedAt(now);
    taskMapper.updateById(task);

    record.setPreparationStatus(submit ? PREPARATION_TECH_SUBMITTED : PREPARATION_NEED_TECH);
    record.setReviewStatus(submit ? REVIEW_PENDING : REVIEW_NOT_SUBMITTED);
    record.setUpdatedAt(now);
    preparationRecordMapper.updateById(record);

    QuoteBomStatus status = findQuoteBomStatus(record);
    if (status != null) {
      status.setBomStatus(submit ? QuoteBomStatusCode.MANUAL_ENTERED.getCode() : QuoteBomStatusCode.ENTRY_IN_PROGRESS.getCode());
      status.setManualTaskNo(task.getTaskNo());
      status.setSupplementTaskId(task.getId());
      status.setCheckedAt(now);
      status.setUpdatedAt(now);
      statusMapper.updateById(status);
    }

    return new BomSupplementCollaborationSaveResponse(
        task.getId(),
        task.getTaskStatus(),
        record.getId(),
        record.getPreparationStatus(),
        record.getReviewStatus(),
        supplementVersionId,
        supplementLineCount,
        packageReferenceId,
        packageLineCount,
        changeLogCount);
  }

  private TaskLink createTokenLink(QuoteProductBomPreparationPreview preview, int expireHours, boolean reused) {
    String remark = toTokenRemark(preview);
    LpCollaborationToken token = tokenService.generateToken(0L, TOKEN_TYPE, remark, expireHours);
    BomSupplementTask task = taskMapper.selectById(preview.taskId());
    if (task != null) {
      ensureTechnicianTodo(task, preview, token.getToken(), token.getExpireTime());
    }
    return new TaskLink(
        preview.taskId(),
        task == null ? null : task.getTaskNo(),
        preview.oaFormItemId(),
        preview.oaNo(),
        preview.quoteProductCode(),
        taskType(preview.productType(), preview.missingScopes()),
        task == null ? null : task.getTaskStatus(),
        token.getToken(),
        token.getExpireTime(),
        COLLABORATION_URL + token.getToken(),
        reused);
  }

  private void ensureTechnicianTodo(
      BomSupplementTask task, QuoteProductBomPreparationPreview preview, String token, LocalDateTime expireTime) {
    BomSupplementTodo existing =
        todoMapper.selectOne(
            Wrappers.<BomSupplementTodo>lambdaQuery()
                .eq(BomSupplementTodo::getTaskId, task.getId())
                .eq(BomSupplementTodo::getRecipientRole, "TECHNICIAN")
                .eq(BomSupplementTodo::getTodoKind, "TODO")
                .last("LIMIT 1"));
    String url = COLLABORATION_URL + token;
    if (existing != null) {
      existing.setTodoStatus("MOCK_PUSHED");
      if (!StringUtils.hasText(existing.getPushStatus()) || "MOCK_PUSHED".equals(existing.getPushStatus())) {
        existing.setPushStatus("NOT_PUSHED");
      }
      existing.setTodoUrl(url);
      existing.setPayloadJson(todoPayload(preview, token, expireTime));
      existing.setUpdatedAt(LocalDateTime.now());
      todoMapper.updateById(existing);
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    BomSupplementTodo todo = new BomSupplementTodo();
    todo.setTaskId(task.getId());
    todo.setTaskNo(task.getTaskNo());
    todo.setTodoNo("MOCK-OA-TODO-" + task.getTaskNo());
    todo.setTodoStatus("MOCK_PUSHED");
    todo.setPushStatus("NOT_PUSHED");
    todo.setTodoKind("TODO");
    todo.setRecipientRole("TECHNICIAN");
    todo.setAssigneeName(task.getTechnicianName());
    todo.setTitle("请处理报价产品 " + preview.quoteProductCode() + " 的 BOM 准备任务");
    todo.setTodoUrl(url);
    todo.setPayloadJson(todoPayload(preview, token, expireTime));
    todo.setPushedAt(now);
    todo.setCreatedAt(now);
    todo.setUpdatedAt(now);
    todoMapper.insert(todo);
  }

  private QuoteBomSupplementVersion upsertSupplementVersion(
      QuoteBomPreparationRecord record,
      BomSupplementTask task,
      BomSupplementCollaborationSubmitRequest request,
      String status,
      LocalDateTime now) {
    String scope = supplementScope(record);
    if (scope == null) {
      throw new QuoteIngestException("当前任务不允许补录本体 BOM");
    }
    QuoteBomSupplementVersion version =
        supplementVersionMapper.selectOne(
            Wrappers.<QuoteBomSupplementVersion>lambdaQuery()
                .eq(QuoteBomSupplementVersion::getTaskId, task.getId())
                .eq(QuoteBomSupplementVersion::getSupplementScope, scope)
                .eq(QuoteBomSupplementVersion::getVersionNo, 1)
                .eq(QuoteBomSupplementVersion::getActiveFlag, ACTIVE)
                .last("LIMIT 1"));
    if (version == null) {
      version = new QuoteBomSupplementVersion();
      version.setPreparationId(record.getId());
      version.setTaskId(task.getId());
      version.setTaskNo(task.getTaskNo());
      version.setOaNo(record.getOaNo());
      version.setOaFormItemId(record.getOaFormItemId());
      version.setQuoteProductCode(record.getQuoteProductCode());
      version.setProductType(record.getProductType());
      version.setSupplementScope(scope);
      version.setBomSource("TECH_SUPPLEMENT");
      version.setVersionNo(1);
      version.setActiveFlag(ACTIVE);
      version.setPeriodMonth(record.getCostPeriodMonth());
      version.setEffectiveFrom(LocalDate.now());
      version.setCreatedAt(now);
    }
    version.setVersionStatus(status);
    version.setSubmittedBy(request == null ? null : request.submittedBy());
    version.setSubmittedByName(trimToNull(request == null ? null : request.submittedByName()));
    version.setSubmittedAt(VERSION_SUBMITTED.equals(status) ? now : null);
    version.setUpdatedAt(now);
    if (version.getId() == null) {
      supplementVersionMapper.insert(version);
    } else {
      supplementVersionMapper.updateById(version);
    }
    return version;
  }

  private int replaceSupplementDetails(
      QuoteBomSupplementVersion version,
      QuoteBomPreparationRecord record,
      BomSupplementTask task,
      List<SupplementLine> lines,
      LocalDateTime now) {
    supplementDetailMapper.delete(
        Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
            .eq(QuoteBomSupplementDetail::getSupplementVersionId, version.getId()));
    int count = 0;
    int fallbackLineNo = 1;
    for (SupplementLine line : lines) {
      if (line == null || trimToNull(line.materialCode()) == null) {
        continue;
      }
      QuoteBomSupplementDetail detail = new QuoteBomSupplementDetail();
      detail.setSupplementVersionId(version.getId());
      detail.setPreparationId(record.getId());
      detail.setTaskId(task.getId());
      detail.setOaNo(record.getOaNo());
      detail.setOaFormItemId(record.getOaFormItemId());
      detail.setQuoteProductCode(record.getQuoteProductCode());
      detail.setSupplementScope(version.getSupplementScope());
      detail.setLineNo(line.lineNo() == null ? fallbackLineNo : line.lineNo());
      detail.setLevel(line.level() == null ? 0 : line.level());
      detail.setParentCode(trimToNull(line.parentCode()));
      detail.setMaterialCode(trimToNull(line.materialCode()));
      detail.setMaterialName(trimToNull(line.materialName()));
      detail.setMaterialSpec(trimToNull(line.materialSpec()));
      detail.setMaterialModel(trimToNull(line.materialModel()));
      detail.setDrawingNo(trimToNull(line.drawingNo()));
      detail.setShapeAttr(trimToNull(line.shapeAttr()));
      detail.setMainCategoryCode(trimToNull(line.mainCategoryCode()));
      detail.setSourceCategory(trimToNull(line.sourceCategory()));
      detail.setCostElementCode(trimToNull(line.costElementCode()));
      detail.setBomPurpose(trimToNull(line.bomPurpose()));
      detail.setBomVersion(trimToNull(line.bomVersion()));
      detail.setQtyPerParent(line.qtyPerParent());
      detail.setQtyPerTop(line.qtyPerTop());
      detail.setParentBaseQty(line.parentBaseQty());
      detail.setUnit(trimToNull(line.unit()));
      detail.setPath(trimToNull(line.path()));
      detail.setSortSeq(line.sortSeq());
      detail.setManualFlag(1);
      detail.setRemark(trimToNull(line.remark()));
      detail.setCreatedAt(now);
      detail.setUpdatedAt(now);
      supplementDetailMapper.insert(detail);
      count++;
      fallbackLineNo++;
    }
    return count;
  }

  private PackageSaveResult savePackageReference(
      QuoteBomPreparationRecord record,
      BomSupplementTask task,
      BomSupplementCollaborationSubmitRequest request,
      String status,
      LocalDateTime now) {
    if (record.getNeedPackage() == null || record.getNeedPackage() != 1) {
      throw new QuoteIngestException("当前任务不允许选择包装参考");
    }
    PackageReferenceSelection selection = request.packageReference();
    PackageComponentStructureReadResult source =
        packageReadService.readByReference(
            selection.referenceFinishedCode(),
            selection.sourceTopProductCode(),
            firstText(selection.periodMonth(), record.getCostPeriodMonth()));
    if (!source.found()) {
      throw new QuoteIngestException("包装组件结构不可用: " + String.join("；", source.gaps()));
    }

    QuoteBomPackageReference reference =
        packageReferenceMapper.selectOne(
            Wrappers.<QuoteBomPackageReference>lambdaQuery()
                .eq(QuoteBomPackageReference::getTaskId, task.getId())
                .eq(QuoteBomPackageReference::getActiveFlag, ACTIVE)
                .last("LIMIT 1"));
    if (reference == null) {
      reference = new QuoteBomPackageReference();
      reference.setPreparationId(record.getId());
      reference.setTaskId(task.getId());
      reference.setOaNo(record.getOaNo());
      reference.setOaFormItemId(record.getOaFormItemId());
      reference.setQuoteProductCode(record.getQuoteProductCode());
      reference.setBareProductCode(record.getBareProductCode());
      reference.setActiveFlag(ACTIVE);
      reference.setCreatedAt(now);
    }
    reference.setReferenceFinishedCode(source.referenceFinishedCode());
    reference.setSourceTopProductCode(source.sourceTopProductCode());
    reference.setPeriodMonth(source.periodMonth());
    reference.setSnapshotId(source.lines().isEmpty() ? null : source.lines().get(0).snapshotId());
    reference.setReferenceStatus(status);
    reference.setRemark(trimToNull(selection.remark()));
    reference.setUpdatedAt(now);
    if (reference.getId() == null) {
      packageReferenceMapper.insert(reference);
    } else {
      packageReferenceMapper.updateById(reference);
    }

    packageReferenceDetailMapper.delete(
        Wrappers.<QuoteBomPackageReferenceDetail>lambdaQuery()
            .eq(QuoteBomPackageReferenceDetail::getPackageReferenceId, reference.getId()));
    List<PackageLineSelection> selectedLines =
        selection.selectedLines() == null ? List.of() : selection.selectedLines();
    Map<String, PackageLineSelection> selectionByKey =
        selectedLines.stream()
            .filter(Objects::nonNull)
            .filter(line -> line.selected() == null || line.selected())
            .collect(Collectors.toMap(this::packageSelectionKey, Function.identity(), (first, ignored) -> first, LinkedHashMap::new));

    int lineCount = 0;
    int changeLogCount = 0;
    boolean edited = false;
    String batchNo = "QBP06-" + UUID.randomUUID().toString().replace("-", "");
    for (PackageComponentStructureLineDto sourceLine : source.lines()) {
      PackageLineSelection selected = selectionByKey.get(packageSourceKey(sourceLine));
      if (selected == null) {
        continue;
      }
      QuoteBomPackageReferenceDetail detail =
          toPackageDetail(reference, record, task, sourceLine, selected, now);
      boolean detailEdited = isPackageDetailEdited(detail);
      detail.setEditedFlag(detailEdited ? 1 : 0);
      packageReferenceDetailMapper.insert(detail);
      int insertedLogs =
          insertPackageChangeLogs(reference, detail, sourceLine, selected, request, batchNo, now);
      changeLogCount += insertedLogs;
      edited = edited || detailEdited;
      lineCount++;
    }
    reference.setSelectedLineCount(lineCount);
    reference.setEditedFlag(edited ? 1 : 0);
    packageReferenceMapper.updateById(reference);
    record.setReferenceFinishedCode(reference.getReferenceFinishedCode());
    record.setSourceTopProductCode(reference.getSourceTopProductCode());
    return new PackageSaveResult(reference.getId(), lineCount, changeLogCount);
  }

  private QuoteBomPackageReferenceDetail toPackageDetail(
      QuoteBomPackageReference reference,
      QuoteBomPreparationRecord record,
      BomSupplementTask task,
      PackageComponentStructureLineDto line,
      PackageLineSelection selected,
      LocalDateTime now) {
    QuoteBomPackageReferenceDetail detail = new QuoteBomPackageReferenceDetail();
    detail.setPackageReferenceId(reference.getId());
    detail.setPreparationId(record.getId());
    detail.setTaskId(task.getId());
    detail.setOaNo(record.getOaNo());
    detail.setOaFormItemId(record.getOaFormItemId());
    detail.setBareProductCode(record.getBareProductCode());
    detail.setReferenceFinishedCode(line.referenceFinishedCode());
    detail.setSourceTopProductCode(line.sourceTopProductCode());
    detail.setSnapshotId(line.snapshotId());
    detail.setSnapshotDetailId(line.snapshotDetailId());
    detail.setLineNo(line.lineNo());
    detail.setPackageParentCode(line.packageParentCode());
    detail.setPackageParentName(line.packageParentName());
    detail.setPackageParentSpec(line.packageParentSpec());
    detail.setPackageParentModel(line.packageParentModel());
    detail.setPackageParentDrawingNo(line.packageParentDrawingNo());
    detail.setPackageParentShapeAttr(line.packageParentShapeAttr());
    detail.setPackageParentMainCategoryCode(line.packageParentMainCategoryCode());
    detail.setPackageParentUnit(line.packageParentUnit());
    detail.setPackageParentCodeInReferenceBom(line.childSourceParentCode());
    detail.setPackageQtyPerParent(line.packageQtyPerParent());
    detail.setPackageQtyPerTop(line.packageQtyPerTop());
    detail.setPackageParentBaseQty(line.packageParentBaseQty());
    detail.setAdjustedPackageQtyPerParent(selected.adjustedPackageQtyPerParent());
    detail.setAdjustedPackageQtyPerTop(selected.adjustedPackageQtyPerTop());
    detail.setAdjustedPackageParentBaseQty(selected.adjustedPackageParentBaseQty());
    detail.setPackageMaterialCode(line.packageChildCode());
    detail.setPackageMaterialName(line.packageChildName());
    detail.setPackageMaterialSpec(line.packageChildSpec());
    detail.setPackageMaterialModel(line.packageChildModel());
    detail.setPackageMaterialDrawingNo(line.packageChildDrawingNo());
    detail.setPackageMaterialShapeAttr(line.packageChildShapeAttr());
    detail.setPackageMaterialMainCategoryCode(line.packageChildMainCategoryCode());
    detail.setPackageMaterialUnit(line.packageChildUnit());
    detail.setChildQtyPerParent(line.childQtyPerParent());
    detail.setChildQtyPerTop(line.childQtyPerTop());
    detail.setChildParentBaseQty(line.childParentBaseQty());
    detail.setAdjustedChildQtyPerParent(selected.adjustedChildQtyPerParent());
    detail.setAdjustedChildQtyPerTop(selected.adjustedChildQtyPerTop());
    detail.setAdjustedChildParentBaseQty(selected.adjustedChildParentBaseQty());
    detail.setQtyPerTop(firstNonNull(selected.adjustedChildQtyPerTop(), line.childQtyPerTop()));
    detail.setUnit(line.packageChildUnit());
    detail.setSourceRawHierarchyId(line.childSourceRawHierarchyId());
    detail.setSourceParentCode(line.childSourceParentCode());
    detail.setSourcePath(line.childSourcePath());
    detail.setSelectedFlag(1);
    detail.setRemark(trimToNull(selected.remark()));
    detail.setCreatedAt(now);
    detail.setUpdatedAt(now);
    return detail;
  }

  private int insertPackageChangeLogs(
      QuoteBomPackageReference reference,
      QuoteBomPackageReferenceDetail detail,
      PackageComponentStructureLineDto sourceLine,
      PackageLineSelection selected,
      BomSupplementCollaborationSubmitRequest request,
      String batchNo,
      LocalDateTime now) {
    int count = 0;
    count += logIfChanged(reference, detail, "adjustedPackageQtyPerParent", "包装父件用量", sourceLine.packageQtyPerParent(), selected.adjustedPackageQtyPerParent(), request, batchNo, now);
    count += logIfChanged(reference, detail, "adjustedPackageQtyPerTop", "包装父件累计用量", sourceLine.packageQtyPerTop(), selected.adjustedPackageQtyPerTop(), request, batchNo, now);
    count += logIfChanged(reference, detail, "adjustedPackageParentBaseQty", "包装父件母件底数", sourceLine.packageParentBaseQty(), selected.adjustedPackageParentBaseQty(), request, batchNo, now);
    count += logIfChanged(reference, detail, "adjustedChildQtyPerParent", "包装子件用量", sourceLine.childQtyPerParent(), selected.adjustedChildQtyPerParent(), request, batchNo, now);
    count += logIfChanged(reference, detail, "adjustedChildQtyPerTop", "包装子件累计用量", sourceLine.childQtyPerTop(), selected.adjustedChildQtyPerTop(), request, batchNo, now);
    count += logIfChanged(reference, detail, "adjustedChildParentBaseQty", "包装子件母件底数", sourceLine.childParentBaseQty(), selected.adjustedChildParentBaseQty(), request, batchNo, now);
    return count;
  }

  private int logIfChanged(
      QuoteBomPackageReference reference,
      QuoteBomPackageReferenceDetail detail,
      String fieldName,
      String fieldLabel,
      BigDecimal before,
      BigDecimal after,
      BomSupplementCollaborationSubmitRequest request,
      String batchNo,
      LocalDateTime now) {
    if (after == null || compareDecimal(before, after)) {
      return 0;
    }
    BusinessChangeLog log = new BusinessChangeLog();
    log.setBizDomain("QUOTE_BOM_PREPARATION");
    log.setBizType("PACKAGE_REFERENCE_DETAIL");
    log.setBizId(reference.getId());
    log.setBizDetailId(detail.getId());
    log.setOaNo(detail.getOaNo());
    log.setOaFormItemId(detail.getOaFormItemId());
    log.setTaskId(detail.getTaskId());
    log.setFieldName(fieldName);
    log.setFieldLabel(fieldLabel);
    log.setBeforeValue(decimalText(before));
    log.setAfterValue(decimalText(after));
    log.setChangeReason(trimToNull(request == null ? null : request.remark()));
    log.setChangedBy(request == null ? null : request.submittedBy());
    log.setChangedByName(trimToNull(request == null ? null : request.submittedByName()));
    log.setChangedAt(now);
    log.setChangeSource("OA_COLLABORATION");
    log.setSubmitBatchNo(batchNo);
    log.setCreatedAt(now);
    changeLogMapper.insert(log);
    return 1;
  }

  private TokenScope validateTokenScope(String token, Long expectedTaskId) {
    LpCollaborationToken record = tokenService.validateToken(trimToNull(token));
    if (record == null || !TOKEN_TYPE.equals(record.getTokenType())) {
      throw new QuoteIngestException("协作令牌无效或已过期");
    }
    TokenRemark remark = parseTokenRemark(record.getRemark());
    if (expectedTaskId != null && !Objects.equals(expectedTaskId, remark.taskId())) {
      throw new QuoteIngestException("协作令牌不能访问该任务");
    }
    return new TokenScope(
        record.getTokenId(),
        record.getExpireTime(),
        remark.taskId(),
        remark.oaFormItemId(),
        remark.oaNo(),
        remark.quoteProductCode());
  }

  private TokenRemark parseTokenRemark(String remark) {
    try {
      TokenRemark parsed = objectMapper.readValue(remark, TokenRemark.class);
      if (parsed.taskId() == null || parsed.oaFormItemId() == null) {
        throw new QuoteIngestException("协作令牌缺少任务上下文");
      }
      return parsed;
    } catch (JsonProcessingException ex) {
      throw new QuoteIngestException("协作令牌上下文不可解析");
    }
  }

  private String toTokenRemark(QuoteProductBomPreparationPreview preview) {
    try {
      return objectMapper.writeValueAsString(
          new TokenRemark(preview.taskId(), preview.oaFormItemId(), preview.oaNo(), preview.quoteProductCode()));
    } catch (JsonProcessingException ex) {
      throw new QuoteIngestException("协作令牌上下文生成失败");
    }
  }

  private BomSupplementTask loadTask(Long taskId) {
    if (taskId == null) {
      throw new QuoteIngestException("任务 ID 不能为空");
    }
    BomSupplementTask task = taskMapper.selectById(taskId);
    if (task == null) {
      throw new QuoteIngestException("BOM 补录任务不存在: " + taskId);
    }
    return task;
  }

  private QuoteBomPreparationRecord loadPreparationByTask(Long taskId) {
    return preparationRecordMapper.selectOne(
        Wrappers.<QuoteBomPreparationRecord>lambdaQuery()
            .eq(QuoteBomPreparationRecord::getTaskId, taskId)
            .eq(QuoteBomPreparationRecord::getActiveFlag, ACTIVE)
            .orderByDesc(QuoteBomPreparationRecord::getUpdatedAt)
            .orderByDesc(QuoteBomPreparationRecord::getId)
            .last("LIMIT 1"));
  }

  private QuoteBomStatus findQuoteBomStatus(QuoteBomPreparationRecord record) {
    if (record == null) {
      return null;
    }
    if (record.getQuoteBomStatusId() != null) {
      QuoteBomStatus status = statusMapper.selectById(record.getQuoteBomStatusId());
      if (status != null) {
        return status;
      }
    }
    return statusMapper.selectOne(
        Wrappers.<QuoteBomStatus>lambdaQuery()
            .eq(QuoteBomStatus::getOaFormItemId, record.getOaFormItemId())
            .last("LIMIT 1"));
  }

  private void requireScopeMatchesRecord(TokenScope scope, QuoteBomPreparationRecord record) {
    if (record == null) {
      return;
    }
    if (!Objects.equals(scope.oaFormItemId(), record.getOaFormItemId())
        || !Objects.equals(trimToNull(scope.quoteProductCode()), trimToNull(record.getQuoteProductCode()))) {
      throw new QuoteIngestException("协作令牌不能访问该报价产品行");
    }
  }

  private QuoteBomSupplementVersion latestSupplementVersion(Long taskId) {
    return supplementVersionMapper.selectOne(
        Wrappers.<QuoteBomSupplementVersion>lambdaQuery()
            .eq(QuoteBomSupplementVersion::getTaskId, taskId)
            .eq(QuoteBomSupplementVersion::getActiveFlag, ACTIVE)
            .orderByDesc(QuoteBomSupplementVersion::getUpdatedAt)
            .orderByDesc(QuoteBomSupplementVersion::getId)
            .last("LIMIT 1"));
  }

  private QuoteBomPackageReference latestPackageReference(Long taskId) {
    return packageReferenceMapper.selectOne(
        Wrappers.<QuoteBomPackageReference>lambdaQuery()
            .eq(QuoteBomPackageReference::getTaskId, taskId)
            .eq(QuoteBomPackageReference::getActiveFlag, ACTIVE)
            .orderByDesc(QuoteBomPackageReference::getUpdatedAt)
            .orderByDesc(QuoteBomPackageReference::getId)
            .last("LIMIT 1"));
  }

  private List<QuoteBomSupplementDetailDto> toSupplementLineDtos(Long versionId) {
    List<QuoteBomSupplementDetail> rows =
        supplementDetailMapper.selectList(
            Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
                .eq(QuoteBomSupplementDetail::getSupplementVersionId, versionId)
                .orderByAsc(QuoteBomSupplementDetail::getLineNo));
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<QuoteBomSupplementDetailDto> result = new ArrayList<>();
    for (QuoteBomSupplementDetail row : rows) {
      QuoteBomSupplementDetailDto dto = new QuoteBomSupplementDetailDto();
      dto.setId(row.getId());
      dto.setSupplementVersionId(row.getSupplementVersionId());
      dto.setSupplementScope(row.getSupplementScope());
      dto.setLineNo(row.getLineNo());
      dto.setLevel(row.getLevel());
      dto.setParentCode(row.getParentCode());
      dto.setMaterialCode(row.getMaterialCode());
      dto.setMaterialName(row.getMaterialName());
      dto.setMaterialSpec(row.getMaterialSpec());
      dto.setMaterialModel(row.getMaterialModel());
      dto.setDrawingNo(row.getDrawingNo());
      dto.setShapeAttr(row.getShapeAttr());
      dto.setMainCategoryCode(row.getMainCategoryCode());
      dto.setQtyPerParent(row.getQtyPerParent());
      dto.setQtyPerTop(row.getQtyPerTop());
      dto.setParentBaseQty(row.getParentBaseQty());
      dto.setUnit(row.getUnit());
      dto.setRemark(row.getRemark());
      result.add(dto);
    }
    return result;
  }

  private List<QuoteBomPackageReferenceDetailDto> toPackageLineDtos(Long packageReferenceId) {
    List<QuoteBomPackageReferenceDetail> rows =
        packageReferenceDetailMapper.selectList(
            Wrappers.<QuoteBomPackageReferenceDetail>lambdaQuery()
                .eq(QuoteBomPackageReferenceDetail::getPackageReferenceId, packageReferenceId)
                .orderByAsc(QuoteBomPackageReferenceDetail::getLineNo));
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<QuoteBomPackageReferenceDetailDto> result = new ArrayList<>();
    for (QuoteBomPackageReferenceDetail row : rows) {
      QuoteBomPackageReferenceDetailDto dto = new QuoteBomPackageReferenceDetailDto();
      dto.setId(row.getId());
      dto.setPackageReferenceId(row.getPackageReferenceId());
      dto.setReferenceFinishedCode(row.getReferenceFinishedCode());
      dto.setSourceTopProductCode(row.getSourceTopProductCode());
      dto.setPackageParentCode(row.getPackageParentCode());
      dto.setPackageParentName(row.getPackageParentName());
      dto.setPackageParentSpec(row.getPackageParentSpec());
      dto.setPackageParentModel(row.getPackageParentModel());
      dto.setPackageParentDrawingNo(row.getPackageParentDrawingNo());
      dto.setPackageParentShapeAttr(row.getPackageParentShapeAttr());
      dto.setPackageParentMainCategoryCode(row.getPackageParentMainCategoryCode());
      dto.setPackageParentUnit(row.getPackageParentUnit());
      dto.setPackageQtyPerParent(row.getPackageQtyPerParent());
      dto.setPackageQtyPerTop(row.getPackageQtyPerTop());
      dto.setPackageParentBaseQty(row.getPackageParentBaseQty());
      dto.setAdjustedPackageQtyPerParent(row.getAdjustedPackageQtyPerParent());
      dto.setAdjustedPackageQtyPerTop(row.getAdjustedPackageQtyPerTop());
      dto.setAdjustedPackageParentBaseQty(row.getAdjustedPackageParentBaseQty());
      dto.setPackageMaterialCode(row.getPackageMaterialCode());
      dto.setPackageMaterialName(row.getPackageMaterialName());
      dto.setPackageMaterialSpec(row.getPackageMaterialSpec());
      dto.setPackageMaterialModel(row.getPackageMaterialModel());
      dto.setPackageMaterialDrawingNo(row.getPackageMaterialDrawingNo());
      dto.setPackageMaterialShapeAttr(row.getPackageMaterialShapeAttr());
      dto.setPackageMaterialMainCategoryCode(row.getPackageMaterialMainCategoryCode());
      dto.setPackageMaterialUnit(row.getPackageMaterialUnit());
      dto.setChildQtyPerParent(row.getChildQtyPerParent());
      dto.setChildQtyPerTop(row.getChildQtyPerTop());
      dto.setAdjustedChildQtyPerParent(row.getAdjustedChildQtyPerParent());
      dto.setAdjustedChildQtyPerTop(row.getAdjustedChildQtyPerTop());
      dto.setChildParentBaseQty(row.getChildParentBaseQty());
      dto.setAdjustedChildParentBaseQty(row.getAdjustedChildParentBaseQty());
      dto.setQtyPerTop(row.getQtyPerTop());
      dto.setUnit(row.getUnit());
      dto.setRemark(row.getRemark());
      dto.setSelected(row.getSelectedFlag() != null && row.getSelectedFlag() == 1);
      dto.setEdited(row.getEditedFlag() != null && row.getEditedFlag() == 1);
      result.add(dto);
    }
    return result;
  }

  private BomSupplementTaskQueryResponse.Row toTaskQueryRow(
      BomSupplementTask task, QuoteBomPreparationRecord record, QuoteBomPackageReference reference) {
    int supplementLineCount = 0;
    QuoteBomSupplementVersion version = latestSupplementVersion(task.getId());
    if (version != null) {
      Long count =
          supplementDetailMapper.selectCount(
              Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
                  .eq(QuoteBomSupplementDetail::getSupplementVersionId, version.getId()));
      supplementLineCount = count == null ? 0 : count.intValue();
    }
    return new BomSupplementTaskQueryResponse.Row(
        task.getId(),
        task.getTaskNo(),
        taskType(record),
        task.getProductCode(),
        task.getProductName(),
        task.getProductModel(),
        task.getCustomerCode(),
        record == null ? null : record.getCostPeriodMonth(),
        task.getTaskStatus(),
        record == null ? null : record.getPreparationStatus(),
        record == null ? null : record.getReviewStatus(),
        task.getTechnicianName(),
        reference == null ? null : reference.getReferenceFinishedCode(),
        reference == null ? null : reference.getSourceTopProductCode(),
        supplementLineCount,
        reference == null || reference.getSelectedLineCount() == null ? 0 : reference.getSelectedLineCount(),
        reference == null ? null : reference.getEditedFlag(),
        task.getCreatedAt(),
        task.getUpdatedAt());
  }

  private List<ChangeLogLine> toChangeLogLines(Long taskId) {
    List<BusinessChangeLog> rows =
        changeLogMapper.selectList(
            Wrappers.<BusinessChangeLog>lambdaQuery()
                .eq(BusinessChangeLog::getTaskId, taskId)
                .orderByDesc(BusinessChangeLog::getChangedAt)
                .orderByDesc(BusinessChangeLog::getId));
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    return rows.stream()
        .map(
            row ->
                new ChangeLogLine(
                    row.getId(),
                    row.getBizDetailId(),
                    row.getFieldName(),
                    row.getFieldLabel(),
                    row.getBeforeValue(),
                    row.getAfterValue(),
                    row.getChangeReason(),
                    row.getChangedBy(),
                    row.getChangedByName(),
                    row.getChangedAt() == null ? null : row.getChangedAt().toString(),
                    row.getChangeSource()))
        .toList();
  }

  private List<CompleteBomLine> buildCompleteBomPreview(
      QuoteBomPreparationRecord record,
      QuoteProductBomPreparationPreview preview,
      List<QuoteBomSupplementDetailDto> supplementLines,
      List<QuoteBomPackageReferenceDetailDto> packageLines) {
    List<CompleteBomLine> result = new ArrayList<>();
    if (preview != null && preview.bodyBomReady() && preview.bodyBomLines() != null && !preview.bodyBomLines().isEmpty()) {
      for (QuoteBomSourceLineDto line : preview.bodyBomLines()) {
        result.add(toCompleteBodyLine("FORMAL_BODY_BOM", line, null));
      }
    } else {
      for (QuoteBomSupplementDetailDto line : supplementLines == null ? List.<QuoteBomSupplementDetailDto>of() : supplementLines) {
        result.add(toCompleteSupplementLine(record, line));
      }
    }
    int lineNo = result.size() + 1;
    for (QuoteBomPackageReferenceDetailDto line : packageLines == null ? List.<QuoteBomPackageReferenceDetailDto>of() : packageLines) {
      if (line.getSelected() != null && !line.getSelected()) {
        continue;
      }
      result.add(toCompletePackageLine(line, lineNo++));
    }
    return result;
  }

  private CompleteBomLine toCompleteBodyLine(
      String sourceType, QuoteBomSourceLineDto line, String remark) {
    return new CompleteBomLine(
        sourceType,
        line.lineNo(),
        line.level(),
        line.topProductCode(),
        line.parentCode(),
        line.materialCode(),
        line.materialName(),
        line.materialSpec(),
        line.materialModel(),
        line.drawingNo(),
        line.shapeAttr(),
        line.mainCategoryCode(),
        line.unit(),
        null,
        null,
        line.qtyPerParent(),
        line.qtyPerTop(),
        line.parentBaseQty(),
        remark,
        line.manualFlag() != null && line.manualFlag() == 1);
  }

  private CompleteBomLine toCompleteSupplementLine(
      QuoteBomPreparationRecord record, QuoteBomSupplementDetailDto line) {
    return new CompleteBomLine(
        "TECH_SUPPLEMENT_BODY",
        line.getLineNo(),
        line.getLevel(),
        record == null ? null : record.getQuoteProductCode(),
        line.getParentCode(),
        line.getMaterialCode(),
        line.getMaterialName(),
        line.getMaterialSpec(),
        line.getMaterialModel(),
        line.getDrawingNo(),
        line.getShapeAttr(),
        line.getMainCategoryCode(),
        line.getUnit(),
        null,
        null,
        line.getQtyPerParent(),
        line.getQtyPerTop(),
        line.getParentBaseQty(),
        line.getRemark(),
        true);
  }

  private CompleteBomLine toCompletePackageLine(
      QuoteBomPackageReferenceDetailDto line, int lineNo) {
    return new CompleteBomLine(
        "PACKAGE_REFERENCE",
        lineNo,
        null,
        line.getSourceTopProductCode(),
        line.getPackageParentCode(),
        line.getPackageMaterialCode(),
        line.getPackageMaterialName(),
        line.getPackageMaterialSpec(),
        line.getPackageMaterialModel(),
        line.getPackageMaterialDrawingNo(),
        line.getPackageMaterialShapeAttr(),
        line.getPackageMaterialMainCategoryCode(),
        firstText(line.getUnit(), line.getPackageMaterialUnit()),
        line.getReferenceFinishedCode(),
        line.getSourceTopProductCode(),
        firstNonNull(line.getAdjustedChildQtyPerParent(), line.getChildQtyPerParent()),
        firstNonNull(line.getAdjustedChildQtyPerTop(), line.getChildQtyPerTop()),
        firstNonNull(line.getAdjustedChildParentBaseQty(), line.getChildParentBaseQty()),
        line.getRemark(),
        Boolean.TRUE.equals(line.getEdited()));
  }

  private TaskHeader toTaskHeader(BomSupplementTask task, QuoteBomPreparationRecord record) {
    return new TaskHeader(
        task.getId(),
        task.getTaskNo(),
        taskType(record),
        task.getProductCode(),
        task.getProductName(),
        task.getProductModel(),
        task.getCustomerCode(),
        task.getPackageMethod(),
        task.getMissingBomScope(),
        task.getMissingReason(),
        task.getTaskStatus(),
        task.getTechnicianName(),
        task.getRemark());
  }

  private PackageReference toPackageReferenceDto(QuoteBomPackageReference reference) {
    if (reference == null) {
      return null;
    }
    return new PackageReference(
        reference.getId(),
        reference.getBareProductCode(),
        reference.getReferenceFinishedCode(),
        reference.getSourceTopProductCode(),
        reference.getPeriodMonth(),
        reference.getReferenceStatus(),
        reference.getSelectedLineCount(),
        reference.getEditedFlag());
  }

  private String supplementScope(QuoteBomPreparationRecord record) {
    if (record == null) {
      return null;
    }
    if (PRODUCT_TYPE_NON_BARE.equals(record.getProductType())) {
      return SCOPE_NON_BARE_FULL_BOM;
    }
    return SCOPE_BARE_BODY_BOM;
  }

  private String taskType(QuoteBomPreparationRecord record) {
    if (record == null) {
      return null;
    }
    return taskType(record.getProductType(), splitScopes(record.getNeedPackage(), record.getProductType(), record.getReferenceFinishedCode()));
  }

  private String taskType(String productType, Collection<String> missingScopes) {
    Set<String> scopes = new LinkedHashSet<>(missingScopes == null ? List.of() : missingScopes);
    if (PRODUCT_TYPE_NON_BARE.equals(productType)) {
      return "NON_BARE_FULL_BOM";
    }
    boolean body = scopes.contains(SCOPE_BARE_BODY_BOM);
    boolean pkg = scopes.contains(SCOPE_PACKAGE_REFERENCE);
    if (body && pkg) {
      return "BARE_BODY_BOM_AND_PACKAGE_REFERENCE";
    }
    if (pkg) {
      return "BARE_PACKAGE_REFERENCE";
    }
    return "BARE_BODY_BOM";
  }

  private List<String> splitScopes(Integer needPackage, String productType, String referenceFinishedCode) {
    if (PRODUCT_TYPE_NON_BARE.equals(productType)) {
      return List.of(SCOPE_NON_BARE_FULL_BOM);
    }
    List<String> scopes = new ArrayList<>();
    scopes.add(SCOPE_BARE_BODY_BOM);
    if (needPackage != null && needPackage == 1 && trimToNull(referenceFinishedCode) == null) {
      scopes.add(SCOPE_PACKAGE_REFERENCE);
    }
    return scopes;
  }

  private String todoPayload(
      QuoteProductBomPreparationPreview preview, String token, LocalDateTime expireTime) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("taskId", preview.taskId());
      payload.put("oaFormItemId", preview.oaFormItemId());
      payload.put("oaNo", preview.oaNo());
      payload.put("productCode", preview.quoteProductCode());
      payload.put("token", token);
      payload.put("expireTime", expireTime);
      payload.put("url", COLLABORATION_URL + token);
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException ex) {
      return "{}";
    }
  }

  private List<Long> normalizeIds(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    Set<Long> normalized = new LinkedHashSet<>();
    for (Long id : ids) {
      if (id != null && id > 0) {
        normalized.add(id);
      }
    }
    return new ArrayList<>(normalized);
  }

  private int normalizeExpireHours(Integer expireHours) {
    if (expireHours == null || expireHours <= 0) {
      return 72;
    }
    return Math.min(expireHours, 24 * 30);
  }

  private String packageSelectionKey(PackageLineSelection selection) {
    return selection.snapshotId() + ":" + selection.snapshotDetailId() + ":" + selection.lineNo();
  }

  private String packageSourceKey(PackageComponentStructureLineDto line) {
    return line.snapshotId() + ":" + line.snapshotDetailId() + ":" + line.lineNo();
  }

  private boolean isPackageDetailEdited(QuoteBomPackageReferenceDetail detail) {
    return detail.getAdjustedPackageQtyPerParent() != null
        || detail.getAdjustedPackageQtyPerTop() != null
        || detail.getAdjustedPackageParentBaseQty() != null
        || detail.getAdjustedChildQtyPerParent() != null
        || detail.getAdjustedChildQtyPerTop() != null
        || detail.getAdjustedChildParentBaseQty() != null;
  }

  private boolean compareDecimal(BigDecimal before, BigDecimal after) {
    if (before == null && after == null) {
      return true;
    }
    if (before == null || after == null) {
      return false;
    }
    return before.compareTo(after) == 0;
  }

  private String decimalText(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }

  private BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
    return first == null ? second : first;
  }

  private String firstText(String first, String second) {
    String value = trimToNull(first);
    return value == null ? trimToNull(second) : value;
  }

  private boolean contains(String value, String keyword) {
    return trimToNull(value) != null && trimToNull(value).contains(keyword);
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private record TokenRemark(Long taskId, Long oaFormItemId, String oaNo, String quoteProductCode) {}

  private record TokenScope(
      Long tokenId,
      LocalDateTime expireTime,
      Long taskId,
      Long oaFormItemId,
      String oaNo,
      String quoteProductCode) {}

  private record PackageSaveResult(Long packageReferenceId, int lineCount, int changeLogCount) {}
}
