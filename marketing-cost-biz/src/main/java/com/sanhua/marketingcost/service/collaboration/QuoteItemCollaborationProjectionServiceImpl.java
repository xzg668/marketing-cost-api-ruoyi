package com.sanhua.marketingcost.service.collaboration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationHistoryResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationSummaryResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteItemCollaborationResponse;
import com.sanhua.marketingcost.entity.IntegrationOutbox;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.mapper.IntegrationOutboxMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanAction;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
  private final IntegrationOutboxMapper outboxMapper;

  public QuoteItemCollaborationProjectionServiceImpl(
      OaFormMapper formMapper,
      OaFormItemMapper itemMapper,
      QuoteBomPreparationRecordMapper preparationRecordMapper,
      QuoteCollaborationScanService scanService,
      QuoteCollaborationTaskRepository repository,
      CollaborationTechnicianResolver technicianResolver,
      IntegrationOutboxMapper outboxMapper) {
    this.formMapper = formMapper;
    this.itemMapper = itemMapper;
    this.preparationRecordMapper = preparationRecordMapper;
    this.scanService = scanService;
    this.repository = repository;
    this.technicianResolver = technicianResolver;
    this.outboxMapper = outboxMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public QuoteCollaborationSummaryResponse summary(String oaNo) {
    OaForm form = requireForm(oaNo);
    List<QuoteItemCollaborationResponse> items = itemMapper.selectList(
        Wrappers.<OaFormItem>lambdaQuery().eq(OaFormItem::getOaFormId, form.getId())
            .orderByAsc(OaFormItem::getSeq).orderByAsc(OaFormItem::getId))
        .stream().map(item -> projectItem(form, item)).toList();
    String version = sha256(items.stream().map(QuoteItemCollaborationResponse::projectionVersion)
        .reduce("", (left, right) -> left + "|" + right));
    return new QuoteCollaborationSummaryResponse(form.getOaNo(), version, items);
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
    QuoteItemCollaborationResponse projection = projectItem(form, item);
    if (projection.productTaskId() == null) {
      return new QuoteCollaborationHistoryResponse(itemId, null, null,
          projection.currentStatus(), projection.currentStatusLabel(), projection.assigneeName(), List.of());
    }
    List<QuoteCollaborationHistoryResponse.Entry> entries = outboxMapper
        .selectByAggregate("PRODUCT_TASK", projection.productTaskId()).stream()
        .map(this::historyEntry).toList();
    return new QuoteCollaborationHistoryResponse(itemId, projection.productTaskId(),
        projection.productTaskNo(), projection.currentStatus(), projection.currentStatusLabel(),
        projection.assigneeName(), entries);
  }

  private QuoteCollaborationHistoryResponse.Entry historyEntry(IntegrationOutbox event) {
    return new QuoteCollaborationHistoryResponse.Entry(event.getOccurredAt(), event.getEventType(),
        eventTitle(event.getEventType()), eventDescription(event));
  }

  private static String eventTitle(String eventType) {
    if ("TECH_TASK_CREATED".equals(eventType)) return "已发起技术补录";
    if ("TECH_TASK_LINKED".equals(eventType)) return "当前报价已关联原任务";
    if ("TECH_TASK_UPDATED".equals(eventType)) return "技术补录状态已更新";
    if ("FINANCE_REVIEW_CREATED".equals(eventType)) return "已提交财务审核";
    if ("TECH_TASK_RETURNED".equals(eventType)) return "财务已驳回技术修改";
    return StringUtils.hasText(eventType) ? eventType : "状态更新";
  }

  private static String eventDescription(IntegrationOutbox event) {
    if (StringUtils.hasText(event.getLastErrorMessage())) return event.getLastErrorMessage();
    return "事件已记录；OA对接关闭时保留在报价系统内，不伪造OA推送成功";
  }

  private QuoteItemCollaborationResponse projectItem(OaForm form, OaFormItem item) {
    QuoteCollaborationScanResult scan = scanService.scanQuoteItem(item.getId());
    CollaborationScope scope = new CollaborationScope(scan.businessUnitType(), scan.priceOrgCode());
    QuoteCollaborationQuoteLink link = repository.findActiveLinksByQuoteItem(item.getId(), scope)
        .stream().findFirst().orElse(null);
    QuoteCollaborationProductTask task = task(scan, link, scope);
    QuoteBomPreparationRecord preparation =
        currentPreparation(item.getId(), scan.productCode(), scan.accountingMonth());
    ProjectionFields fields = fields(form, item, scan, task, link, preparation);
    String version = sha256(String.join("|", String.valueOf(item.getId()),
        value(scan.status()), value(scan.action()), value(scan.requiredScope()),
        value(task == null ? null : task.getId()), value(task == null ? null : task.getTaskVersion()),
        value(link == null ? null : link.getId()), value(link == null ? null : link.getLinkStatus()),
        value(preparation == null ? null : preparation.getId()),
        value(preparation == null ? null : preparation.getUpdatedAt()),
        value(item.getCalcStatus()), value(item.getConfirmedCostVersionId())));
    return new QuoteItemCollaborationResponse(item.getId(), fields.bomCode, fields.bomLabel,
        fields.priceCode, fields.priceLabel, scan.price().gapCount(), fields.assigneeId,
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
      QuoteBomPreparationRecord preparation) {
    if (isCalculated(item)) return new ProjectionFields(bomCode(scan), bomLabel(scan), "READY", "价格齐全",
        task == null ? null : task.getCurrentAssigneeUserId(), task == null ? null : task.getCurrentAssigneeName(),
        "COMPLETED", "核算完成", QuoteItemCollaborationAction.VIEW_COSTING_RESULT, "该产品已完成核算");
    if (link != null && "READY".equals(link.getLinkStatus())) return ready(scan, "补录审核通过，已具备核算条件");
    if (task != null && link != null) return taskFields(scan, task);
    if (preparation != null) return new ProjectionFields(bomCode(scan), bomLabel(scan), "PENDING",
        "在核算工作台确认", null, null, "COSTING", "核算中",
        QuoteItemCollaborationAction.CONTINUE_COSTING, "已存在当前核算准备，继续原六步核算流程");
    if (scan.action() == QuoteCollaborationScanAction.NO_COLLABORATION_REQUIRED) return ready(scan, scan.message());
    if (scan.action() == QuoteCollaborationScanAction.REUSE_APPROVED_RESULT)
      return action(scan, null, "可复用", "已有半年有效的审核结果", QuoteItemCollaborationAction.APPLY_APPROVED_RESULT, scan.message());
    if (scan.action() == QuoteCollaborationScanAction.LINK_ACTIVE_TASK)
      return action(scan, null, "他人处理中", "同月同产品已有补录任务", QuoteItemCollaborationAction.LINK_EXISTING_TASK, scan.message());
    if (scan.action() == QuoteCollaborationScanAction.SYSTEM_BLOCKED)
      return action(scan, null, "检查未通过", "系统检查未通过", QuoteItemCollaborationAction.NONE, scan.message());
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
      case "COSTING" -> QuoteItemCollaborationAction.CONTINUE_COSTING;
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
      case START_COSTING -> "发起核算";
      case CONTINUE_COSTING -> "继续核算";
      case VIEW_COSTING_RESULT -> "查看核算结果";
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
    return Objects.equals(accountingMonth, preparation.getCostPeriodMonth()) ? preparation : null;
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
