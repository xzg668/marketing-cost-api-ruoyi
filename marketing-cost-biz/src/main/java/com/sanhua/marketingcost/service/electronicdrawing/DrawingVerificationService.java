package com.sanhua.marketingcost.service.electronicdrawing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.DrawingVerificationTask;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationClient;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationCodec;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationException;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationPayload;
import com.sanhua.marketingcost.mapper.DrawingVerificationTaskMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomGateway;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 持久任务去重和短事务；网络调用在两个事务之间，接收明细与 BOM 消费分别推进。 */
@Service
public class DrawingVerificationService {
  public static final String SOURCE_SYSTEM = "DRAWING_PDF";
  private static final Logger log = LoggerFactory.getLogger(DrawingVerificationService.class);
  private final ElectronicDrawingWorkflowContextPort contexts;
  private final ElectronicDrawingProductLookup drawings;
  private final CurrentU9BomGateway u9;
  private final OaFormItemMapper items;
  private final DrawingVerificationTaskMapper tasks;
  private final DrawingVerificationStore store;
  private final DrawingVerificationClient client;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;

  public DrawingVerificationService(
      ElectronicDrawingWorkflowContextPort contexts,
      ElectronicDrawingProductLookup drawings,
      CurrentU9BomGateway u9,
      OaFormItemMapper items,
      DrawingVerificationTaskMapper tasks,
      DrawingVerificationStore store,
      DrawingVerificationClient client,
      ObjectMapper json,
      PlatformTransactionManager transactionManager) {
    this.contexts = contexts;
    this.drawings = drawings;
    this.u9 = u9;
    this.items = items;
    this.tasks = tasks;
    this.store = store;
    this.client = client;
    this.json = json;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /** 空结果仅表示 U9 已有 BOM；错误不会被当作缺失，已有任务不会重新 POST。 */
  public Optional<DrawingVerificationTask> start(
      ElectronicDrawingWorkContext scope, String requestedDrawing) {
    return execute(scope, requestedDrawing, null);
  }

  /** 显式重新获取以旧任务 ID 作并发条件；重复点击同一旧任务不会再次 POST。 */
  public Optional<DrawingVerificationTask> restart(
      ElectronicDrawingWorkContext scope, String requestedDrawing, Long expectedTaskId) {
    if (expectedTaskId == null || expectedTaskId <= 0)
      throw new IllegalArgumentException("重新获取必须提供当前取图任务编号");
    return execute(scope, requestedDrawing, expectedTaskId);
  }

  private Optional<DrawingVerificationTask> execute(
      ElectronicDrawingWorkContext scope, String requestedDrawing, Long expectedTaskId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("电子图库核验必须在报价写事务提交后发起");
    }
    Prepared prepared =
        transaction.execute(status -> prepare(scope, requestedDrawing, expectedTaskId));
    if (prepared == null) return Optional.empty();
    if (!prepared.created()) return Optional.of(prepared.task());
    DrawingVerificationTask task = prepared.task();
    DrawingVerificationPayload payload;
    try {
      payload =
          client.verify(
              new DrawingVerificationClient.Query(
                  task.getDrawingNo(),
                  task.getRequestId(),
                  task.getId(),
                  task.getOaNo(),
                  task.getOaFormItemId(),
                  task.getAccountingMonth()));
    } catch (DrawingVerificationException failure) {
      transaction.executeWithoutResult(
          status ->
              tasks.failRequest(
                  task.getId(), failure.getCode().name(), failure.getMessage(), now()));
      return Optional.of(tasks.selectById(task.getId()));
    }
    // 保存失败向调用者报告；REQUESTING 留在库中，恢复时只读现状，不重发可能已经成功的 POST。
    DrawingVerificationTask saved;
    try {
      saved = Objects.requireNonNull(transaction.execute(status -> receive(scope, task, payload)));
    } catch (RuntimeException failure) {
      log.error(
          "drawing verification save failed: requestId={} localTaskId={} externalTaskId={}"
              + " exceptionType={}",
          task.getRequestId(),
          task.getId(),
          payload.summary().taskId(),
          failure.getClass().getSimpleName());
      throw new IllegalStateException("电子图库响应保存失败，请按原请求号核查，不要重复发起核验", failure);
    }
    log.info(
        "drawing verification committed: requestId={} localTaskId={} externalTaskId={} resultId={}"
            + " state={}",
        saved.getRequestId(),
        saved.getId(),
        saved.getExternalTaskId(),
        saved.getCurrentResultId(),
        saved.getTaskState());
    return Optional.of(saved);
  }

  /** 页面读取只读持久状态；不核验、不改状态、不触发超时重试。 */
  public Optional<DrawingVerificationTask> current(ElectronicDrawingWorkContext scope) {
    return Optional.ofNullable(tasks.findActive(scopeKey(scope)));
  }

