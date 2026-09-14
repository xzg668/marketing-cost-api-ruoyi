package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProductResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataReviewDecisionRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataReviewDecisionResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataReviewItemDetailResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataReviewTaskResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPageResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskSummaryResponse;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechReviewItem;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.QuoteTechReviewItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TechnicalDataReviewApplicationServiceImpl
    implements TechnicalDataReviewApplicationService {
  private static final Set<String> REVIEWABLE_TASK_STATUSES = Set.of("SUBMITTED");
  private static final Set<String> FILTER_STATUSES = Set.of(
      "SUBMITTED", "PARTIALLY_RETURNED", "APPROVED", "CANCELLED");

  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository taskRepository;
  private final QuoteTechnicalDataPersistenceService persistenceService;
  private final TechnicalDataTaskApplicationService taskService;
  private final TechnicalDataPackageApplicationService packageService;
  private final TechnicalDataAuxiliaryApplicationService auxiliaryService;
  private final TechnicalDataSalaryApplicationService salaryService;
  private final QuoteTechReviewItemMapper reviewMapper;
  private final QuoteTechTaskMapper taskMapper;
  private final TechnicalDataAuditLogService auditLog;

  public TechnicalDataReviewApplicationServiceImpl(
      QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository taskRepository,
      QuoteTechnicalDataPersistenceService persistenceService,
      TechnicalDataTaskApplicationService taskService,
      TechnicalDataPackageApplicationService packageService,
      TechnicalDataAuxiliaryApplicationService auxiliaryService,
      TechnicalDataSalaryApplicationService salaryService,
      QuoteTechReviewItemMapper reviewMapper,
      QuoteTechTaskMapper taskMapper,
      TechnicalDataAuditLogService auditLog) {
    this.repository = repository;
    this.taskRepository = taskRepository;
    this.persistenceService = persistenceService;
    this.taskService = taskService;
    this.packageService = packageService;
    this.auxiliaryService = auxiliaryService;
    this.salaryService = salaryService;
    this.reviewMapper = reviewMapper;
    this.taskMapper = taskMapper;
    this.auditLog = auditLog;
  }

  @Override
  @Transactional(readOnly = true)
  public TechnicalDataTaskPageResponse mine(
      int current, int size, String taskStatus, String accountingMonth, TechnicalDataActor actor) {
    requireReviewer(actor, false);
    if (actor.shortSession()) throw forbidden("短时任务会话不允许查询审核列表");
    if (current <= 0 || size <= 0 || size > 100) throw invalid("分页参数无效");
    String status = normalizeStatus(taskStatus);
    String month = normalizeMonth(accountingMonth);
    String accessMode = actor.admin() ? "ALL" : "REVIEWER";
    long total = taskRepository.countAccessible(
        accessMode, actor.userId(), status, month);
    List<TechnicalDataTaskSummaryResponse> records = taskRepository.findAccessiblePage(
            accessMode, actor.userId(), status, month, (current - 1) * size, size).stream()
        .map(this::summary).toList();
    return new TechnicalDataTaskPageResponse(total, current, size, records);
  }

  @Override
  @Transactional(readOnly = true)
  public TechnicalDataReviewTaskResponse detail(Long taskId, TechnicalDataActor actor) {
    requireReviewer(actor, false);
    QuoteTechTask task = requireTask(taskId);
    requireReviewAccess(task, actor);
    TechnicalDataTaskResponse taskResponse = taskService.detail(task.getId(), actor);
    List<QuoteTechReviewItem> items = repository.findReviewItems(
        task.getId(), task.getReviewRound());
    Map<Long, Integer> versionNos = items.stream()
        .map(QuoteTechReviewItem::getSubmittedVersionId).distinct()
        .map(repository::findVersion).flatMap(java.util.Optional::stream)
        .collect(Collectors.toMap(QuoteTechDataVersion::getId, QuoteTechDataVersion::getVersionNo));
    List<TechnicalDataReviewTaskResponse.Item> values = items.stream()
        .map(item -> response(item, versionNos.get(item.getSubmittedVersionId())))
        .toList();
    return new TechnicalDataReviewTaskResponse(
        taskResponse,
        count(values, "PENDING"),
        count(values, "PASSED"),
        count(values, "RETURNED"),
        values);
  }

  @Override
  @Transactional(readOnly = true)
  public TechnicalDataReviewItemDetailResponse itemDetail(
      Long taskId, Long itemId, TechnicalDataActor actor) {
    TechnicalDataReviewTaskResponse review = detail(taskId, actor);
    TechnicalDataReviewTaskResponse.Item item = review.reviewItems().stream()
        .filter(value -> Objects.equals(value.id(), positive(itemId, "itemId")))
        .findFirst().orElseThrow(() -> notFound("审核项不存在或不属于当前审核轮次"));
    TechnicalDataProductResponse product = review.task().products().stream()
        .filter(value -> Objects.equals(value.id(), item.productId()))
        .findFirst().orElseThrow(() -> notFound("审核产品不存在"));
    Object moduleData = switch (item.moduleType()) {
      case "PROFILE" -> product.profile();
      case "PACKAGE" -> packageService.get(product.id(), actor);
      case "AUXILIARY" -> auxiliaryService.get(product.id(), actor);
      case "SALARY" -> salaryService.get(product.id(), actor);
      default -> throw conflict("审核模块类型无效");
    };
    List<String> editableModules = product.modules().stream()
        .filter(module -> Set.of("RETURNED", "EDITING").contains(module.moduleStatus()))
        .map(module -> module.moduleType()).toList();
    return new TechnicalDataReviewItemDetailResponse(
        item, product, moduleData, editableModules);
  }

  @Override
  @Transactional
  public TechnicalDataReviewDecisionResponse decide(
      Long taskId,
      Long itemId,
      String decisionValue,
      TechnicalDataReviewDecisionRequest request,
      TechnicalDataActor actor) {
    requireReviewer(actor, true);
    if (request == null || !request.getUnknownFields().isEmpty()) {
      throw invalid(request == null ? "请求体不能为空" : "请求包含未知字段");
    }
    String decision = decision(decisionValue);
    String reason = reason(request.getReason(), "RETURNED".equals(decision) || actor.admin());
    int expectedTaskVersion = expected(request.getExpectedTaskVersion(), "expectedTaskVersion");
    int expectedItemVersion = expected(
        request.getExpectedReviewItemVersion(), "expectedReviewItemVersion");
    Long productId = positive(request.getProductId(), "productId");
    Long submittedVersionId = positive(request.getSubmittedVersionId(), "submittedVersionId");

    QuoteTechTask task = repository.lockTask(positive(taskId, "taskId"))
        .orElseThrow(() -> notFound("技术资料审核任务不存在"));
    requireReviewAccess(task, actor);
    if (!REVIEWABLE_TASK_STATUSES.contains(task.getTaskStatus())
        || !"PENDING".equals(task.getReviewStatus())) {
      throw conflict("任务当前不在审核中");
    }
    if (!Objects.equals(task.getTaskVersion(), expectedTaskVersion)) {
      throw conflict("任务已被其他会话修改；当前版本=" + task.getTaskVersion());
    }
    QuoteTechReviewItem locked = reviewMapper.selectByIdForUpdate(positive(itemId, "itemId"));
    if (locked == null
        || !Objects.equals(locked.getTaskId(), task.getId())
        || !Objects.equals(locked.getReviewRound(), task.getReviewRound())
        || !Objects.equals(locked.getProductId(), productId)
        || !Objects.equals(locked.getSubmittedVersionId(), submittedVersionId)) {
      throw notFound("审核项不存在或边界参数不匹配");
    }
    if (!"PENDING".equals(locked.getDecision())) throw conflict("该审核项已处理，不能重复审核");
    QuoteTechProduct product = repository.lockProduct(productId)
        .orElseThrow(() -> notFound("审核产品不存在"));
    if (!Objects.equals(product.getTaskId(), task.getId())) throw conflict("审核产品不属于任务");
    QuoteTechDataVersion submitted = repository.lockVersion(submittedVersionId)
        .orElseThrow(() -> notFound("审核提交版本不存在"));
    if (!Objects.equals(submitted.getProductId(), product.getId())) {
      throw conflict("审核版本不属于产品");
    }
    if (Objects.equals(submitted.getSubmittedBy(), actor.userId())
        || Objects.equals(submitted.getUpdatedBy(), actor.userId())) {
      throw forbidden("不能审核本人代录、本人提交或本人最后修改的数据");
    }
    LocalDateTime changedAt = now();
    if (reviewMapper.decidePending(
        locked.getId(), task.getId(), task.getReviewRound(), product.getId(), submitted.getId(),
        expectedItemVersion, decision, reason, actor.userId(), actor.name(), changedAt) != 1) {
      throw conflict("审核项已被其他会话处理");
    }
    auditLog.record(
        task, product, locked.getId(), "MODULE_REVIEW_" + decision,
        "PENDING", decision, reason, actor, "review-item:" + locked.getId());

    List<QuoteTechReviewItem> roundItems = repository.findReviewItems(
        task.getId(), task.getReviewRound());
    int pending = (int) roundItems.stream().filter(item -> "PENDING".equals(item.getDecision())).count();
    int returned = (int) roundItems.stream().filter(item -> "RETURNED".equals(item.getDecision())).count();
    boolean completed = pending == 0;
    boolean effective = false;
    if (completed) {
      if (returned > 0) completeReturnedRound(task, roundItems, actor, changedAt);
      else {
        completeApprovedRound(task, roundItems, actor, changedAt);
        effective = true;
      }
    }
    QuoteTechTask current = repository.findTask(task.getId())
        .orElseThrow(() -> conflict("审核后任务不存在"));
    TechnicalDataReviewTaskResponse detail = detail(current.getId(), actor);
    return new TechnicalDataReviewDecisionResponse(
        current.getId(), locked.getId(), decision, current.getTaskStatus(),
        current.getReviewStatus(), current.getReviewRound(), current.getTaskVersion(),
        detail.pendingCount(), detail.returnedCount(), completed, effective, detail);
  }

  private void completeReturnedRound(
      QuoteTechTask task,
      List<QuoteTechReviewItem> items,
      TechnicalDataActor actor,
      LocalDateTime changedAt) {
    if (taskMapper.markReviewReturned(
        task.getId(), task.getTaskVersion(), task.getReviewRound(), actor.userId(), changedAt) != 1) {
      throw conflict("审核退回时任务状态已变化");
    }
    Map<Long, List<QuoteTechReviewItem>> byProduct = items.stream()
        .collect(Collectors.groupingBy(QuoteTechReviewItem::getProductId));
    for (Map.Entry<Long, List<QuoteTechReviewItem>> entry : byProduct.entrySet()) {
      QuoteTechProduct product = repository.lockProduct(entry.getKey())
          .orElseThrow(() -> notFound("退回产品不存在"));
      Map<String, QuoteTechReviewItem> decisions = entry.getValue().stream()
          .collect(Collectors.toMap(QuoteTechReviewItem::getModuleType, Function.identity()));
      Set<String> returnedTypes = decisions.values().stream()
          .filter(item -> "RETURNED".equals(item.getDecision()))
          .map(QuoteTechReviewItem::getModuleType).collect(Collectors.toCollection(HashSet::new));
      for (QuoteTechModule module : repository.lockModules(product.getId())) {
        QuoteTechReviewItem item = decisions.get(module.getModuleType());
        if (item == null || !Integer.valueOf(1).equals(module.getRequiredFlag())) continue;
        int version = module.getRowVersion();
        module.setModuleStatus(returnedTypes.contains(module.getModuleType())
            ? "RETURNED" : "APPROVED");
        if (repository.updateModule(module, version, changedAt) != 1) {
          throw conflict("退回模块状态已被其他会话修改");
        }
      }
      if (!returnedTypes.isEmpty()) {
        QuoteTechDataVersion source = repository.lockVersion(product.getLatestSubmittedVersionId())
            .orElseThrow(() -> notFound("退回来源版本不存在"));
        persistenceService.transitionVersion(
            source.getId(), QuoteTechDataVersion.STATUS_SUBMITTED,
            QuoteTechDataVersion.STATUS_RETURNED, source.getRowVersion(),
            source.getContentFingerprint(), actor.userId());
        QuoteTechDataVersion draft = persistenceService.copyReturnedModulesAsDraft(
            product.getId(), source.getId(), product.getRowVersion(), actor.userId(), returnedTypes);
        auditLog.record(
            task, product, draft.getId(), "RETURNED_VERSION_COPIED",
            "V" + source.getVersionNo(), "V" + draft.getVersionNo(),
            "仅开放退回模块：" + returnedTypes.stream().sorted().collect(Collectors.joining(",")),
            actor, "review-round:" + task.getReviewRound());
      }
    }
    auditLog.record(
        task, null, null, "REVIEW_ROUND_RETURNED", "SUBMITTED", "PARTIALLY_RETURNED",
        "第" + task.getReviewRound() + "轮存在退回模块", actor,
        "review-round:" + task.getReviewRound());
  }

  private void completeApprovedRound(
      QuoteTechTask task,
      List<QuoteTechReviewItem> items,
      TechnicalDataActor actor,
      LocalDateTime changedAt) {
    Map<Long, List<QuoteTechReviewItem>> byProduct = items.stream()
        .collect(Collectors.groupingBy(QuoteTechReviewItem::getProductId));
    for (Long productId : byProduct.keySet()) {
      QuoteTechProduct product = repository.lockProduct(productId)
          .orElseThrow(() -> notFound("生效产品不存在"));
      QuoteTechDataVersion submitted = repository.lockVersion(product.getLatestSubmittedVersionId())
          .orElseThrow(() -> notFound("生效提交版本不存在"));
      persistenceService.transitionVersion(
          submitted.getId(), QuoteTechDataVersion.STATUS_SUBMITTED,
          QuoteTechDataVersion.STATUS_APPROVED, submitted.getRowVersion(),
          submitted.getContentFingerprint(), actor.userId());
      for (QuoteTechModule module : repository.lockModules(product.getId())) {
        if (!Integer.valueOf(1).equals(module.getRequiredFlag())) continue;
        int version = module.getRowVersion();
        module.setModuleStatus("APPROVED");
        module.setCurrentVersionId(submitted.getId());
        if (repository.updateModule(module, version, changedAt) != 1) {
          throw conflict("生效模块状态已被其他会话修改");
        }
      }
      int productVersion = product.getRowVersion();
      product.setProductStatus("APPROVED");
      product.setCurrentEditVersionId(null);
      product.setLatestSubmittedVersionId(submitted.getId());
      product.setEffectiveVersionId(submitted.getId());
      product.setEffectiveReviewRound(task.getReviewRound());
      product.setEffectiveAt(changedAt);
      if (repository.updateProductPointers(product, productVersion, changedAt) != 1) {
        throw conflict("产品有效版本已被其他会话修改");
      }
      auditLog.record(
          task, product, submitted.getId(), "EFFECTIVE_VERSION_ACTIVATED",
          null, String.valueOf(submitted.getId()), "全部必审模块通过", actor,
          "review-round:" + task.getReviewRound());
    }
    if (taskMapper.markReviewApproved(
        task.getId(), task.getTaskVersion(), task.getReviewRound(), actor.userId(), changedAt) != 1) {
      throw conflict("审核生效时任务状态已变化");
    }
  }

  private TechnicalDataReviewTaskResponse.Item response(
      QuoteTechReviewItem item, Integer versionNo) {
    return new TechnicalDataReviewTaskResponse.Item(
        item.getId(), item.getTaskId(), item.getReviewRound(), item.getProductId(),
        item.getSubmittedVersionId(), versionNo, item.getModuleType(), item.getDecision(),
        item.getDecisionReason(), item.getInheritedFromReviewItemId(),
        item.getDifferenceSnapshotJson(), item.getValidationSnapshotJson(),
        item.getDecidedBy(), item.getDecidedByName(), item.getDecidedAt(), item.getRowVersion());
  }

  private int count(List<TechnicalDataReviewTaskResponse.Item> items, String decision) {
    return (int) items.stream().filter(item -> decision.equals(item.decision())).count();
  }

  private TechnicalDataTaskSummaryResponse summary(QuoteTechTask task) {
    return new TechnicalDataTaskSummaryResponse(
        task.getId(), task.getTaskNo(), task.getOaNo(), task.getAccountingMonth(),
        task.getBusinessUnitType(), task.getApplicableOrgCode(), task.getAssigneeUserId(),
        task.getAssigneeName(), task.getReviewerUserId(), task.getReviewerName(),
        task.getTaskStatus(), task.getReviewStatus(), task.getReviewRound(),
        task.getExternalTaskStatus(), task.getDueAt(), task.getUpdatedAt());
  }

  private QuoteTechTask requireTask(Long taskId) {
    return repository.findTask(positive(taskId, "taskId"))
        .orElseThrow(() -> notFound("技术资料审核任务不存在"));
  }

  private void requireReviewAccess(QuoteTechTask task, TechnicalDataActor actor) {
    if (!actor.canAccessTask(task.getId())) {
      throw forbidden("短时访问会话不允许跨任务审核");
    }
    if (!Integer.valueOf(1).equals(task.getActiveFlag()) && !actor.admin()) {
      throw forbidden("历史审核任务仅管理员可查看");
    }
    if (!actor.admin() && !Objects.equals(task.getReviewerUserId(), actor.userId())) {
      throw forbidden("只能查看或处理分配给本人的补录审核");
    }
  }

  private void requireReviewer(TechnicalDataActor actor, boolean decide) {
    if (actor == null || actor.userId() == null || actor.userId() <= 0) {
      throw forbidden("当前登录用户无效");
    }
    if (decide
        ? !actor.hasDirect("technical:data:review:decide")
        : !actor.reviewer()) {
      throw forbidden(decide ? "当前用户无权处理补录审核" : "当前用户无权查看补录审核");
    }
  }

  private String decision(String value) {
    String decision = value == null ? "" : value.trim().toUpperCase();
    if (!Set.of("PASSED", "RETURNED").contains(decision)) throw invalid("审核决定无效");
    return decision;
  }

  private String reason(String value, boolean required) {
    String result = value == null ? null : value.trim();
    if (required && !StringUtils.hasText(result)) throw invalid("退回或管理员代操作必须填写原因");
    if (result != null && result.length() > 500) throw invalid("审核原因长度不能超过500");
    return StringUtils.hasText(result) ? result : null;
  }

  private String normalizeStatus(String value) {
    if (!StringUtils.hasText(value)) return null;
    String status = value.trim().toUpperCase();
    if (!FILTER_STATUSES.contains(status)) throw invalid("taskStatus无效");
    return status;
  }

  private String normalizeMonth(String value) {
    if (!StringUtils.hasText(value)) return null;
    try {
      return YearMonth.parse(value.trim()).toString();
    } catch (DateTimeParseException exception) {
      throw invalid("accountingMonth必须为YYYY-MM");
    }
  }

  private int expected(Integer value, String field) {
    if (value == null || value < 0) throw invalid(field + "必须大于等于0");
    return value;
  }

  private Long positive(Long value, String field) {
    if (value == null || value <= 0) throw invalid(field + "必须大于0");
    return value;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
  }

  private TechnicalDataTaskException invalid(String message) {
    return error(TechnicalDataTaskErrorCode.INVALID_REQUEST, message);
  }

  private TechnicalDataTaskException forbidden(String message) {
    return error(TechnicalDataTaskErrorCode.FORBIDDEN, message);
  }

  private TechnicalDataTaskException conflict(String message) {
    return error(TechnicalDataTaskErrorCode.VERSION_CONFLICT, message);
  }

  private TechnicalDataTaskException notFound(String message) {
    return error(TechnicalDataTaskErrorCode.TASK_NOT_FOUND, message);
  }

  private TechnicalDataTaskException error(TechnicalDataTaskErrorCode code, String message) {
    return new TechnicalDataTaskException(code, message);
  }
}
