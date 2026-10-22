package com.sanhua.marketingcost.service.electronicdrawing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.DrawingVerificationTask;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationCodec;
import com.sanhua.marketingcost.mapper.DrawingVerificationResultMapper;
import com.sanhua.marketingcost.mapper.DrawingVerificationTaskMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 从已提交的 PDF 结果创建来源；不联网、不伪造文件证据、不改写旧来源。 */
@Service
public class ElectronicDrawingSourceImportService {
  private static final Logger log =
      LoggerFactory.getLogger(ElectronicDrawingSourceImportService.class);
  private final ElectronicDrawingWorkflowContextPort contexts;
  private final OaFormItemMapper items;
  private final QuoteBomPreparationRecordMapper preparations;
  private final QuoteBomSupplementVersionMapper versions;
  private final ElectronicDrawingSourceNodeRepository nodes;
  private final DrawingVerificationTaskMapper tasks;
  private final DrawingVerificationResultMapper results;
  private final DrawingVerificationCodec codec;
  private final DrawingVerificationService verifications;

  public ElectronicDrawingSourceImportService(
      ElectronicDrawingWorkflowContextPort contexts,
      OaFormItemMapper items,
      QuoteBomPreparationRecordMapper preparations,
      QuoteBomSupplementVersionMapper versions,
      ElectronicDrawingSourceNodeRepository nodes,
      DrawingVerificationTaskMapper tasks,
      DrawingVerificationResultMapper results,
      DrawingVerificationCodec codec,
      DrawingVerificationService verifications) {
    this.contexts = contexts;
    this.items = items;
    this.preparations = preparations;
    this.versions = versions;
    this.nodes = nodes;
    this.tasks = tasks;
    this.results = results;
    this.codec = codec;
    this.verifications = verifications;
  }

  @Transactional
  public ImportResult importSource(ElectronicDrawingWorkContext expected, Long resultId) {
    if (expected == null
        || expected.workflowId() == null
        || expected.oaFormItemId() == null
        || resultId == null) {
      throw invalid("导入缺少报价上下文或已接收结果");
    }
    // 锁是事务的首次读取；并发确认/报价修改之后再建立一致性快照。
    items.selectForCostingWrite(expected.oaFormItemId());
    var context =
        contexts.load(
            expected.workflowId(),
            expected.businessUnitType(),
            expected.applicableOrgCode(),
            expected.accountingMonth());
    var result = results.findById(resultId);
    if (result == null) throw invalid("已接收的电子图库结果不存在");
    var known = tasks.selectById(result.getVerificationTaskId());
    if (known == null || !Objects.equals(known.getOaFormItemId(), expected.oaFormItemId())) {
      throw invalid("电子图库结果没有对应的报价产品任务");
    }
    var task = tasks.selectForUpdate(known.getId());
    validateBinding(context, task, resultId);
    var preparation = preparations.selectForElectronicDrawingImport(context.preparationId());
    validatePreparation(context, preparation);
    var payload = codec.parse(result.getRawSummaryJson());
    if (DrawingVerificationStore.INITIAL.equals(result.getEventType())
        && payload.summary().differenceCount() != 0) {
      throw invalid("有差异的首次结果必须等待人工确认");
    }
    String scope = "NON_BARE_FULL_BOM";
    var existing =
        versions.selectList(
            Wrappers.<QuoteBomSupplementVersion>lambdaQuery()
                .eq(QuoteBomSupplementVersion::getPreparationId, preparation.getId())
                .eq(QuoteBomSupplementVersion::getDrawingResultId, resultId)
                .eq(QuoteBomSupplementVersion::getSupplementScope, scope)
                .eq(QuoteBomSupplementVersion::getBomSource, ElectronicDrawingSourceKind.PDF)
                .orderByDesc(QuoteBomSupplementVersion::getVersionNo)
                .orderByDesc(QuoteBomSupplementVersion::getId)
                .last("LIMIT 1"));
    if (!existing.isEmpty()) {
      var version = existing.getFirst();
      if (!Objects.equals(context.sourceVersionId(), version.getId()))
        attach(context, version.getId());
      complete(task, resultId);
      logCommitted(task, version, true);
      return receipt(version, nodes.findByVersionId(version.getId()).size(), true);
    }
    // 多组原文完整保存在结果中；只有核实归属的产品组成为本报价的 BOM 行。
    var sourceNodes = DrawingJsonNodes.forProduct(payload.summary(), task.getDrawingNo());
    var now = now();
    var version = newVersion(context, task, resultId, scope, result.getReceivedAt(), now);
    if (versions.insert(version) != 1 || version.getId() == null) throw invalid("电子图库来源版本创建失败");
    sourceNodes.forEach(
        node -> {
          node.setSupplementVersionId(version.getId());
          node.setCreatedAt(now);
          node.setUpdatedAt(now);
        });
    nodes.insertAll(version.getId(), sourceNodes);
    attach(context, version.getId());
    complete(task, resultId);
    logCommitted(task, version, false);
    return receipt(version, sourceNodes.size(), false);
  }

