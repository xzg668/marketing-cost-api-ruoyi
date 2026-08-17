package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.dto.collaboration.TechnicalTaskChangeLogResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalTaskDetailResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalTaskListResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalTaskValidationResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalTaskValidationResponse.Issue;
import com.sanhua.marketingcost.entity.IntegrationOutbox;
import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCollaborationReviewItem;
import com.sanhua.marketingcost.mapper.IntegrationOutboxMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationReviewItemMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductTaskStatus;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ValidationStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 技术本人任务的唯一应用入口；任何范围都不接受前端传值。 */
@Service
public class TechnicalTaskApplicationService {
  private static final EnumSet<ProductTaskStatus> EDITABLE = EnumSet.of(
      ProductTaskStatus.WAIT_TECH,
      ProductTaskStatus.BOM_IN_PROGRESS,
      ProductTaskStatus.PACKAGE_IN_PROGRESS,
      ProductTaskStatus.PRICE_IN_PROGRESS,
      ProductTaskStatus.TECH_VALIDATION_FAILED,
      ProductTaskStatus.RETURNED_TO_TECH);
  private static final EnumSet<ProductTaskStatus> VALIDATABLE = EnumSet.of(
      ProductTaskStatus.BOM_IN_PROGRESS,
      ProductTaskStatus.PACKAGE_IN_PROGRESS,
      ProductTaskStatus.PRICE_IN_PROGRESS,
      ProductTaskStatus.RETURNED_TO_TECH);

  private final QuoteCollaborationTaskRepository repository;
  private final CollaborationCurrentPrincipalProvider principalProvider;
  private final CollaborationNextActionCalculator nextActionCalculator;
  private final CollaborationProductStateService stateService;
  private final TechnicalTaskValidator validator;
  private final TechnicalSubmissionCoordinator submissionCoordinator;
  private final IntegrationOutboxMapper outboxMapper;
  private final QuoteCollaborationReviewItemMapper reviewItemMapper;

  public TechnicalTaskApplicationService(
      QuoteCollaborationTaskRepository repository,
      CollaborationCurrentPrincipalProvider principalProvider,
      CollaborationNextActionCalculator nextActionCalculator,
      CollaborationProductStateService stateService,
      TechnicalTaskValidator validator,
      TechnicalSubmissionCoordinator submissionCoordinator,
      IntegrationOutboxMapper outboxMapper,
      QuoteCollaborationReviewItemMapper reviewItemMapper) {
    this.repository = repository;
    this.principalProvider = principalProvider;
    this.nextActionCalculator = nextActionCalculator;
    this.stateService = stateService;
    this.validator = validator;
    this.submissionCoordinator = submissionCoordinator;
    this.outboxMapper = outboxMapper;
    this.reviewItemMapper = reviewItemMapper;
  }

  @Transactional(readOnly = true)
  public TechnicalTaskListResponse mine() {
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    String businessUnit = currentBusinessUnit();
    List<TechnicalTaskListResponse.Item> items = repository
        .findMineByTechnician(principal.userId(), businessUnit).stream()
        .map(task -> listItem(task, principal))
        .toList();
    return new TechnicalTaskListResponse(items.size(), items);
  }

  @Transactional(readOnly = true)
  public TechnicalTaskDetailResponse detail(Long taskId) {
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    return detail(ownTask(taskId, principal), principal);
  }

  @Transactional
  public TechnicalTaskDetailResponse start(Long taskId, Integer expectedVersion) {
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    QuoteCollaborationProductTask task = ownTask(taskId, principal);
    ProductTaskStatus status = status(task);
    requireVersion(task, expectedVersion);
    if (status == ProductTaskStatus.WAIT_TECH) {
      ProductAction action = switch (nextActionCalculator.calculate(task, principal)) {
        case SUPPLEMENT_BOM -> ProductAction.START_BOM;
        case SUPPLEMENT_PACKAGE -> ProductAction.START_PACKAGE;
        case SUPPLEMENT_PRICE -> ProductAction.START_PRICE;
        default -> throw invalid("当前任务没有可开始的补录内容");
      };
      task = stateService.transition(task.getId(), task.getTaskVersion(), scope(task), action, principal)
          .task();
    } else if (status == ProductTaskStatus.TECH_VALIDATION_FAILED) {
      ProductAction action = retryAction(task, validator.validate(task, gaps(task)));
      task = stateService.transition(task.getId(), task.getTaskVersion(), scope(task), action, principal)
          .task();
    } else if (!VALIDATABLE.contains(status)) {
      throw invalid("当前任务已提交或已结束，不能再次开始处理");
    }
    return detail(task, principal);
  }

