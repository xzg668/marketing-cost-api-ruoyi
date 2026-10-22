package com.sanhua.marketingcost.service.electronicdrawing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.electronicdrawing.DrawingVerificationWorkspace;
import com.sanhua.marketingcost.dto.electronicdrawing.DrawingVerificationWorkspace.*;
import com.sanhua.marketingcost.entity.DrawingVerificationResult;
import com.sanhua.marketingcost.entity.DrawingVerificationTask;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy;
import com.sanhua.marketingcost.mapper.DrawingVerificationResultMapper;
import com.sanhua.marketingcost.mapper.DrawingVerificationTaskMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.BusinessUnitRepriceLockGuard;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.transaction.annotation.Transactional;

/** 产品授权和页面读模型；GET 始终只读，明确操作才获取或续接结果。 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DrawingVerificationWorkspaceService {
  private final ElectronicDrawingWorkflowContextPort contexts;
  private final ElectronicDrawingProductLookup drawings;
  private final DrawingVerificationService verifications;
  private final DrawingResultConsumptionService consumption;
  private final DrawingVerificationTaskMapper tasks;
  private final DrawingVerificationResultMapper results;
  private final QuoteBomSupplementVersionMapper versions;
  private final OaWorkflowAccessPolicy access;
  private final BusinessUnitRepriceLockGuard reprice;
  private final ObjectMapper json;

  public DrawingVerificationWorkspaceService(
      ElectronicDrawingWorkflowContextPort contexts,
      ElectronicDrawingProductLookup drawings,
      DrawingVerificationService verifications,
      DrawingResultConsumptionService consumption,
      DrawingVerificationTaskMapper tasks,
      DrawingVerificationResultMapper results,
      QuoteBomSupplementVersionMapper versions,
      OaWorkflowAccessPolicy access,
      BusinessUnitRepriceLockGuard reprice,
      ObjectMapper json) {
    this.contexts = contexts;
    this.drawings = drawings;
    this.verifications = verifications;
    this.consumption = consumption;
    this.tasks = tasks;
    this.results = results;
    this.versions = versions;
    this.access = access;
    this.reprice = reprice;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public DrawingVerificationWorkspace state(String oaNo, Long itemId, String month) {
    return workspace(scope(oaNo, itemId, month), null);
  }

  @Transactional(readOnly = true)
  public Detail detail(String oaNo, Long itemId, String month, Long resultId) {
    var scope = scope(oaNo, itemId, month);
    var result = results.findById(resultId);
    var task = result == null ? null : tasks.selectById(result.getVerificationTaskId());
    if (task == null || !belongs(task, scope)) throw new IllegalArgumentException("该报价产品没有此图库历史结果");
    var plmSource =
        result.getPlmSourceResultId() == null
            ? result
            : results.findById(result.getPlmSourceResultId());
    if (plmSource == null || !Objects.equals(plmSource.getVerificationTaskId(), task.getId()))
      throw new IllegalStateException("图库历史的 PLM 来源关联异常，请按结果编号排查");
    JsonNode summary = parse(result.getRawSummaryJson());
    String adoption =
        "MANUAL_CONFIRMATION".equals(result.getEventType())
            ? "MANUAL_CONFIRMED"
            : summary.path("differenceCount").asInt(-1) == 0 ? "DIRECT_ACCEPTED" : "WAIT_REVIEW";
    var sourceIds =
        versions
            .selectList(
                Wrappers.<QuoteBomSupplementVersion>lambdaQuery()
                    .eq(QuoteBomSupplementVersion::getDrawingResultId, resultId)
                    .orderByAsc(QuoteBomSupplementVersion::getId))
            .stream()
            .map(QuoteBomSupplementVersion::getId)
            .toList();
    return new Detail(
        task.getId(),
        resultId,
        result.getEventType(),
        adoption,
        summary,
        parse(result.getDataJson()),
        parse(plmSource.getPlmDataJson()),
        plmSource.getId(),
        sourceIds,
        result.getReceivedAt());
  }

  /** 不开外层事务；核验 HTTP 与结果消费各自管理短事务。 */
  public DrawingVerificationWorkspace operate(
      String oaNo, Long itemId, String month, Operation request) {
    var scope = scope(oaNo, itemId, month);
    access.requireCosting(scope.oaFormId());
    reprice.assertCostRunAllowed(oaNo);
    if (request == null || request.action() == null) throw new IllegalArgumentException("请选择图库操作");
    if (request.action() == Action.CONTINUE) {
      var current =
          verifications
              .current(scope)
              .orElseThrow(() -> new IllegalArgumentException("当前没有可续接的图库任务"));
      if (!Objects.equals(current.getId(), request.expectedTaskId()))
        throw new IllegalArgumentException("图库任务已变化，请刷新后继续处理");
      consumption.consume(current.getId());
    } else {
      var saved =
          request.action() == Action.RESTART
              ? verifications.restart(scope, request.drawingNo(), request.expectedTaskId())
              : verifications.start(scope, request.drawingNo());
      if (saved.isEmpty()) return workspace(scope, "U9 BOM 已可用，请继续原核算流程");
      if ("RECEIVED".equals(saved.orElseThrow().getTaskState()))
        consumption.consume(saved.orElseThrow().getId());
    }
    return workspace(
        contexts.load(itemId, scope.businessUnitType(), scope.applicableOrgCode(), month), null);
  }

  private ElectronicDrawingWorkContext scope(String oaNo, Long itemId, String month) {
    if (month == null || !month.matches("\\d{4}-\\d{2}"))
      throw new IllegalArgumentException("请指定图库核算月份 YYYY-MM");
    try {
      YearMonth.parse(month);
    } catch (java.time.DateTimeException invalid) {
      throw new IllegalArgumentException("图库核算月份无效");
    }
    String businessUnit = BusinessUnitContext.getCurrentBusinessUnitType();
    if (businessUnit == null || businessUnit.isBlank())
      throw new IllegalArgumentException("请先选择当前业务单元");
    var scope = contexts.loadForCurrentBusinessUnit(itemId, month);
    if (!Objects.equals(scope.oaNo(), oaNo)
        || !Objects.equals(scope.oaFormItemId(), itemId)
        || !Objects.equals(scope.businessUnitType(), businessUnit))
      throw new IllegalArgumentException("当前用户无权访问该报价产品的图库资料");
    return scope;
  }

  private DrawingVerificationWorkspace workspace(
      ElectronicDrawingWorkContext scope, String notice) {
    var headers =
        results.findProductHeaders(
            scope.oaFormItemId(), scope.accountingMonth(), scope.businessUnitType());
    Map<Long, List<DrawingVerificationResult>> byTask =
        headers.stream()
            .collect(Collectors.groupingBy(DrawingVerificationResult::getVerificationTaskId));
    var history =
        tasks
            .findProductHistory(
                scope.oaFormItemId(), scope.accountingMonth(), scope.businessUnitType())
            .stream()
            .filter(task -> belongs(task, scope))
            .map(task -> view(task, byTask.getOrDefault(task.getId(), List.of())))
            .toList();
    var active = verifications.current(scope).orElse(null);
    var current =
        active == null
            ? null
            : history.stream()
                .filter(task -> task.localTaskId().equals(active.getId()))
                .findFirst()
                .orElse(null);
    return new DrawingVerificationWorkspace(
        scope.oaNo(),
        scope.oaFormItemId(),
        scope.accountingMonth(),
        current,
        history,
        scope.workflowStage(),
        scope.sourceVersionId(),
        drawings.options(scope).stream()
            .map(
                row ->
                    new DrawingOption(row.drawingNo(), row.materialNo(), row.name(), row.model()))
            .toList(),
        notice);
  }

  private boolean belongs(DrawingVerificationTask task, ElectronicDrawingWorkContext scope) {
    return Objects.equals(task.getOaNo(), scope.oaNo())
        && Objects.equals(task.getOaFormItemId(), scope.oaFormItemId())
        && Objects.equals(task.getBusinessUnitType(), scope.businessUnitType())
        && Objects.equals(task.getAccountingMonth(), scope.accountingMonth());
  }

  private Task view(DrawingVerificationTask task, List<DrawingVerificationResult> headers) {
    return new Task(
        task.getId(),
        task.getExternalTaskId(),
        task.getDrawingNo(),
        task.getTaskState(),
        task.getConsumptionState(),
        task.getDifferenceCount(),
        task.getEditUrl(),
        task.getErrorMessage(),
        task.getCurrentResultId(),
        task.getConsumedResultId(),
        task.getCreatedAt(),
        headers.stream()
            .map(
                row ->
                    new Result(
                        row.getId(),
                        row.getEventType(),
                        row.getUpstreamRevision(),
                        row.getConfirmedAtMs(),
                        row.getReceivedAt()))
            .toList());
  }

  private JsonNode parse(String value) {
    try {
      return json.readTree(value);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("已保存的图库历史格式异常，请按结果编号排查");
    }
  }

  public enum Action {
    START,
    RESTART,
    CONTINUE
  }

  public record Operation(Action action, String drawingNo, Long expectedTaskId) {}
}
