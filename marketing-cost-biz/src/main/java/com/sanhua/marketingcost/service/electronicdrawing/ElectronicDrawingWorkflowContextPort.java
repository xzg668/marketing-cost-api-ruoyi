package com.sanhua.marketingcost.service.electronicdrawing;

import java.time.LocalDateTime;

/**
 * 电子图库编排与外层任务载体之间的单向端口。
 *
 * <p>实现可以暂时落在旧协作任务，也可以落在新的技术资料任务；电子图库核心不反向依赖任何任务实体、
 * Mapper、审核结果或报价关联表。
 */
public interface ElectronicDrawingWorkflowContextPort {

  ElectronicDrawingWorkContext load(
      Long workflowId, String businessUnitType, String applicableOrgCode);

  ElectronicDrawingWorkContext loadForCurrentBusinessUnit(Long workflowId);

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
