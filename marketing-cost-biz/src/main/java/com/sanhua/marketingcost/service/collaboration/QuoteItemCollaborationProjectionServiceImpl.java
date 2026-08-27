package com.sanhua.marketingcost.service.collaboration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationHistoryResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationSummaryResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteItemCollaborationResponse;
import com.sanhua.marketingcost.entity.BusinessChangeLog;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationQuoteLinkMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanAction;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanErrorCode;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteItemCollaborationProjectionServiceImpl
    implements QuoteItemCollaborationProjectionService {
  private final OaFormMapper formMapper;
  private final OaFormItemMapper itemMapper;
  private final QuoteBomPreparationRecordMapper preparationRecordMapper;
  private final QuoteCollaborationScanService scanService;
  private final QuoteCollaborationTaskRepository repository;
  private final CollaborationTechnicianResolver technicianResolver;
  private final CollaborationTaskLogService taskLogService;
  private final QuoteCostingWorkspaceService workspaceService;
  private final QuoteCostRunVersionMapper costRunVersionMapper;
  private final QuoteCollaborationQuoteLinkMapper quoteLinkMapper;

  public QuoteItemCollaborationProjectionServiceImpl(
      OaFormMapper formMapper,
      OaFormItemMapper itemMapper,
      QuoteBomPreparationRecordMapper preparationRecordMapper,
      QuoteCollaborationScanService scanService,
      QuoteCollaborationTaskRepository repository,
      CollaborationTechnicianResolver technicianResolver,
      CollaborationTaskLogService taskLogService,
      QuoteCostingWorkspaceService workspaceService,
      QuoteCostRunVersionMapper costRunVersionMapper,
      QuoteCollaborationQuoteLinkMapper quoteLinkMapper) {
    this.formMapper = formMapper;
    this.itemMapper = itemMapper;
    this.preparationRecordMapper = preparationRecordMapper;
    this.scanService = scanService;
    this.repository = repository;
    this.technicianResolver = technicianResolver;
    this.taskLogService = taskLogService;
    this.workspaceService = workspaceService;
    this.costRunVersionMapper = costRunVersionMapper;
    this.quoteLinkMapper = quoteLinkMapper;
  }

  @Override
  @Cacheable(
      value = "quoteCollaborationSummaries",
      key = "T(com.sanhua.marketingcost.service.collaboration.QuoteItemCollaborationProjectionServiceImpl).summaryCacheKey(#oaNo)",
      sync = true)
  @Transactional(readOnly = true)
  public QuoteCollaborationSummaryResponse summary(String oaNo) {
    return buildStoredSummary(oaNo);
  }

  @Override
  @CachePut(
      value = "quoteCollaborationSummaries",
      key = "T(com.sanhua.marketingcost.service.collaboration.QuoteItemCollaborationProjectionServiceImpl).summaryCacheKey(#oaNo)")
  @Transactional(readOnly = true)
  public QuoteCollaborationSummaryResponse refreshSummary(String oaNo) {
    return buildLiveSummary(oaNo);
  }

  private QuoteCollaborationSummaryResponse buildLiveSummary(String oaNo) {
    OaForm form = requireForm(oaNo);
    List<OaFormItem> quoteItems = itemMapper.selectList(
        Wrappers.<OaFormItem>lambdaQuery().eq(OaFormItem::getOaFormId, form.getId())
            .orderByAsc(OaFormItem::getSeq).orderByAsc(OaFormItem::getId));
    if (quoteItems.isEmpty()) {
      return new QuoteCollaborationSummaryResponse(form.getOaNo(), sha256(""), List.of());
    }

    QuoteCollaborationScanResult firstScan = scanService.scanQuoteItem(quoteItems.get(0).getId());
    List<Long> itemIds = quoteItems.stream().map(OaFormItem::getId).toList();
    List<QuoteCostingWorkspace> storedWorkspaces = workspaceService
        .findAll(itemIds, firstScan.accountingMonth());
    Map<Long, QuoteCostingWorkspace> workspaces = (storedWorkspaces == null
        ? List.<QuoteCostingWorkspace>of() : storedWorkspaces)
        .stream().collect(Collectors.toMap(
            QuoteCostingWorkspace::getOaFormItemId, Function.identity(), (left, right) -> right));
    List<QuoteCollaborationQuoteLink> activeLinks = quoteLinkMapper.selectList(
        Wrappers.<QuoteCollaborationQuoteLink>lambdaQuery()
            .in(QuoteCollaborationQuoteLink::getOaFormItemId, itemIds)
            .eq(QuoteCollaborationQuoteLink::getActiveFlag, 1));
    Set<Long> linkedItemIds = (activeLinks == null ? List.<QuoteCollaborationQuoteLink>of() : activeLinks)
        .stream().map(QuoteCollaborationQuoteLink::getOaFormItemId).collect(Collectors.toSet());

    List<QuoteItemCollaborationResponse> items = quoteItems.stream().map(item -> {
      QuoteCostingWorkspace workspace = workspaces.get(item.getId());
      if (Objects.equals(item.getId(), firstScan.oaFormItemId())) {
        return projectItem(form, item, firstScan, workspace);
      }
      if (isStoredFinanceProjection(workspace) && !linkedItemIds.contains(item.getId())) {
        return projectStoredFinanceGap(item, workspace);
      }
      return projectItem(form, item);
    }).toList();
    String version = sha256(items.stream().map(QuoteItemCollaborationResponse::projectionVersion)
        .reduce("", (left, right) -> left + "|" + right));
    return new QuoteCollaborationSummaryResponse(form.getOaNo(), version, items);
  }

  /** GET projection: reads persisted state only and never triggers U9/CMS/price scans. */
  private QuoteCollaborationSummaryResponse buildStoredSummary(String oaNo) {
    OaForm form = requireForm(oaNo);
    List<OaFormItem> quoteItems = itemMapper.selectList(
        Wrappers.<OaFormItem>lambdaQuery().eq(OaFormItem::getOaFormId, form.getId())
            .orderByAsc(OaFormItem::getSeq).orderByAsc(OaFormItem::getId));
    if (quoteItems.isEmpty()) {
      return new QuoteCollaborationSummaryResponse(form.getOaNo(), sha256(""), List.of());
    }
    String month = CostPricingPeriodUtils.currentPricingMonth();
    List<Long> itemIds = quoteItems.stream().map(OaFormItem::getId).toList();
    List<QuoteCostingWorkspace> stored = workspaceService.findAll(itemIds, month);
    Map<Long, QuoteCostingWorkspace> workspaces =
        (stored == null ? List.<QuoteCostingWorkspace>of() : stored).stream()
            .collect(Collectors.toMap(
                QuoteCostingWorkspace::getOaFormItemId,
                Function.identity(),
                (left, right) -> right));
    List<QuoteCollaborationQuoteLink> links = quoteLinkMapper.selectList(
        Wrappers.<QuoteCollaborationQuoteLink>lambdaQuery()
            .in(QuoteCollaborationQuoteLink::getOaFormItemId, itemIds)
            .eq(QuoteCollaborationQuoteLink::getActiveFlag, 1));
    Map<Long, QuoteCollaborationQuoteLink> linksByItem =
        (links == null ? List.<QuoteCollaborationQuoteLink>of() : links).stream()
            .collect(Collectors.toMap(
                QuoteCollaborationQuoteLink::getOaFormItemId,
                Function.identity(),
                (left, right) -> right));
    List<QuoteItemCollaborationResponse> items = quoteItems.stream()
        .map(item -> projectStoredItem(form, item, workspaces.get(item.getId()), linksByItem.get(item.getId())))
        .toList();
    String version = sha256(items.stream()
        .map(QuoteItemCollaborationResponse::projectionVersion)
        .reduce("", (left, right) -> left + "|" + right));
    return new QuoteCollaborationSummaryResponse(form.getOaNo(), version, items);
  }

  public static String summaryCacheKey(String oaNo) {
    String businessUnit = BusinessUnitContext.getCurrentBusinessUnitType();
    return value(businessUnit) + ":" + value(oaNo).trim();
  }

  @Override
  @Transactional(readOnly = true)
  public QuoteItemCollaborationResponse project(String oaNo, Long itemId) {
    OaForm form = requireForm(oaNo);
    return projectItem(form, requireItem(form, itemId));
  }

  @Override
  @Transactional(readOnly = true)
  public QuoteCollaborationHistoryResponse history(String oaNo, Long itemId) {
    OaForm form = requireForm(oaNo);
    OaFormItem item = requireItem(form, itemId);
    QuoteCostingWorkspace workspace = workspaceService
        .find(itemId, CostPricingPeriodUtils.currentPricingMonth()).orElse(null);
    QuoteCollaborationQuoteLink link = quoteLinkMapper.selectOne(
        Wrappers.<QuoteCollaborationQuoteLink>lambdaQuery()
            .eq(QuoteCollaborationQuoteLink::getOaFormItemId, itemId)
            .eq(QuoteCollaborationQuoteLink::getActiveFlag, 1)
            .orderByDesc(QuoteCollaborationQuoteLink::getId)
            .last("LIMIT 1"));
    QuoteItemCollaborationResponse projection = projectStoredItem(form, item, workspace, link);
    if (projection.productTaskId() == null) {
      return new QuoteCollaborationHistoryResponse(itemId, null, null,
          projection.currentStatus(), projection.currentStatusLabel(), projection.assigneeName(), List.of());
    }
    List<QuoteCollaborationHistoryResponse.Entry> entries = taskLogService
        .findByProductTask(projection.productTaskId()).stream()
        .map(this::historyEntry).toList();
    return new QuoteCollaborationHistoryResponse(itemId, projection.productTaskId(),
        projection.productTaskNo(), projection.currentStatus(), projection.currentStatusLabel(),
        projection.assigneeName(), entries);
  }

  private QuoteItemCollaborationResponse projectStoredItem(
      OaForm form,
      OaFormItem item,
      QuoteCostingWorkspace workspace,
      QuoteCollaborationQuoteLink link) {
    QuoteCollaborationProductTask task = link == null || link.getProductTaskId() == null
        ? null
        : repository.findProductTaskByIdAndBusinessUnit(
            link.getProductTaskId(), form.getBusinessUnitType()).orElse(null);
    ProjectionFields fields = storedFields(item, workspace, task);
    String version = sha256(String.join("|",
        String.valueOf(item.getId()),
        value(workspace == null ? null : workspace.getWorkspaceStatus()),
        value(workspace == null ? null : workspace.getInputFingerprint()),
        value(workspace == null ? null : workspace.getLastSuccessInputFingerprint()),
        value(workspace == null ? null : workspace.getSourceRevision()),
        value(workspace == null ? null : workspace.getLastSuccessSourceRevision()),
        value(workspace == null ? null : workspace.getUpdatedAt()),
        value(task == null ? null : task.getId()),
        value(task == null ? null : task.getTaskVersion()),
        value(link == null ? null : link.getId()),
        value(link == null ? null : link.getLinkStatus()),
        value(item.getCalcStatus()),
        value(item.getConfirmedCostVersionId())));
    Integer gapCount = task != null && task.getOpenGapCount() != null
        ? task.getOpenGapCount()
        : workspace == null || workspace.getGapCount() == null ? 0 : workspace.getGapCount();
    return response(item, fields, task, link, gapCount, version);
  }

  private ProjectionFields storedFields(
      OaFormItem item,
      QuoteCostingWorkspace workspace,
      QuoteCollaborationProductTask task) {
    if (task != null) {
      String status = task.getTaskStatus();
      QuoteItemCollaborationAction action = switch (status) {
        case "READY_FOR_COSTING" -> QuoteItemCollaborationAction.START_COSTING;
        case "COSTING" -> QuoteItemCollaborationAction.VIEW_COSTING_PROGRESS;
        case "COMPLETED" -> QuoteItemCollaborationAction.VIEW_COSTING_RESULT;
        case "CANCELLED" -> QuoteItemCollaborationAction.NONE;
        default -> QuoteItemCollaborationAction.VIEW_SUPPLEMENT;
      };
      return new ProjectionFields(
          "STORED", "已保存的BOM状态", "STORED", "已保存的价格状态",
          task.getCurrentAssigneeUserId(), task.getCurrentAssigneeName(), status,
          storedTaskLabel(status), action, "展示最近保存的协作状态；重新检查会刷新外部数据");
    }
    if (workspace != null) {
      String status = value(workspace.getWorkspaceStatus()).toUpperCase(Locale.ROOT);
      if ("SUCCESS".equals(status) && isCalculated(item)) {
        return new ProjectionFields(
            "AVAILABLE", "BOM已准备", "READY", "价格齐全", null, null,
            "COMPLETED", "核算完成", QuoteItemCollaborationAction.VIEW_COSTING_RESULT,
            "当前成功结果可直接查看");
      }
      QuoteItemCollaborationAction action = switch (status) {
        case "QUEUED", "RUNNING" -> QuoteItemCollaborationAction.VIEW_COSTING_PROGRESS;
        case "SYSTEM_FAILED" -> QuoteItemCollaborationAction.RETRY_COSTING;
        case "WAIT_BOM", "WAIT_PRICE_TYPE", "WAIT_PRICE" ->
            QuoteItemCollaborationAction.VIEW_COSTING_GAP;
        case "BOM_READY", "READY" -> QuoteItemCollaborationAction.START_COSTING;
        default -> QuoteItemCollaborationAction.NONE;
      };
      return new ProjectionFields(
          "STORED", "已保存的BOM状态", "STORED", "已保存的价格状态", null, null,
          status, storedWorkspaceLabel(status), action,
          gapMessage(workspace, "展示最近保存的核算状态；重新检查会刷新外部数据"));
    }
    return new ProjectionFields(
        "UNCHECKED", "待检查", "UNCHECKED", "待检查", null, null,
        "UNCHECKED", "待检查", QuoteItemCollaborationAction.NONE,
        "尚无保存的检查结果，请使用“重新检查”显式刷新");
  }

  private String storedTaskLabel(String status) {
    if ("WAIT_TECH".equals(status)) return "待技术处理";
    if ("WAIT_FINANCE".equals(status) || "TECH_SUBMITTED".equals(status)) return "待财务审核";
    if ("READY_FOR_COSTING".equals(status)) return "已就绪";
    if ("COSTING".equals(status)) return "核算中";
    if ("COMPLETED".equals(status)) return "核算完成";
    if ("CANCELLED".equals(status)) return "已取消";
    return StringUtils.hasText(status) ? status : "待处理";
  }

  private String storedWorkspaceLabel(String status) {
    return switch (status) {
      case "QUEUED" -> "排队中";
      case "RUNNING" -> "核算中";
      case "WAIT_BOM" -> "待补BOM";
      case "WAIT_PRICE_TYPE" -> "缺价格类型";
      case "WAIT_PRICE" -> "缺价格";
      case "SYSTEM_FAILED" -> "系统处理失败";
      case "BOM_READY", "READY" -> "已就绪";
      default -> StringUtils.hasText(status) ? status : "待检查";
    };
  }

  private QuoteCollaborationHistoryResponse.Entry historyEntry(BusinessChangeLog event) {
    return new QuoteCollaborationHistoryResponse.Entry(event.getChangedAt(), event.getFieldName(),
        eventTitle(event.getFieldName()), eventDescription(event));
  }

  private static String eventTitle(String eventType) {
    if ("TECH_TASK_CREATED".equals(eventType)) return "已发起技术补录";
    if ("TECH_TASK_LINKED".equals(eventType)) return "当前报价已关联原任务";
    if ("TECH_TASK_UPDATED".equals(eventType)) return "技术补录状态已更新";
    if ("FINANCE_REVIEW_CREATED".equals(eventType)) return "已提交财务审核";
    if ("TECH_TASK_RETURNED".equals(eventType)) return "财务已驳回技术修改";
    return StringUtils.hasText(eventType) ? eventType : "状态更新";
  }

  private static String eventDescription(BusinessChangeLog event) {
    return StringUtils.hasText(event.getChangeReason())
        ? event.getChangeReason() : "报价系统已记录协作状态变化";
  }

  private QuoteItemCollaborationResponse projectItem(OaForm form, OaFormItem item) {
    QuoteCollaborationScanResult scan = scanService.scanQuoteItem(item.getId());
    QuoteCostingWorkspace workspace =
        workspaceService.find(item.getId(), scan.accountingMonth()).orElse(null);
    return projectItem(form, item, scan, workspace);
  }

  private QuoteItemCollaborationResponse projectItem(
      OaForm form,
      OaFormItem item,
      QuoteCollaborationScanResult scan,
      QuoteCostingWorkspace workspace) {
    CollaborationScope scope = new CollaborationScope(scan.businessUnitType(), scan.priceOrgCode());
    QuoteCollaborationQuoteLink link = repository.findActiveLinksByQuoteItem(item.getId(), scope)
        .stream().findFirst().orElse(null);
    QuoteCollaborationProductTask task = task(scan, link, scope);
    QuoteBomPreparationRecord preparation =
        currentPreparation(item.getId(), scan.productCode(), scan.accountingMonth());
    QuoteCostRunVersion currentVersion = currentVersion(item);
    ProjectionFields fields =
        fields(form, item, scan, task, link, preparation, workspace, currentVersion);
    String version = sha256(String.join("|", String.valueOf(item.getId()),
        value(scan.status()), value(scan.action()), value(scan.requiredScope()),
        value(task == null ? null : task.getId()), value(task == null ? null : task.getTaskVersion()),
        value(link == null ? null : link.getId()), value(link == null ? null : link.getLinkStatus()),
        value(preparation == null ? null : preparation.getId()),
        value(preparation == null ? null : preparation.getUpdatedAt()),
        value(workspace == null ? null : workspace.getWorkspaceStatus()),
        value(workspace == null ? null : workspace.getInputFingerprint()),
        value(workspace == null ? null : workspace.getLastSuccessInputFingerprint()),
        value(currentVersion == null ? null : currentVersion.getPricingMonth()),
        value(item.getCalcStatus()), value(item.getConfirmedCostVersionId())));
    return response(item, fields, task, link,
        task != null && task.getOpenGapCount() != null
            ? task.getOpenGapCount() : scan.price().gapCount(), version);
  }

  private QuoteItemCollaborationResponse projectStoredFinanceGap(
      OaFormItem item, QuoteCostingWorkspace workspace) {
    ProjectionFields fields = new ProjectionFields(
        "AVAILABLE", "BOM已准备", "MISSING", "缺价格类型", null, "财务报价",
        "MISSING_PRICE", "缺价格类型", QuoteItemCollaborationAction.VIEW_COSTING_GAP,
        gapMessage(workspace, "当前产品存在价格类型缺口"));
    String version = sha256(String.join("|", String.valueOf(item.getId()),
        value(workspace.getWorkspaceStatus()), value(workspace.getInputFingerprint()),
        value(workspace.getLastSuccessInputFingerprint()), value(workspace.getLockVersion()),
        value(workspace.getUpdatedAt())));
    return response(item, fields, null, null, workspace.getGapCount(), version);
  }

  private static boolean isStoredFinanceProjection(QuoteCostingWorkspace workspace) {
    return workspace != null
        && "WAIT_PRICE_TYPE".equalsIgnoreCase(workspace.getWorkspaceStatus());
  }

  private QuoteItemCollaborationResponse response(
      OaFormItem item,
      ProjectionFields fields,
      QuoteCollaborationProductTask task,
      QuoteCollaborationQuoteLink link,
      Integer gapCount,
      String version) {
    return new QuoteItemCollaborationResponse(item.getId(), fields.bomCode, fields.bomLabel,
        fields.priceCode, fields.priceLabel,
        gapCount,
        fields.assigneeId,
        fields.assigneeName, fields.statusCode, fields.statusLabel,
        task == null ? null : task.getId(), task == null ? null : task.getProductTaskNo(),
        link == null ? null : link.getId(), task == null ? null : task.getTaskVersion(),
        fields.action.name(), actionLabel(fields.action), fields.action != QuoteItemCollaborationAction.NONE,
        fields.action.batchSelectable(), version, fields.message);
  }

  private QuoteCollaborationProductTask task(
      QuoteCollaborationScanResult scan, QuoteCollaborationQuoteLink link, CollaborationScope scope) {
    Long id = link != null ? link.getProductTaskId() : scan.activeProductTaskId();
    return id == null ? null : repository.findProductTaskById(id, scope).orElse(null);
  }

  private ProjectionFields fields(OaForm form, OaFormItem item, QuoteCollaborationScanResult scan,
      QuoteCollaborationProductTask task, QuoteCollaborationQuoteLink link,
      QuoteBomPreparationRecord preparation, QuoteCostingWorkspace workspace,
      QuoteCostRunVersion currentVersion) {
    if (inputChanged(workspace) || periodChanged(scan, workspace, currentVersion)) {
      String message = periodChanged(scan, workspace, currentVersion)
          ? "核算月份已由 " + currentVersion.getPricingMonth() + " 变为 "
              + scan.accountingMonth() + "；原核算结果仍可查看，重新核算成功后才替换当前结果"
          : "规则、替代料、包装或价格来源已变化；原核算结果仍可查看，重新核算成功后才替换当前结果";
      return new ProjectionFields(
        bomCode(scan), bomLabel(scan), "STALE", "核算输入已变化",
        task == null ? null : task.getCurrentAssigneeUserId(),
        task == null ? null : task.getCurrentAssigneeName(),
        "STALE", "待重新核算", QuoteItemCollaborationAction.RESTART_COSTING,
        message);
    }
    ProjectionFields workspaceFields = workspaceFields(scan, workspace);
    if (workspaceFields != null && "COSTING".equals(workspaceFields.statusCode)) {
      return workspaceFields;
    }
    if (link != null && "READY".equals(link.getLinkStatus())) return ready(scan, "补录审核通过，已具备核算条件");
    if (task != null && link != null) return taskFields(scan, task);
    if (workspaceFields != null && !requiresTechnicalCollaboration(workspace, scan)) {
      return workspaceFields;
    }
    if (isCalculated(item)) return new ProjectionFields(bomCode(scan), bomLabel(scan), "READY", "价格齐全",
        task == null ? null : task.getCurrentAssigneeUserId(), task == null ? null : task.getCurrentAssigneeName(),
        "COMPLETED", "核算完成", QuoteItemCollaborationAction.VIEW_COSTING_RESULT,
        "当前成功结果可直接查看；只有输入变化后才需要重新核算");
    if (preparation != null && scan.requiredScope() == PrimaryScope.PRICE_ONLY)
      return new ProjectionFields(bomCode(scan), bomLabel(scan), "PENDING",
        "核算资料已准备", null, null, "READY_FOR_COSTING", "可核算",
        QuoteItemCollaborationAction.START_COSTING, "已存在当前核算准备，可直接核算本产品");
    if (scan.action() == QuoteCollaborationScanAction.NO_COLLABORATION_REQUIRED) return ready(scan, scan.message());
    if (scan.action() == QuoteCollaborationScanAction.REUSE_APPROVED_RESULT)
      return action(scan, null, "可复用", "已有半年有效的审核结果", QuoteItemCollaborationAction.APPLY_APPROVED_RESULT, scan.message());
    if (scan.action() == QuoteCollaborationScanAction.LINK_ACTIVE_TASK)
      return action(scan, null, "他人处理中", "同月同产品已有补录任务", QuoteItemCollaborationAction.LINK_EXISTING_TASK, scan.message());
    if (scan.action() == QuoteCollaborationScanAction.MAINTAIN_PRICE_TYPE)
      return new ProjectionFields(
          bomCode(scan), bomLabel(scan), "MISSING", "缺价格类型", null, "财务报价",
          "MISSING_PRICE_TYPE", "缺价格类型", QuoteItemCollaborationAction.VIEW_COSTING_GAP,
          valueOr(scan.message(), "请财务在物料价格类型中导入或维护后重新核算本产品"));
    if (scan.action() == QuoteCollaborationScanAction.SYSTEM_BLOCKED
        && scan.errorCode() == QuoteCollaborationScanErrorCode.PRICE_PREPARATION_ERROR)
      return action(scan, null, "PRICE_PREPARATION_REQUIRED", "价格待处理",
          QuoteItemCollaborationAction.RETRY_COSTING,
          valueOr(scan.message(), "价格准备检查失败")
              + "；重试时仅重新处理当前产品和当前核算月");
    if (scan.action() == QuoteCollaborationScanAction.SYSTEM_BLOCKED)
      return action(scan, null, "SYSTEM_FAILED", "系统检查未通过", QuoteItemCollaborationAction.RETRY_COSTING, scan.message());
    CollaborationTechnicianResolver.Resolution resolution = technicianResolver.resolve(
        form, item, scan.businessUnitType(), null);
    if (!resolution.resolved())
      return action(scan, null, "TECHNICIAN_UNASSIGNED", "待指定负责人",
          QuoteItemCollaborationAction.ASSIGN_TECHNICIAN, resolution.error());
    QuoteItemCollaborationAction action = scan.requiredScope() == PrimaryScope.FULL_BOM
        ? QuoteItemCollaborationAction.START_BOM_SUPPLEMENT
        : scan.requiredScope() == PrimaryScope.BARE_PACKAGE
            ? QuoteItemCollaborationAction.START_PACKAGE_SUPPLEMENT
            : QuoteItemCollaborationAction.START_PRICE_SUPPLEMENT;
    return action(scan, resolution, statusCode(scan), statusLabel(scan), action, scan.message());
  }

  private ProjectionFields taskFields(QuoteCollaborationScanResult scan, QuoteCollaborationProductTask task) {
    String status = task.getTaskStatus();
    QuoteItemCollaborationAction action = switch (status) {
      case "READY_FOR_COSTING" -> QuoteItemCollaborationAction.START_COSTING;
      case "COSTING" -> QuoteItemCollaborationAction.VIEW_COSTING_PROGRESS;
      default -> QuoteItemCollaborationAction.VIEW_SUPPLEMENT;
    };
    String label = switch (status) {
      case "WAIT_TECH" -> "待技术处理";
      case "BOM_IN_PROGRESS" -> "BOM补录中";
      case "PACKAGE_IN_PROGRESS" -> "包装补录中";
      case "PRICE_IN_PROGRESS" -> "价格补录中";
      case "TECH_VALIDATION_FAILED" -> "技术校验未通过";
      case "TECH_SUBMITTED", "WAIT_FINANCE" -> "待财务审核";
      case "RETURNED_TO_TECH" -> "财务已驳回";
      case "APPROVED_PUBLISHING" -> "审核通过，数据生效中";
      case "PUBLISH_OR_REPRICE_FAILED" -> "生效或重新取价失败";
      case "READY_FOR_COSTING" -> "已就绪";
      case "COSTING" -> "核算中";
      case "COMPLETED" -> "核算完成";
      case "CANCELLED" -> "已取消";
      default -> status;
    };
    if ("COMPLETED".equals(status)) action = QuoteItemCollaborationAction.VIEW_COSTING_RESULT;
    if ("CANCELLED".equals(status)) action = QuoteItemCollaborationAction.NONE;
    return new ProjectionFields(bomCode(scan), bomLabel(scan), scan.price().status().name(), priceLabel(scan),
        task.getCurrentAssigneeUserId(), task.getCurrentAssigneeName(), status, label, action,
        "当前由" + valueOr(task.getCurrentAssigneeName(), "原技术人员") + "处理");
  }

  private ProjectionFields ready(QuoteCollaborationScanResult scan, String message) {
    return new ProjectionFields(bomCode(scan), bomLabel(scan), "READY", "价格齐全", null, null,
        "READY_FOR_COSTING", "已就绪", QuoteItemCollaborationAction.START_COSTING, message);
  }

  private ProjectionFields workspaceFields(
      QuoteCollaborationScanResult scan, QuoteCostingWorkspace workspace) {
    if (workspace == null || !StringUtils.hasText(workspace.getWorkspaceStatus())) return null;
    return switch (workspace.getWorkspaceStatus().trim().toUpperCase(Locale.ROOT)) {
      case "QUEUED", "RUNNING" -> new ProjectionFields(
          bomCode(scan), bomLabel(scan), "PENDING", "核算处理中", null, null,
          "COSTING", "核算中", QuoteItemCollaborationAction.VIEW_COSTING_PROGRESS,
          "当前产品正在核算，可查看处理进度");
      case "WAIT_BOM" -> new ProjectionFields(
          bomCode(scan), bomLabel(scan), "PENDING_BOM", "待BOM补齐后检查", null, "待指定技术负责人",
          "MISSING_BOM", "待补BOM", QuoteItemCollaborationAction.VIEW_COSTING_GAP,
          gapMessage(workspace, "当前产品缺少可核算 BOM"));
      case "WAIT_PRICE_TYPE" -> new ProjectionFields(
          bomCode(scan), bomLabel(scan), "MISSING", "缺价格类型", null, "财务报价",
          "MISSING_PRICE", "缺价格类型", QuoteItemCollaborationAction.VIEW_COSTING_GAP,
          gapMessage(workspace, "当前产品存在价格类型缺口"));
      case "WAIT_PRICE" -> new ProjectionFields(
          bomCode(scan), bomLabel(scan), "MISSING", "缺价格", null, waitPriceAssignee(workspace),
          "MISSING_PRICE", "缺价格", QuoteItemCollaborationAction.VIEW_COSTING_GAP,
          gapMessage(workspace, "当前产品存在价格缺口"));
      case "SYSTEM_FAILED" -> new ProjectionFields(
          bomCode(scan), bomLabel(scan), "ERROR", "系统处理失败", null, null,
          "SYSTEM_FAILED", "系统处理失败", QuoteItemCollaborationAction.RETRY_COSTING,
          gapMessage(workspace, "系统处理失败，可重试当前产品"));
      case "BOM_READY", "READY" -> ready(scan, "当前资料已准备，可核算本产品");
      default -> null;
    };
  }

  private static String waitPriceAssignee(QuoteCostingWorkspace workspace) {
    if (workspace != null
        && "FINANCE_BASE_PRICE_MISSING".equalsIgnoreCase(workspace.getLastErrorCode())) {
      return "财务报价";
    }
    return "财务报价/产品技术";
  }

  /**
   * 核算工作区只记录阻塞结果，不负责决定协作动作。BOM 与底层物料缺价必须继续使用
   * 实时扫描结果解析负责人和唯一下一步；价格类型、财务基准价仍留给报价人员处理。
   */
  private static boolean requiresTechnicalCollaboration(
      QuoteCostingWorkspace workspace, QuoteCollaborationScanResult scan) {
    if (workspace == null || !StringUtils.hasText(workspace.getWorkspaceStatus())) return false;
    if (scan.action() != QuoteCollaborationScanAction.CREATE_COLLABORATION
        && scan.action() != QuoteCollaborationScanAction.LINK_ACTIVE_TASK
        && scan.action() != QuoteCollaborationScanAction.REUSE_APPROVED_RESULT) {
      return false;
    }
    String status = workspace.getWorkspaceStatus().trim().toUpperCase(Locale.ROOT);
    if ("WAIT_BOM".equals(status)) return true;
    return "WAIT_PRICE".equals(status)
        && !"FINANCE_BASE_PRICE_MISSING".equalsIgnoreCase(workspace.getLastErrorCode());
  }

  private static boolean inputChanged(QuoteCostingWorkspace workspace) {
    if (workspace == null) return false;
    if ("STALE".equalsIgnoreCase(workspace.getWorkspaceStatus())) return true;
    return StringUtils.hasText(workspace.getInputFingerprint())
        && StringUtils.hasText(workspace.getLastSuccessInputFingerprint())
        && !workspace.getInputFingerprint().equals(workspace.getLastSuccessInputFingerprint());
  }

  private static boolean periodChanged(
      QuoteCollaborationScanResult scan,
      QuoteCostingWorkspace workspace,
      QuoteCostRunVersion currentVersion) {
    return workspace == null
        && currentVersion != null
        && StringUtils.hasText(currentVersion.getPricingMonth())
        && StringUtils.hasText(scan.accountingMonth())
        && !currentVersion.getPricingMonth().trim().equals(scan.accountingMonth().trim());
  }

  private QuoteCostRunVersion currentVersion(OaFormItem item) {
    if (item.getConfirmedCostVersionId() == null) return null;
    return costRunVersionMapper.selectById(item.getConfirmedCostVersionId());
  }

  private static String gapMessage(QuoteCostingWorkspace workspace, String fallback) {
    if (workspace != null && StringUtils.hasText(workspace.getLastErrorMessage())) {
      return workspace.getLastErrorMessage();
    }
    return fallback;
  }

  private ProjectionFields action(QuoteCollaborationScanResult scan,
      CollaborationTechnicianResolver.Resolution person, String statusCode, String statusLabel,
      QuoteItemCollaborationAction action, String message) {
    return new ProjectionFields(bomCode(scan), bomLabel(scan), scan.price().status().name(), priceLabel(scan),
        person == null ? null : person.userId(),
        person == null ? scan.activeAssigneeName() : person.userName(),
        statusCode, statusLabel, action, message);
  }

  private static String statusCode(QuoteCollaborationScanResult scan) {
    if (scan.requiredScope() == PrimaryScope.FULL_BOM) return "MISSING_BOM";
    if (scan.requiredScope() == PrimaryScope.BARE_PACKAGE) return "MISSING_PACKAGE";
    return "MISSING_PRICE";
  }
  private static String statusLabel(QuoteCollaborationScanResult scan) {
    if (scan.requiredScope() == PrimaryScope.FULL_BOM) return "待补BOM";
    if (scan.requiredScope() == PrimaryScope.BARE_PACKAGE) return "待补包装";
    return "待补明细价格";
  }
  private static String bomCode(QuoteCollaborationScanResult scan) {
    if (scan.requiredScope() == PrimaryScope.FULL_BOM) return "NO_BOM";
    if (scan.requiredScope() == PrimaryScope.BARE_PACKAGE) return "BARE_PRODUCT";
    return "AVAILABLE";
  }
  private static String bomLabel(QuoteCollaborationScanResult scan) {
    if (scan.requiredScope() == PrimaryScope.FULL_BOM) return "无BOM";
    if (scan.requiredScope() == PrimaryScope.BARE_PACKAGE) return "U9本体BOM已有（裸品）";
    if ("ELECTRONIC_DRAWING".equals(scan.authoritativeBomSource())) return "电子图库BOM已校验";
    return "U9有此BOM";
  }
  private static String priceLabel(QuoteCollaborationScanResult scan) {
    return switch (scan.price().status()) {
      case READY -> "价格齐全";
      case GAPS -> scan.price().gapCount() + "项明细缺价";
      case PENDING_BOM -> "待BOM补齐后检查";
      case PENDING_PACKAGE -> "待包装补齐后检查";
      case ERROR -> "价格检查失败";
      default -> "待检查";
    };
  }
  private static String actionLabel(QuoteItemCollaborationAction action) {
    return switch (action) {
      case ASSIGN_TECHNICIAN -> "指定技术负责人";
      case START_BOM_SUPPLEMENT -> "发起补录";
      case START_PACKAGE_SUPPLEMENT -> "补包装";
      case START_PRICE_SUPPLEMENT -> "补明细价格";
      case LINK_EXISTING_TASK -> "关联现有任务";
      case APPLY_APPROVED_RESULT -> "应用已审核结果";
      case VIEW_SUPPLEMENT -> "查看补录内容";
      case START_COSTING -> "核算本产品";
      case RESTART_COSTING -> "重新核算";
      case RETRY_COSTING -> "重试本产品";
      case VIEW_COSTING_RESULT -> "查看结果";
      case VIEW_COSTING_PROGRESS -> "查看进度";
      case VIEW_COSTING_GAP -> "查看缺口";
      case NONE -> "";
    };
  }
  private OaForm requireForm(String oaNo) {
    if (!StringUtils.hasText(oaNo)) throw new IllegalArgumentException("报价单号不能为空");
    List<OaForm> forms = formMapper.selectList(Wrappers.<OaForm>lambdaQuery().eq(OaForm::getOaNo, oaNo.trim()));
    if (forms.size() != 1) throw new IllegalArgumentException(forms.isEmpty() ? "报价单不存在" : "报价单号不唯一");
    return forms.get(0);
  }
  private OaFormItem requireItem(OaForm form, Long itemId) {
    if (itemId == null || itemId <= 0) throw new IllegalArgumentException("报价产品行ID必须为正数");
    OaFormItem item = itemMapper.selectById(itemId);
    if (item == null || !form.getId().equals(item.getOaFormId())) throw new IllegalArgumentException("报价产品不属于当前报价单");
    return item;
  }
  private static boolean isCalculated(OaFormItem item) {
    return "已核算".equals(item.getCalcStatus()) || item.getConfirmedCostVersionId() != null;
  }
  private QuoteBomPreparationRecord currentPreparation(
      Long itemId, String productCode, String accountingMonth) {
    QuoteBomPreparationRecord preparation = preparationRecordMapper.selectOne(
        Wrappers.<QuoteBomPreparationRecord>lambdaQuery()
            .eq(QuoteBomPreparationRecord::getOaFormItemId, itemId)
            .eq(QuoteBomPreparationRecord::getQuoteProductCode, productCode)
            .eq(QuoteBomPreparationRecord::getCostPeriodMonth, accountingMonth)
            .eq(QuoteBomPreparationRecord::getActiveFlag, 1)
            .orderByDesc(QuoteBomPreparationRecord::getUpdatedAt)
            .orderByDesc(QuoteBomPreparationRecord::getId)
            .last("LIMIT 1"));
    if (preparation == null) return null;
    if (!Objects.equals(productCode, preparation.getQuoteProductCode())) return null;
    if (!Objects.equals(accountingMonth, preparation.getCostPeriodMonth())) return null;
    return "READY".equalsIgnoreCase(preparation.getPreparationStatus())
            || "CONFIRMED".equalsIgnoreCase(preparation.getPreparationStatus())
        ? preparation : null;
  }
  private static String sha256(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).toUpperCase(Locale.ROOT); }
    catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
  }
  private static String value(Object value) { return value == null ? "" : value.toString(); }
  private static String valueOr(String value, String fallback) { return StringUtils.hasText(value) ? value : fallback; }
  private record ProjectionFields(String bomCode, String bomLabel, String priceCode, String priceLabel,
      Long assigneeId, String assigneeName, String statusCode, String statusLabel,
      QuoteItemCollaborationAction action, String message) {}
}
