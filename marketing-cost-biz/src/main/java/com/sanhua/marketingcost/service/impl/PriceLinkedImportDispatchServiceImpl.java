package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportCommand;
import com.sanhua.marketingcost.dto.PriceLinkedType2ImportPreviewResponse;
import com.sanhua.marketingcost.dto.PriceLinkedWorkbookDetectionResult;
import com.sanhua.marketingcost.enums.PriceLinkedWorkbookType;
import com.sanhua.marketingcost.service.PriceLinkedImportDispatchService;
import com.sanhua.marketingcost.service.PriceLinkedItemService;
import com.sanhua.marketingcost.service.PriceLinkedType2ImportOrchestrator;
import com.sanhua.marketingcost.service.PriceLinkedWorkbookTypeDetector;
import com.sanhua.marketingcost.service.technicaldata.*;
import java.io.ByteArrayInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PriceLinkedImportDispatchServiceImpl implements PriceLinkedImportDispatchService {

  private final TechnicalPriceCorrectionService corrections;
  private final TechnicalPriceCorrectionWorkbook workbook;
  private final TechnicalPriceCorrectionImportService technicalImport;
  private final TechnicalDataActorProvider actors;
  private final PriceLinkedWorkbookTypeDetector detector;
  private final PriceLinkedItemService standardImportService;
  private final PriceLinkedType2ImportOrchestrator type2Orchestrator;

  @Value("${cost.linked.type2-import.enabled:true}")
  private boolean type2ImportEnabled = true;

  public PriceLinkedImportDispatchServiceImpl(
      PriceLinkedWorkbookTypeDetector detector,
      PriceLinkedItemService standardImportService,
      PriceLinkedType2ImportOrchestrator type2Orchestrator,
      TechnicalPriceCorrectionService corrections,
      TechnicalPriceCorrectionWorkbook workbook,
      TechnicalPriceCorrectionImportService technicalImport,
      TechnicalDataActorProvider actors) {
    this.corrections = corrections;
    this.workbook = workbook;
    this.technicalImport = technicalImport;
    this.actors = actors;
    this.detector = detector;
    this.standardImportService = standardImportService;
    this.type2Orchestrator = type2Orchestrator;
  }

  @Override
  @Transactional(readOnly = true)
  public PriceLinkedType2ImportPreviewResponse preview(PriceLinkedImportCommand command) {
    byte[] bytes = requireBytes(command);
    PriceLinkedWorkbookDetectionResult detection =
        detector.detect(new ByteArrayInputStream(bytes), command.getSourceFileName());
    command = prepare(command, detection, false);
    if (detection.getType() == PriceLinkedWorkbookType.TYPE2) {
      requireType2ImportEnabled();
      PriceLinkedType2ImportPreviewResponse response = type2Orchestrator.preview(command);
      response.setDetectionMessage(detection.getMessage());
      addTechnicalPreview(command, response);
      return response;
    }
    PriceLinkedType2ImportPreviewResponse response =
        detectionOnlyPreview(detection, PriceLinkedImportFileDigest.sha256(bytes));
    response.setCanConfirm(detection.getType() == PriceLinkedWorkbookType.STANDARD);
    if (!response.isCanConfirm()) {
      response
          .getErrors()
          .add(error("TEMPLATE_DETECTION", detection.getType().name(), detection.getMessage()));
    }
    addTechnicalPreview(command, response);
    return response;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PriceItemImportResponse confirm(PriceLinkedImportCommand command) {
    byte[] bytes = requireBytes(command);
    String actualSha256 = PriceLinkedImportFileDigest.sha256(bytes);
    PriceLinkedWorkbookDetectionResult detection =
        detector.detect(new ByteArrayInputStream(bytes), command.getSourceFileName());
    command = prepare(command, detection, true);
    if (command.getTechnicalPlan() != null)
      requireMatchingPreviewHash(command.getExpectedPreviewSha256(), actualSha256);
    if (detection.getType() == PriceLinkedWorkbookType.STANDARD) {
      PriceItemImportResponse response =
          command.getTechnicalPlan() == null
              ? standardImportService.importExcel(
                  new ByteArrayInputStream(bytes),
                  command.getPricingMonth(),
                  command.isOverwriteManual(),
                  command.getBusinessUnitType(),
                  command.getSourceFileName(),
                  command.getEffectiveStrategy(),
                  command.getFormulaEffectiveDate(),
                  command.getFactorPriceConflictStrategy())
              : standardImportService.importExcel(command);
      response.setTemplateType(PriceLinkedWorkbookType.STANDARD.name());
      response.setFileSha256(actualSha256);
      response.setImportDataSheetName(first(detection.getStandardCandidateSheets()));
      if (!StringUtils.hasText(response.getImportStatus())) {
        response.setImportStatus(response.getErrors().isEmpty() ? "SUCCESS" : "PARTIAL");
      }
      technicalImport.finish(command, response, actualSha256);
      return response;
    }
    if (detection.getType() == PriceLinkedWorkbookType.TYPE2) {
      requireType2ImportEnabled();
      requireMatchingPreviewHash(command.getExpectedPreviewSha256(), actualSha256);
      var response = type2Orchestrator.confirm(command);
      technicalImport.finish(command, response, actualSha256);
      return response;
    }
    throw new IllegalArgumentException(
        "Excel模板无法导入：" + detection.getType() + "；" + detection.getMessage());
  }

  private PriceLinkedImportCommand prepare(
      PriceLinkedImportCommand command,
      PriceLinkedWorkbookDetectionResult detection,
      boolean lock) {
    if (command.getTechnicalContext() == null) {
      if (workbook.hasAssociation(command.getFileBytes()))
        throw new IllegalArgumentException("补录修正文件必须从原产品核算页进入，并携带审批上下文");
      return command;
    }
    if (detection.getType() != PriceLinkedWorkbookType.STANDARD
        && detection.getType() != PriceLinkedWorkbookType.TYPE2)
      throw new IllegalArgumentException("补录文件模板无法识别，请核对表头");
    var scope =
        corrections.require(
            command.getTechnicalContext(),
            command.getPricingMonth(),
            command.getBusinessUnitType(),
            actors.current(),
            lock);
    var plan =
        workbook.validate(
            command.getFileBytes(),
            scope,
            first(detection.getStandardCandidateSheets()),
            detection.getType() == PriceLinkedWorkbookType.TYPE2);
    return command.withTechnicalPlan(plan);
  }

  private void addTechnicalPreview(
      PriceLinkedImportCommand command, PriceLinkedType2ImportPreviewResponse response) {
    var plan = command.getTechnicalPlan();
    if (plan == null) return;
    for (var row : plan.rows())
      for (String issue : row.issues()) {
        var error =
            new PriceItemImportResponse.ErrorRow(
                row.rowNumber(),
                row.values().getMaterialCode(),
                row.values().getFormulaExpr(),
                issue);
        error.setSourceSheetName(row.sheetName());
        error.setItemKey(row.itemKey());
        error.setErrorStage("TECHNICAL_PRICE");
        error.setErrorCode("TECH_PRICE_ROW_INVALID");
        response.getErrors().add(error);
      }
    if ("STANDARD".equals(response.getTemplateType())) {
      response.setBusinessRowCount(plan.rows().size());
      response.setMatchedRowCount(plan.rows().size());
    }
    response.setCanConfirm(
        response.isCanConfirm() && plan.rows().stream().anyMatch(row -> row.issues().isEmpty()));
  }

  private PriceLinkedType2ImportPreviewResponse detectionOnlyPreview(
      PriceLinkedWorkbookDetectionResult detection, String sha256) {
    PriceLinkedType2ImportPreviewResponse response = new PriceLinkedType2ImportPreviewResponse();
    response.setFileSha256(sha256);
    response.setTemplateType(detection.getType().name());
    response.setDetectionMessage(detection.getMessage());
    response.setBusinessSheetName(first(detection.getType2CandidateSheets()));
    response.setImportDataSheetName(first(detection.getStandardCandidateSheets()));
    return response;
  }

  private byte[] requireBytes(PriceLinkedImportCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("导入请求不能为空");
    }
    byte[] bytes = command.getFileBytes();
    if (bytes.length == 0) {
      throw new IllegalArgumentException("Excel文件不能为空");
    }
    return bytes;
  }

  private void requireMatchingPreviewHash(String expected, String actual) {
    if (!StringUtils.hasText(expected)) {
      throw new IllegalArgumentException("确认导入必须携带预检返回的文件SHA-256");
    }
    if (!expected.trim().equalsIgnoreCase(actual)) {
      throw new IllegalArgumentException("文件SHA-256与预检不一致，文件可能已被替换，请重新预检");
    }
  }

  private void requireType2ImportEnabled() {
    if (!type2ImportEnabled) {
      throw new IllegalStateException("类型2联动价导入当前已关闭；原标准联动价模板仍可正常导入");
    }
  }

  private String first(java.util.List<String> values) {
    return values == null || values.isEmpty() ? null : values.getFirst();
  }

  private PriceItemImportResponse.ErrorRow error(String stage, String code, String message) {
    PriceItemImportResponse.ErrorRow error =
        new PriceItemImportResponse.ErrorRow(null, null, null, message);
    error.setErrorStage(stage);
    error.setErrorCode(code);
    return error;
  }
}