  @Transactional
  public TechnicalTaskValidationResponse validate(Long taskId, Integer expectedVersion) {
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    QuoteCollaborationProductTask task = ownTask(taskId, principal);
    requireVersion(task, expectedVersion);
    ProductTaskStatus status = status(task);
    if (!VALIDATABLE.contains(status)) {
      throw invalid("请先开始任务，再执行完整性校验");
    }
    List<Issue> issues = validator.validate(task, gaps(task));
    ValidationStatus validationStatus = issues.isEmpty()
        ? ValidationStatus.PASSED : ValidationStatus.FAILED;
    task = repository.updateValidationResult(task.getId(), task.getTaskVersion(),
        validationStatus.code(), principal.userId(), scope(task), principal.actor());
    if (!issues.isEmpty()) {
      task = stateService.transition(task.getId(), task.getTaskVersion(), scope(task),
          ProductAction.FAIL_TECH_VALIDATION, principal).task();
    }
    return new TechnicalTaskValidationResponse(issues.isEmpty(),
        issues.isEmpty() ? "校验通过，可以提交财务审核" : "校验未通过，请按提示修改后重新校验",
        issues, detail(task, principal));
  }

  @Transactional
  public TechnicalTaskDetailResponse submit(Long taskId, Integer expectedVersion) {
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    QuoteCollaborationProductTask task = ownTask(taskId, principal);
    requireVersion(task, expectedVersion);
    ProductTaskStatus status = status(task);
    if (!VALIDATABLE.contains(status)) {
      throw invalid("当前任务状态不能提交");
    }
    if (!ValidationStatus.PASSED.code().equals(task.getLastValidationStatus())) {
      throw invalid("请先通过完整性校验，再提交财务审核");
    }
    List<Issue> currentIssues = validator.validate(task, gaps(task));
    if (!currentIssues.isEmpty()) {
      throw invalid("任务数据已变化，请重新校验后提交");
    }
    List<QuoteCollaborationGap> currentGaps = gaps(task);
    submissionCoordinator.lockCurrentPriceDrafts(task, currentGaps, principal);
    task = stateService.transition(task.getId(), task.getTaskVersion(), scope(task),
        ProductAction.SUBMIT_TECH, principal).task();
    task = submissionCoordinator.aggregateAfterSubmission(task, principal);
    return detail(task, principal);
  }

  @Transactional(readOnly = true)
  public TechnicalTaskChangeLogResponse changeLog(Long taskId) {
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    QuoteCollaborationProductTask task = ownTask(taskId, principal);
    List<TechnicalTaskChangeLogResponse.Entry> entries = outboxMapper
        .selectByAggregate("PRODUCT_TASK", task.getId()).stream()
        .map(this::changeLogEntry)
        .toList();
    return new TechnicalTaskChangeLogResponse(task.getId(), entries);
  }

  private TechnicalTaskDetailResponse detail(
      QuoteCollaborationProductTask task, CollaborationPrincipal principal) {
    List<QuoteCollaborationGap> gaps = gaps(task);
    List<TechnicalTaskDetailResponse.Requirement> requirements = requirements(task, gaps);
    int completed = (int) requirements.stream().filter(
        TechnicalTaskDetailResponse.Requirement::completed).count();
    CollaborationNextAction action = nextActionCalculator.calculate(task, principal);
    ProductTaskStatus status = status(task);
    boolean requiresValidation = requiresValidation(task, status, requirements);
    return new TechnicalTaskDetailResponse(task.getId(), task.getProductTaskNo(),
        task.getProductCode(), task.getProductName(), task.getProductSpec(), task.getProductModel(),
        task.getAccountingMonth(), task.getPrimaryScope(), scopeLabel(task),
        task.getTaskStatus(), statusLabel(status), task.getTaskVersion(), editable(task, principal),
        displayAction(action, requiresValidation), nextActionLabel(action, requiresValidation),
        completed, requirements.size(), requirements,
        gaps.stream().filter(gap -> !"OBSOLETE".equals(gap.getGapStatus()))
            .map(this::gap).toList(), quoteSource(task), task.getLastValidationStatus(),
        task.getLastValidationAt(),
        returnIssues(task, status),
        guidance(status, action, requiresValidation, requirements.size() - completed));
  }

