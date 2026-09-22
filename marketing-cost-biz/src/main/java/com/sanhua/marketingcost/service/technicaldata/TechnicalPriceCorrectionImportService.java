package com.sanhua.marketingcost.service.technicaldata;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalPriceImportResult;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.FactorUploadBatchService;
import com.sanhua.marketingcost.service.impl.TechnicalPriceSourceResolverImpl;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在既有导入事务内追加来源归属、实际计算及逐项回执，不修改技术批准快照。 */
@Service
public class TechnicalPriceCorrectionImportService {
  private final PriceLinkedItemMapper linked;
  private final FactorUploadBatchMapper batches;
  private final FactorUploadBatchService batchService;
  private final TechnicalPriceSourceResolverImpl calculator;
  private final OaMessageCodec json;

  public TechnicalPriceCorrectionImportService(
      PriceLinkedItemMapper linked,
      FactorUploadBatchMapper batches,
      FactorUploadBatchService batchService,
      TechnicalPriceSourceResolverImpl calculator,
      OaMessageCodec json) {
    this.linked = linked;
    this.batches = batches;
    this.batchService = batchService;
    this.calculator = calculator;
    this.json = json;
  }

  public List<TechnicalPriceImportResult> results(FactorUploadBatch batch) {
    if (batch.getTechnicalResultJson() == null) return List.of();
    var rows = json.read(batch.getTechnicalResultJson()).path("results");
    var result = new ArrayList<TechnicalPriceImportResult>();
    for (var row : rows)
      result.add(
          new TechnicalPriceImportResult(
              row.path("itemKey").asText(),
              row.path("sheetName").asText(),
              row.path("rowNumber").asInt(),
              row.path("materialNo").asText(),
              row.path("linkedItemId").isNull() ? null : row.path("linkedItemId").asLong(),
              row.path("status").asText(),
              row.path("errorCode").isNull() ? null : row.path("errorCode").asText(),
              row.path("message").asText()));
    return List.copyOf(result);
  }

  public void restoreSummary(FactorUploadBatch batch, PriceLinkedImportBatchDetailDto detail) {
    var summary = json.read(batch.getTechnicalResultJson()).path("summary");
    detail.setLinkedVersionCreatedCount(summary.path("created").asInt());
    detail.setLinkedUnchangedSkippedCount(summary.path("reused").asInt());
    detail.setLinkedExpiredCount(summary.path("expired").asInt());
  }

  public PriceLinkedItem current(Long version, String key) {
    return linked.selectOne(
        Wrappers.lambdaQuery(PriceLinkedItem.class)
            .eq(PriceLinkedItem::getSourceKind, "TECH_SUPPLEMENTAL")
            .eq(PriceLinkedItem::getTechnicalVersionId, version)
            .eq(PriceLinkedItem::getTechnicalItemKey, key)
            .orderByDesc(PriceLinkedItem::getTechnicalRevision)
            .last("LIMIT 1"));
  }

