package com.sanhua.marketingcost.dto.quotebom;

import java.time.LocalDate;
import java.util.List;

public record QuoteProductBomPreparationPreview(
    Long preparationRecordId,
    Long quoteBomStatusId,
    Long oaFormId,
    Long oaFormItemId,
    String oaNo,
    String quoteProductCode,
    String productType,
    String bareProductCode,
    boolean needPackage,
    String periodMonth,
    String preparationStatus,
    String reviewStatus,
    boolean ready,
    boolean needTechnicianTask,
    boolean abnormal,
    String bodyBomSource,
    boolean bodyBomReady,
    int bodyBomLineCount,
    String referenceFinishedCode,
    String sourceTopProductCode,
    boolean packageReferenceReady,
    int packageLineCount,
    Long taskId,
    Long reusedFromTaskId,
    String reusedFromOaNo,
    Long reusedFromOaFormItemId,
    String reuseType,
    LocalDate reuseValidUntil,
    List<String> missingScopes,
    List<String> gapMessages,
    String errorMessage,
    List<QuoteBomSourceLineDto> bodyBomLines,
    List<PackageComponentStructureLineDto> packageLines) {}
