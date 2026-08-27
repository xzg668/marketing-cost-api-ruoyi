package com.sanhua.marketingcost.dto.collaboration;

import com.sanhua.marketingcost.service.collaboration.ElectronicDrawingMaterialMatcher.Option;
import java.math.BigDecimal;
import java.util.List;

/** 电子图库 Excel 最近一次导入结果；页面、刷新恢复和映射确认共用。 */
public record ElectronicDrawingBomImportResponse(
    boolean parsed,
    boolean mappingComplete,
    boolean structureReady,
    String message,
    Integer taskVersion,
    Long supplementVersionId,
    String sourceFileName,
    String fileSha256,
    String sourceSheetName,
    int sourceNodeCount,
    int autoMatchedCount,
    int confirmedCount,
    int unmatchedCount,
    int ambiguousCount,
    TechnicalBomDraftResponse draft,
    List<MappingItem> mappings,
    List<Issue> issues) {

  public ElectronicDrawingBomImportResponse {
    mappings = mappings == null ? List.of() : List.copyOf(mappings);
    issues = issues == null ? List.of() : List.copyOf(issues);
  }

  public record MappingItem(
      String nodeId,
      String sourceSequence,
      Integer sourceRowNumber,
      String drawingCode,
      String sourceName,
      String sourceMaterial,
      BigDecimal referenceWeight,
      String importanceClass,
      String hsfRiskClass,
      String sourceRemark,
      String status,
      String selectedMaterialCode,
      List<Option> options) {
    public MappingItem {
      options = options == null ? List.of() : List.copyOf(options);
    }
  }

  public record Issue(
      String category,
      String code,
      String nodeId,
      Integer sourceRowNumber,
      String sourceSequence,
      String message) {}
}
