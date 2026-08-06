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
import java.io.ByteArrayInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PriceLinkedImportDispatchServiceImpl
    implements PriceLinkedImportDispatchService {

  private final PriceLinkedWorkbookTypeDetector detector;
  private final PriceLinkedItemService standardImportService;
  private final PriceLinkedType2ImportOrchestrator type2Orchestrator;

  @Value("${cost.linked.type2-import.enabled:true}")
  private boolean type2ImportEnabled = true;

  public PriceLinkedImportDispatchServiceImpl(
      PriceLinkedWorkbookTypeDetector detector,
      PriceLinkedItemService standardImportService,
      PriceLinkedType2ImportOrchestrator type2Orchestrator) {
    this.detector = detector;
    this.standardImportService = standardImportService;
    this.type2Orchestrator = type2Orchestrator;
  }

  @Override
  public PriceLinkedType2ImportPreviewResponse preview(PriceLinkedImportCommand command) {
    byte[] bytes = requireBytes(command);
    PriceLinkedWorkbookDetectionResult detection =
        detector.detect(new ByteArrayInputStream(bytes), command.getSourceFileName());
    if (detection.getType() == PriceLinkedWorkbookType.TYPE2) {
      requireType2ImportEnabled();
      PriceLinkedType2ImportPreviewResponse response = type2Orchestrator.preview(command);
      response.setDetectionMessage(detection.getMessage());
      return response;
    }
    PriceLinkedType2ImportPreviewResponse response = detectionOnlyPreview(
        detection, PriceLinkedImportFileDigest.sha256(bytes));
    response.setCanConfirm(detection.getType() == PriceLinkedWorkbookType.STANDARD);
    if (!response.isCanConfirm()) {
      response.getErrors().add(error(
          "TEMPLATE_DETECTION",
          detection.getType().name(),
          detection.getMessage()));
    }
    return response;
  }

  @Override
  public PriceItemImportResponse confirm(PriceLinkedImportCommand command) {
    byte[] bytes = requireBytes(command);
    String actualSha256 = PriceLinkedImportFileDigest.sha256(bytes);
    PriceLinkedWorkbookDetectionResult detection =
        detector.detect(new ByteArrayInputStream(bytes), command.getSourceFileName());
    if (detection.getType() == PriceLinkedWorkbookType.STANDARD) {
      PriceItemImportResponse response = standardImportService.importExcel(
          new ByteArrayInputStream(bytes),
          command.getPricingMonth(),
          command.isOverwriteManual(),
          command.getBusinessUnitType(),
          command.getSourceFileName(),
          command.getEffectiveStrategy(),
          command.getFormulaEffectiveDate(),
          command.getFactorPriceConflictStrategy());
      response.setTemplateType(PriceLinkedWorkbookType.STANDARD.name());
      response.setFileSha256(actualSha256);
      response.setImportDataSheetName(first(detection.getStandardCandidateSheets()));
      if (!StringUtils.hasText(response.getImportStatus())) {
        response.setImportStatus(response.getErrors().isEmpty() ? "SUCCESS" : "PARTIAL");
      }
      return response;
    }
    if (detection.getType() == PriceLinkedWorkbookType.TYPE2) {
      requireType2ImportEnabled();
      requireMatchingPreviewHash(command.getExpectedPreviewSha256(), actualSha256);
      return type2Orchestrator.confirm(command);
    }
    throw new IllegalArgumentException(
        "Excel模板无法导入：" + detection.getType() + "；" + detection.getMessage());
  }

  private PriceLinkedType2ImportPreviewResponse detectionOnlyPreview(
      PriceLinkedWorkbookDetectionResult detection, String sha256) {
    PriceLinkedType2ImportPreviewResponse response =
        new PriceLinkedType2ImportPreviewResponse();
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
      throw new IllegalArgumentException("类型2确认导入必须携带预检返回的文件SHA-256");
    }
    if (!expected.trim().equalsIgnoreCase(actual)) {
      throw new IllegalArgumentException(
          "文件SHA-256与预检不一致，文件可能已被替换，请重新预检");
    }
  }

  private void requireType2ImportEnabled() {
    if (!type2ImportEnabled) {
      throw new IllegalStateException(
          "类型2联动价导入当前已关闭；原标准联动价模板仍可正常导入");
    }
  }

  private String first(java.util.List<String> values) {
    return values == null || values.isEmpty() ? null : values.getFirst();
  }

  private PriceItemImportResponse.ErrorRow error(
      String stage, String code, String message) {
    PriceItemImportResponse.ErrorRow error =
        new PriceItemImportResponse.ErrorRow(null, null, null, message);
    error.setErrorStage(stage);
    error.setErrorCode(code);
    return error;
  }
}
