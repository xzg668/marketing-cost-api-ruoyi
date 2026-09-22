package com.sanhua.marketingcost.service.electronicdrawing;

import java.time.LocalDateTime;

/**
 * 电子图库编排与外层任务载体之间的单向端口。
 *
 * <p>以报价产品行和核算月份定位共享 BOM 准备记录，来源、匹配及发布始终使用同一月份。
 */
public interface ElectronicDrawingWorkflowContextPort {

  ElectronicDrawingWorkContext load(
      Long workflowId, String businessUnitType, String applicableOrgCode, String accountingMonth);

  ElectronicDrawingWorkContext loadForCurrentBusinessUnit(Long workflowId, String accountingMonth);

  ElectronicDrawingWorkContext attachPreparation(
      ElectronicDrawingWorkContext context,
      Long preparationId,
      String stage,
      Long assigneeUserId,
      String assigneeName,
      LocalDateTime updatedAt);

  ElectronicDrawingWorkContext attachSourceVersion(
      ElectronicDrawingWorkContext context, Long sourceVersionId, LocalDateTime updatedAt);

  ElectronicDrawingWorkContext touch(
      ElectronicDrawingWorkContext context,
      Long sourceVersionId,
      Long updatedBy,
      String updatedByName,
      LocalDateTime updatedAt);

  ElectronicDrawingWorkContext updateStage(
      ElectronicDrawingWorkContext context,
      String stage,
      Long assigneeUserId,
      String assigneeName,
      LocalDateTime updatedAt);

  ElectronicDrawingWorkContext completePublication(
      ElectronicDrawingWorkContext context,
      String compositionFingerprint,
      LocalDateTime updatedAt);

  void record(ElectronicDrawingWorkContext context, String eventType, String description);
}