  private List<TechnicalTaskDetailResponse.ReturnIssue> returnIssues(
      QuoteCollaborationProductTask task, ProductTaskStatus status) {
    if (status != ProductTaskStatus.RETURNED_TO_TECH) return List.of();
    return reviewItemMapper.selectLatestRejectedByProductTask(
        task.getId(), task.getBusinessUnitType()).stream()
        .map(item -> new TechnicalTaskDetailResponse.ReturnIssue(
            item.getItemType(), reviewItemTypeLabel(item), item.getDecisionReason()))
        .toList();
  }

  private static String reviewItemTypeLabel(QuoteCollaborationReviewItem item) {
    return switch (item == null || item.getItemType() == null ? "" : item.getItemType()) {
      case "BOM" -> "BOM";
      case "PACKAGE" -> "包装";
      case "PRICE_DRAFT" -> "底层价格";
      default -> "补录内容";
    };
  }

  private TechnicalTaskListResponse.Item listItem(
      QuoteCollaborationProductTask task, CollaborationPrincipal principal) {
    ProductTaskStatus status = status(task);
    CollaborationNextAction action = nextActionCalculator.calculate(task, principal);
    List<TechnicalTaskDetailResponse.Requirement> requirements = requirements(task, gaps(task));
    boolean requiresValidation = requiresValidation(task, status, requirements);
    return new TechnicalTaskListResponse.Item(task.getId(), task.getProductTaskNo(),
        task.getProductCode(), task.getProductName(), task.getProductSpec(), task.getProductModel(),
        task.getPrimaryScope(), scopeLabel(task), task.getTaskStatus(),
        statusLabel(status), value(task.getOpenGapCount()), displayAction(action, requiresValidation),
        nextActionLabel(action, requiresValidation),
        editable(task, principal), task.getTaskVersion(), task.getUpdatedAt());
  }

  private List<TechnicalTaskDetailResponse.Requirement> requirements(
      QuoteCollaborationProductTask task, List<QuoteCollaborationGap> gaps) {
    List<TechnicalTaskDetailResponse.Requirement> result = new ArrayList<>();
    if (enabled(task.getNeedBom())) {
      boolean draft = task.getSupplementVersionId() != null;
      boolean verified = StringUtils.hasText(task.getElectronicBomFingerprint());
      result.add(new TechnicalTaskDetailResponse.Requirement("BOM", "补齐并校验BOM", true,
          draft && verified, !draft ? "待补BOM" : verified ? "已从电子图库回取并校验" : "待回取电子图库BOM"));
    }
    if (enabled(task.getNeedPackage())) {
      boolean done = task.getPackageReferenceId() != null;
      result.add(new TechnicalTaskDetailResponse.Requirement("PACKAGE", "补齐裸品包装", true,
          done, done ? "包装方案已保存" : "待补包装"));
    }
    if (enabled(task.getNeedPrice())) {
      List<QuoteCollaborationGap> priceGaps = gaps.stream()
          .filter(gap -> "PRICE".equals(gap.getGapCategory()))
          .filter(gap -> !"OBSOLETE".equals(gap.getGapStatus())).toList();
      long open = priceGaps.stream().filter(gap -> !resolved(gap.getGapStatus())).count();
      boolean done = !priceGaps.isEmpty() && open == 0;
      String message = priceGaps.isEmpty() ? "待生成缺价明细"
          : done ? "全部缺价明细已处理" : open + "项明细待补价";
      result.add(new TechnicalTaskDetailResponse.Requirement("PRICE", "补齐底层物料价格", true,
          done, message));
    }
    return List.copyOf(result);
  }

  private TechnicalTaskDetailResponse.Gap gap(QuoteCollaborationGap gap) {
    return new TechnicalTaskDetailResponse.Gap(gap.getId(), gap.getGapCategory(),
        categoryLabel(gap.getGapCategory()), gap.getMaterialCode(), gap.getMaterialName(),
        gap.getMaterialSpec(), gap.getMaterialModel(), gap.getMaterialRole(),
        gap.getBomPath(), gap.getBomQuantity(), gap.getBomUnit(), gap.getAccountingMonth(),
        gap.getApplicableOrgCode(), gap.getSourceType(), gap.getSourceId(),
        gap.getReasonMessage(), gap.getGapStatus(), gapStatusLabel(gap.getGapStatus()));
  }