  private void logCommitted(
      DrawingVerificationTask task, QuoteBomSupplementVersion source, boolean reused) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            log.info(
                "drawing source committed: requestId={} localTaskId={} externalTaskId={}"
                    + " resultId={} sourceVersionId={} oaNo={} itemId={} month={} reused={}",
                task.getRequestId(),
                task.getId(),
                task.getExternalTaskId(),
                source.getDrawingResultId(),
                source.getId(),
                source.getOaNo(),
                source.getOaFormItemId(),
                source.getPeriodMonth(),
                reused);
          }
        });
  }

  private void validateBinding(
      ElectronicDrawingWorkContext context, DrawingVerificationTask task, Long resultId) {
    if (task == null
        || !"RECEIVED".equals(task.getTaskState())
        || task.getActiveScopeKey() == null
        || !Objects.equals(resultId, task.getCurrentResultId())
        || !Objects.equals(task.getOaFormItemId(), context.oaFormItemId())
        || !Objects.equals(task.getOaNo(), context.oaNo())
        || !Objects.equals(task.getBusinessUnitType(), context.businessUnitType())
        || !Objects.equals(task.getMaterialOrgCode(), context.materialOrgCode())
        || !Objects.equals(task.getPriceOrgCode(), context.priceOrgCode())
        || !Objects.equals(task.getAccountingMonth(), context.accountingMonth())
        || !context.active()
        || !context.bomRequired()
        || context.preparationId() == null
        || !"FULL_BOM".equals(context.primaryScope())
        || !verifications.inputsStillMatch(context, task)) {
      throw invalid("电子图库结果已过期或与当前报价产品、月份、组织及输入不一致");
    }
  }

  private void validatePreparation(
      ElectronicDrawingWorkContext context, QuoteBomPreparationRecord preparation) {
    if (preparation == null
        || !Objects.equals(preparation.getOaFormItemId(), context.oaFormItemId())
        || !Objects.equals(preparation.getOaFormId(), context.oaFormId())
        || !Objects.equals(preparation.getQuoteProductCode(), context.quoteProductCode())
        || !Objects.equals(preparation.getCostPeriodMonth(), context.accountingMonth())
        || !Objects.equals(preparation.getMaterialOrganizationCode(), context.materialOrgCode())
        || !Objects.equals(preparation.getActiveFlag(), 1)) {
      throw invalid("图库来源与当前 BOM 准备记录不一致");
    }
  }

  private QuoteBomSupplementVersion newVersion(
      ElectronicDrawingWorkContext context,
      DrawingVerificationTask task,
      Long resultId,
      String scope,
      LocalDateTime receivedAt,
      LocalDateTime now) {
    var version = new QuoteBomSupplementVersion();
    version.setPreparationId(context.preparationId());
    version.setTaskNo(context.taskNo());
    version.setOaNo(context.oaNo());
    version.setOaFormItemId(context.oaFormItemId());
    version.setQuoteProductCode(context.quoteProductCode());
    version.setProductType("NON_BARE");
    version.setSupplementScope(scope);
    version.setBomSource(ElectronicDrawingSourceKind.PDF);
    version.setElectronicDrawingNo(task.getDrawingNo());
    version.setDrawingResultId(resultId);
    version.setSourceRequestId(task.getRequestId());
    version.setSourceAcquiredAt(receivedAt);
    version.setMaterialOrgCode(context.materialOrgCode());
    version.setPeriodMonth(context.accountingMonth());
    version.setEffectiveFrom(YearMonth.parse(context.accountingMonth()).atDay(1));
    List<QuoteBomSupplementVersion> latest =
        versions.selectList(
            Wrappers.<QuoteBomSupplementVersion>lambdaQuery()
                .eq(QuoteBomSupplementVersion::getPreparationId, context.preparationId())
                .eq(QuoteBomSupplementVersion::getSupplementScope, scope)
                .orderByDesc(QuoteBomSupplementVersion::getVersionNo)
                .last("LIMIT 1"));
    version.setVersionNo(latest.isEmpty() ? 1 : Math.addExact(latest.getFirst().getVersionNo(), 1));
    version.setVersionStatus("DRAFT");
    version.setActiveFlag(1);
    version.setCreatedAt(now);
    version.setUpdatedAt(now);
    return version;
  }

  private void attach(ElectronicDrawingWorkContext context, Long sourceId) {
    contexts.attachSourceVersion(context, sourceId, now());
  }

  private void complete(DrawingVerificationTask task, Long resultId) {
    if (Objects.equals(task.getConsumedResultId(), resultId)
        && "COMPLETE".equals(task.getConsumptionState())) return;
    if (tasks.completeConsumption(task.getId(), resultId, now()) != 1) {
      throw new ElectronicDrawingWorkflowRetryException("图库确认结果已变化，请读取最新结果后继续");
    }
  }

  private ImportResult receipt(QuoteBomSupplementVersion version, int count, boolean reused) {
    return new ImportResult(
        version.getId(),
        version.getVersionNo(),
        version.getVersionStatus(),
        count,
        version.getDrawingResultId(),
        reused,
        true);
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
  }

  private static ElectronicDrawingSourceImportException invalid(String message) {
    return new ElectronicDrawingSourceImportException(
        ElectronicDrawingSourceImportException.BINDING_INVALID, message);
  }

  public record ImportResult(
      Long supplementVersionId,
      Integer versionNo,
      String versionStatus,
      int sourceNodeCount,
      Long drawingResultId,
      boolean reused,
      boolean currentVersion) {}
}