  public void identify(
      PriceLinkedItem candidate,
      TechnicalPriceCorrectionWorkbook.Plan plan,
      TechnicalPriceCorrectionWorkbook.ImportRow row) {
    var old = current(plan.scope().version().getId(), row.itemKey());
    candidate.setSourceKind("TECH_SUPPLEMENTAL");
    candidate.setTechnicalVersionId(plan.scope().version().getId());
    candidate.setTechnicalItemKey(row.itemKey());
    candidate.setTechnicalRevision(
        old == null ? 1 : Objects.requireNonNullElse(old.getTechnicalRevision(), 0) + 1);
    candidate.setTechnicalPublicationStatus("PENDING");
    candidate.setTechnicalPublicationMessage("修正已导入，等待实际取价验证");
    candidate.setBusinessUnitType(plan.scope().businessUnit());
    candidate.setSourceName("技术补录");
    candidate.setEffectiveTo(null);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void finish(
      PriceLinkedImportCommand command, PriceItemImportResponse response, String sha) {
    var plan = command.getTechnicalPlan();
    if (plan == null) return;
    if (response.getFactorUploadBatchId() == null) {
      var request = new FactorUploadBatchCreateRequest();
      request.setPriceMonth(command.getPricingMonth());
      request.setBusinessUnitType(command.getBusinessUnitType());
      request.setFileName(command.getSourceFileName());
      request.setFileSha256(sha);
      request.setImportType("MONTHLY_LINKED_FACTOR");
      request.setImportPurpose("TECH_PRICE_CORRECTION");
      var auth =
          org.springframework.security.core.context.SecurityContextHolder.getContext()
              .getAuthentication();
      request.setUploadedBy(auth == null ? null : auth.getName());
      var batch = batchService.createFactorBatch(request);
      response.setFactorUploadBatchId(batch.getId());
      response.setBatchId(batch.getId().toString());
    }
    Map<String, TechnicalPriceImportResult> imported = new HashMap<>();
    response.getTechnicalResults().forEach(row -> imported.put(row.itemKey(), row));
    List<TechnicalPriceImportResult> results = new ArrayList<>();
    for (var row : plan.rows()) {
      var raw = imported.get(row.itemKey());
      if (raw == null || raw.linkedItemId() == null) {
        String message = String.join("；", row.issues());
        if (message.isBlank())
          message =
              response.getErrors().stream()
                  .filter(e -> Objects.equals(e.getRowNumber(), row.rowNumber()))
                  .map(PriceItemImportResponse.ErrorRow::getMessage)
                  .findFirst()
                  .orElse("本行未形成公式记录，请查看导入问题");
        results.add(
            new TechnicalPriceImportResult(
                row.itemKey(),
                row.sheetName(),
                row.rowNumber(),
                row.values().getMaterialCode(),
                null,
                "FAILED",
                "TECH_PRICE_IMPORT_FAILED",
                message));
        continue;
      }
      PriceLinkedItem source = linked.selectById(raw.linkedItemId());
      if (source == null
          || !Objects.equals(source.getTechnicalVersionId(), plan.scope().version().getId())
          || !Objects.equals(source.getTechnicalItemKey(), row.itemKey()))
        throw new IllegalStateException("导入回执与实际补录来源不一致");
      boolean available = false;
      String message;
      var scope = plan.scope();
      var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
      var context =
          CostRunContext.quote(
              scope.oaNo(),
              scope.itemId(),
              scope.product().getMaterialNo(),
              null,
              null,
              scope.businessUnit(),
              scope.month(),
              now,
              null);
      context.setPriceOrgCode(row.values().getOrgCode());
      context.setPriceCheckOnly(true);
      try {
        var price = calculator.calculate(source, context);
        available =
            "OK".equals(price.getCalcStatus())
                && price.getPartUnitPrice() != null
                && price.getPartUnitPrice().signum() > 0;
        message =
            available
                ? "公式及因素已通过实际计算，返回原产品检查并生成价格"
                : Objects.toString(price.getCalcMessage(), "公式未算出有效正数单价");
      } catch (RuntimeException error) {
        message =
            "实际计算未通过：" + Objects.toString(error.getMessage(), error.getClass().getSimpleName());
      }
      // 失败修正不能撤销以前已可用来源；仅新结果算通后切换，同一批准项最多一条 AVAILABLE。
      if (available) {
        linked.update(
            null,
            Wrappers.lambdaUpdate(PriceLinkedItem.class)
                .eq(PriceLinkedItem::getTechnicalVersionId, source.getTechnicalVersionId())
                .eq(PriceLinkedItem::getTechnicalItemKey, source.getTechnicalItemKey())
                .ne(PriceLinkedItem::getId, source.getId())
                .eq(PriceLinkedItem::getTechnicalPublicationStatus, "AVAILABLE")
                .set(PriceLinkedItem::getTechnicalPublicationStatus, "SUPERSEDED"));
        source.setTechnicalPublicationStatus("AVAILABLE");
      } else if (!"AVAILABLE".equals(source.getTechnicalPublicationStatus()))
        source.setTechnicalPublicationStatus("FAILED");
      source.setTechnicalPublicationMessage(
          message.length() > 1000 ? message.substring(0, 1000) : message);
      if (source.getSourceUploadBatchId() == null)
        source.setSourceUploadBatchId(response.getFactorUploadBatchId());
      linked.updateById(source);
      results.add(
          new TechnicalPriceImportResult(
              row.itemKey(),
              row.sheetName(),
              row.rowNumber(),
              source.getMaterialCode(),
              source.getId(),
              available ? ("REUSED".equals(raw.status()) ? "REUSED" : "AVAILABLE") : "WAIT_PRICE",
              available ? null : "TECH_PRICE_CORRECTION_REQUIRED",
              message));
    }
    response.setTechnicalResults(List.copyOf(results));
    response.setImportPurpose("TECH_PRICE_CORRECTION");
    response.setBusinessRowCount(plan.rows().size());
    response.setMatchedRowCount(plan.rows().size());
    boolean all =
        results.stream().allMatch(r -> Set.of("AVAILABLE", "REUSED").contains(r.status()));
    boolean any =
        results.stream().anyMatch(r -> Set.of("AVAILABLE", "REUSED").contains(r.status()));
    response.setImportStatus(all ? "SUCCESS" : any ? "PARTIAL" : "FAILED");
    var batch = batches.selectById(response.getFactorUploadBatchId());
    batch.setTechnicalVersionId(plan.scope().version().getId());
    batch.setTechnicalResultJson(
        json.write(
            Map.of(
                "oaNo",
                plan.scope().oaNo(),
                "oaFormItemId",
                plan.scope().itemId(),
                "periodMonth",
                plan.scope().month(),
                "businessUnitType",
                plan.scope().businessUnit(),
                "contentFingerprint",
                plan.scope().version().getContentFingerprint(),
                "rows",
                plan.rows(),
                "results",
                results,
                "summary",
                Map.of(
                    "created",
                    response.getLinkedVersionCreatedCount(),
                    "reused",
                    response.getLinkedUnchangedSkippedCount(),
                    "expired",
                    response.getLinkedExpiredCount()))));
    batch.setImportPurpose("TECH_PRICE_CORRECTION");
    batch.setLinkedRowCount(plan.rows().size());
    batch.setErrorCount(
        (int)
            results.stream()
                .filter(row -> !Set.of("AVAILABLE", "REUSED").contains(row.status()))
                .count());
    batch.setFileSha256(sha);
    batch.setStatus(response.getImportStatus());
    batch.setFinishedAt(LocalDateTime.now());
    batches.updateById(batch);
  }
}