  private TechnicalTaskDetailResponse.QuoteSource quoteSource(
      QuoteCollaborationProductTask task) {
    return repository.findLinksByProductTask(task.getId(), scope(task)).stream()
        .filter(link -> "OWNER".equals(link.getLinkType()))
        .max(Comparator.comparing(QuoteCollaborationQuoteLink::getId))
        .map(link -> new TechnicalTaskDetailResponse.QuoteSource(
            link.getOaNo(), link.getOaFormItemId()))
        .orElse(null);
  }

  private QuoteCollaborationProductTask ownTask(
      Long taskId, CollaborationPrincipal principal) {
    if (taskId == null || taskId <= 0) throw notFound();
    return repository.findMineById(taskId, principal.userId(), currentBusinessUnit())
        .orElseThrow(TechnicalTaskApplicationService::notFound);
  }

  private List<QuoteCollaborationGap> gaps(QuoteCollaborationProductTask task) {
    return repository.findGaps(task.getId(), scope(task));
  }

  private static CollaborationScope scope(QuoteCollaborationProductTask task) {
    return new CollaborationScope(task.getBusinessUnitType(), task.getApplicableOrgCode());
  }

  private static ProductAction retryAction(
      QuoteCollaborationProductTask task, List<Issue> issues) {
    if (issues.stream().anyMatch(issue -> "BOM".equals(issue.category()))) {
      return ProductAction.RETRY_BOM;
    }
    if (issues.stream().anyMatch(issue -> "PACKAGE".equals(issue.category()))) {
      return ProductAction.RETRY_PACKAGE;
    }
    if (issues.stream().anyMatch(issue -> "PRICE".equals(issue.category()))) {
      return ProductAction.RETRY_PRICE;
    }
    if (enabled(task.getNeedBom())) return ProductAction.RETRY_BOM;
    if (enabled(task.getNeedPackage())) return ProductAction.RETRY_PACKAGE;
    return ProductAction.RETRY_PRICE;
  }

  private TechnicalTaskChangeLogResponse.Entry changeLogEntry(IntegrationOutbox event) {
    String eventType = event.getEventType();
    String title = switch (eventType == null ? "" : eventType) {
      case "TECH_TASK_CREATED" -> "任务已分配";
      case "TECH_TASK_UPDATED" -> "处理状态已更新";
      case "TECH_TASK_COMPLETED" -> "技术已提交";
      case "TECH_TASK_REOPENED" -> "任务已退回修改";
      default -> "任务状态更新";
    };
    String description = "SYSTEM".equals(event.getDestination())
        ? "报价系统已记录" : "OA未接入时仅在报价系统保存事件，不伪造推送成功";
    return new TechnicalTaskChangeLogResponse.Entry(
        event.getOccurredAt(), eventType, title, description);
  }

  private static boolean editable(
      QuoteCollaborationProductTask task, CollaborationPrincipal principal) {
    return EDITABLE.contains(status(task))
        && principal.has(CollaborationRole.TECHNICIAN)
        && Objects.equals(task.getCurrentAssigneeUserId(), principal.userId());
  }