  private Prepared prepare(
      ElectronicDrawingWorkContext expected, String requestedDrawing, Long expectedTaskId) {
    items.selectForCostingWrite(expected.oaFormItemId());
    var scope = reload(expected);
    var item = items.selectById(scope.oaFormItemId());
    if (hasU9Bom(scope, item)) return null;
    String drawing = drawings.requireDrawing(scope, requestedDrawing);
    String snapshot = snapshot(scope, item, drawing);
    String key = scopeKey(scope);
    DrawingVerificationTask existing = tasks.findActive(key);
    if (expectedTaskId != null && existing == null)
      throw new IllegalArgumentException("当前取图任务已变化，请刷新后重新获取");
    if (expectedTaskId != null && !Objects.equals(expectedTaskId, existing.getId()))
      return new Prepared(existing, false);
    if (existing != null
        && snapshot.equals(existing.getInputSnapshotJson())
        && expectedTaskId == null) return new Prepared(existing, false);
    if (existing != null) supersede(existing);
    var task = new DrawingVerificationTask();
    task.setSourceSystem(SOURCE_SYSTEM);
    task.setRequestId(UUID.randomUUID().toString());
    task.setOaNo(scope.oaNo());
    task.setOaFormItemId(scope.oaFormItemId());
    task.setBusinessUnitType(scope.businessUnitType());
    task.setMaterialOrgCode(scope.materialOrgCode());
    task.setPriceOrgCode(scope.priceOrgCode());
    task.setAccountingMonth(scope.accountingMonth());
    task.setDrawingNo(drawing);
    task.setInputSnapshotJson(snapshot);
    task.setActiveScopeKey(key);
    task.setTaskState("REQUESTING");
    task.setConsumptionState("NOT_READY");
    task.setRowVersion(0);
    task.setCreatedAt(now());
    task.setUpdatedAt(task.getCreatedAt());
    tasks.insert(task);
    return new Prepared(task, true);
  }

  private DrawingVerificationTask receive(
      ElectronicDrawingWorkContext expected,
      DrawingVerificationTask requested,
      DrawingVerificationPayload payload) {
    items.selectForCostingWrite(requested.getOaFormItemId());
    DrawingVerificationTask task = tasks.selectById(requested.getId());
    if (task.getCurrentResultId() != null) return task;
    if (task.getActiveScopeKey() != null && !inputsStillMatch(expected, task)) {
      supersede(task);
      task = tasks.selectById(task.getId());
    }
    String state =
        task.getActiveScopeKey() == null
            ? "SUPERSEDED"
            : payload.summary().differenceCount() == 0 ? "RECEIVED" : "WAIT_REVIEW";
    store.append(task, DrawingVerificationStore.INITIAL, payload, null, state, now());
    return tasks.selectById(task.getId());
  }

  boolean inputsStillMatch(ElectronicDrawingWorkContext expected, DrawingVerificationTask task) {
    try {
      var scope = reload(expected);
      String drawing = drawings.requireDrawing(scope, task.getDrawingNo());
      return task.getInputSnapshotJson()
          .equals(snapshot(scope, items.selectById(scope.oaFormItemId()), drawing));
    } catch (IllegalArgumentException | ElectronicDrawingSourceImportException changed) {
      return false;
    }
  }

  private void supersede(DrawingVerificationTask task) {
    if (tasks.supersede(task.getId(), task.getRowVersion(), now()) != 1) {
      throw new OptimisticLockingFailureException("电子图库任务已变化，请刷新后继续");
    }
  }

  boolean hasU9Bom(ElectronicDrawingWorkContext scope, OaFormItem item) {
    if (!QuoteProductIdentityUtils.hasFormalMaterialNo(item)) return false;
    var now = now();
    CurrentU9BomResult result =
        u9.read(
            new QuoteBomReadContext(
                scope.oaFormId(),
                scope.oaFormItemId(),
                scope.oaNo(),
                scope.accountingMonth(),
                scope.businessUnitType(),
                scope.quoteProductCode(),
                scope.productName(),
                scope.productSpec(),
                scope.productModel(),
                scope.priceOrgCode(),
                scope.materialOrgCode(),
                now.toLocalDate(),
                now));
    if (result == null
        || (result.status() != CurrentU9BomResult.Status.AVAILABLE
            && result.status() != CurrentU9BomResult.Status.NOT_FOUND)) {
      throw new IllegalStateException("U9 BOM 查询未确认缺失，不能发起电子图库核验");
    }
    return result.status() == CurrentU9BomResult.Status.AVAILABLE;
  }

  private ElectronicDrawingWorkContext reload(ElectronicDrawingWorkContext scope) {
    var current =
        contexts.load(
            scope.workflowId(),
            scope.businessUnitType(),
            scope.applicableOrgCode(),
            scope.accountingMonth());
    if (!Objects.equals(current.oaFormId(), scope.oaFormId())
        || !Objects.equals(current.oaFormItemId(), scope.oaFormItemId())
        || !Objects.equals(current.materialOrgCode(), scope.materialOrgCode())
        || !Objects.equals(current.priceOrgCode(), scope.priceOrgCode())
        || !Objects.equals(current.businessUnitType(), scope.businessUnitType())
        || !Objects.equals(current.accountingMonth(), scope.accountingMonth())) {
      throw new IllegalArgumentException("电子图库报价产品、月份或组织已变化");
    }
    return current;
  }

  private String scopeKey(ElectronicDrawingWorkContext scope) {
    return DrawingVerificationCodec.sha256(
        json.createArrayNode()
            .add(scope.oaFormItemId())
            .add(scope.businessUnitType())
            .add(scope.materialOrgCode())
            .add(scope.priceOrgCode())
            .add(scope.accountingMonth())
            .toString());
  }

  private String snapshot(ElectronicDrawingWorkContext scope, OaFormItem item, String drawing) {
    try {
      return json.writeValueAsString(
          new Input(
              scope.oaNo(),
              scope.oaFormItemId(),
              scope.businessUnitType(),
              scope.materialOrgCode(),
              scope.priceOrgCode(),
              scope.accountingMonth(),
              item.getMaterialNo(),
              item.getSunlModel(),
              item.getSpec(),
              item.getCustomerDrawing(),
              item.getProductName(),
              drawing));
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException("无法保存电子图库输入快照", impossible);
    }
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
  }

  private record Prepared(DrawingVerificationTask task, boolean created) {}

  private record Input(
      String oaNo,
      Long oaFormItemId,
      String businessUnitType,
      String materialOrgCode,
      String priceOrgCode,
      String accountingMonth,
      String materialNo,
      String model,
      String specification,
      String suppliedDrawingNo,
      String productName,
      String drawingNo) {}
}
