package com.sanhua.marketingcost.dto.collaboration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record TechnicalTaskDetailResponse(
    Long taskId,
    String taskNo,
    String productCode,
    String productName,
    String productSpec,
    String productModel,
    String accountingMonth,
    String primaryScope,
    String primaryScopeLabel,
    String status,
    String statusLabel,
    Integer taskVersion,
    boolean editable,
    String nextAction,
    String nextActionLabel,
    int completedRequirementCount,
    int totalRequirementCount,
    List<Requirement> requirements,
    List<Gap> gaps,
    QuoteSource quoteSource,
    String validationStatus,
    LocalDateTime validationAt,
    List<ReturnIssue> returnIssues,
    String guidance) {

  public TechnicalTaskDetailResponse {
    requirements = requirements == null ? List.of() : List.copyOf(requirements);
    gaps = gaps == null ? List.of() : List.copyOf(gaps);
    returnIssues = returnIssues == null ? List.of() : List.copyOf(returnIssues);
  }

  public record Requirement(
      String code, String label, boolean required, boolean completed, String message) {}

  public record Gap(
      Long gapId,
      String category,
      String categoryLabel,
      String materialCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String materialRole,
      String bomPath,
      BigDecimal bomQuantity,
      String bomUnit,
      String accountingMonth,
      String applicableOrgCode,
      String sourceType,
      Long sourceId,
      String reason,
      String status,
      String statusLabel) {}

  public record QuoteSource(String oaNo, Long itemId) {}

  public record ReturnIssue(String itemType, String itemTypeLabel, String reason) {}
}