  private static void requireVersion(
      QuoteCollaborationProductTask task, Integer expectedVersion) {
    if (expectedVersion == null || expectedVersion <= 0
        || !expectedVersion.equals(task.getTaskVersion())) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_VERSION_CONFLICT,
          "任务版本已变化，请刷新页面后重试");
    }
  }

  private static ProductTaskStatus status(QuoteCollaborationProductTask task) {
    try {
      return ProductTaskStatus.valueOf(task.getTaskStatus());
    } catch (RuntimeException exception) {
      throw invalid("未知技术任务状态：" + task.getTaskStatus());
    }
  }

  private static String currentBusinessUnit() {
    return CollaborationScope.requireBusinessUnit(
        BusinessUnitContext.getCurrentBusinessUnitType());
  }

  private static String scopeLabel(QuoteCollaborationProductTask task) {
    String scope = task == null ? null : task.getPrimaryScope();
    return switch (scope == null ? "" : scope) {
      case "FULL_BOM" -> enabled(task.getNeedPrice())
          ? "补完整BOM和底层价格" : "补完整BOM（完成后检查价格）";
      case "BARE_PACKAGE" -> enabled(task.getNeedPrice())
          ? "补包装和新增物料价格" : "补包装（完成后检查价格）";
      case "PRICE_ONLY" -> "补底层物料价格";
      default -> "技术协作";
    };
  }

  private static String statusLabel(ProductTaskStatus status) {
    return switch (status) {
      case WAIT_TECH -> "待开始";
      case BOM_IN_PROGRESS -> "补BOM中";
      case PACKAGE_IN_PROGRESS -> "补包装中";
      case PRICE_IN_PROGRESS -> "补价格中";
      case TECH_VALIDATION_FAILED -> "校验未通过";
      case TECH_SUBMITTED -> "技术已提交";
      case WAIT_FINANCE -> "待财务审核";
      case RETURNED_TO_TECH -> "财务退回修改";
      case APPROVED_PUBLISHING -> "审核通过，处理中";
      case PUBLISH_OR_REPRICE_FAILED -> "生效失败";
      case READY_FOR_COSTING -> "可发起核算";
      case COSTING -> "核算中";
      case COMPLETED -> "已完成";
      case CANCELLED -> "已取消";
    };
  }

  private static String displayAction(
      CollaborationNextAction action, boolean requiresValidation) {
    return requiresValidation ? "VALIDATE_COMPLETENESS" : action.name();
  }

  private static String nextActionLabel(
      CollaborationNextAction action, boolean requiresValidation) {
    if (requiresValidation) return "检查完整性";
    return switch (action) {
      case SUPPLEMENT_BOM -> "开始补BOM";
      case VERIFY_ELECTRONIC_BOM -> "回取并校验BOM";
      case SUPPLEMENT_PACKAGE -> "开始补包装";
      case SUPPLEMENT_PRICE -> "补明细价格";
      case FIX_VALIDATION_ERRORS -> "修改校验问题";
      case SUBMIT_FINANCE_REVIEW -> "提交财务审核";
      case WAIT_FINANCE -> "等待财务审核";
      case REVIEW_TECH_SUBMISSION -> "审核技术提交";
      case REVISE_RETURNED_ITEMS -> "修改退回项";
      case RETRY_PUBLISH_OR_REPRICE -> "重试生效";
      case START_COSTING -> "发起核算";
      case CONTINUE_COSTING -> "继续核算";
      case NONE -> "无待办操作";
    };
  }

  private static String guidance(
      ProductTaskStatus status, CollaborationNextAction action,
      boolean requiresValidation, int remaining) {
    if (status == ProductTaskStatus.TECH_SUBMITTED || status == ProductTaskStatus.WAIT_FINANCE) {
      return "你已完成本产品处理，当前内容只读。";
    }
    if (status == ProductTaskStatus.TECH_VALIDATION_FAILED) {
      return "校验未通过，点击继续处理后按问题提示修改。";
    }
    if (requiresValidation) {
      return "内容已齐，请先校验完整性，再提交财务审核。";
    }
    if (action == CollaborationNextAction.SUBMIT_FINANCE_REVIEW) {
      return "校验已通过，可以提交财务审核。";
    }
    return remaining > 0 ? "只处理下方未完成项，系统会按顺序引导。" : "当前没有待处理项。";
  }

  private static boolean requiresValidation(
      QuoteCollaborationProductTask task,
      ProductTaskStatus status,
      List<TechnicalTaskDetailResponse.Requirement> requirements) {
    return VALIDATABLE.contains(status)
        && requirements != null
        && !requirements.isEmpty()
        && requirements.stream().allMatch(TechnicalTaskDetailResponse.Requirement::completed)
        && !ValidationStatus.PASSED.code().equals(task.getLastValidationStatus());
  }

  private static String categoryLabel(String category) {
    return switch (category == null ? "" : category) {
      case "BOM" -> "BOM";
      case "PACKAGE" -> "包装";
      case "PRICE" -> "价格";
      default -> category;
    };
  }

  private static String gapStatusLabel(String status) {
    return switch (status == null ? "" : status) {
      case "OPEN" -> "待处理";
      case "DRAFT_READY" -> "草稿已保存";
      case "RESOLVED" -> "已完成";
      case "WAIVED" -> "无需处理";
      case "OBSOLETE" -> "已失效";
      default -> status;
    };
  }

  private static boolean enabled(Integer value) { return value != null && value == 1; }
  private static int value(Integer value) { return value == null ? 0 : value; }
  private static boolean resolved(String status) {
    return "DRAFT_READY".equals(status) || "RESOLVED".equals(status) || "WAIVED".equals(status);
  }

  private static CollaborationDomainException notFound() {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.TASK_NOT_FOUND,
        "技术任务不存在或不属于当前登录人");
  }

  private static CollaborationDomainException invalid(String message) {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.STATE_TRANSITION_INVALID, message);
  }
}
