package com.sanhua.marketingcost.dto.quotebom;

import java.util.List;

/** 单个OA产品行的最终有效BOM查询/预览结果。 */
public record QuoteEffectiveBomResponse(
    String state,
    String oaNo,
    Long oaFormItemId,
    String costPeriodMonth,
    String topProductCode,
    String customerKey,
    String customerKeySource,
    String packageMethod,
    String priceOrgCode,
    String materialOrganizationCode,
    Long monthlySnapshotId,
    String sourceBomBatchId,
    String buildBatchId,
    String effectiveVariantHash,
    Long monthlySourceOaFormItemId,
    List<QuoteEffectiveBomNodeResponse> nodes,
    List<QuoteEffectiveBomAlternativeResponse> alternativeSelections,
    QuoteEffectiveBomExclusionSummaryResponse exclusionSummary,
    List<QuoteEffectiveBomIssueResponse> blockIssues,
    List<String> warnings) {

  public QuoteEffectiveBomResponse {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    alternativeSelections =
        alternativeSelections == null ? List.of() : List.copyOf(alternativeSelections);
    blockIssues = blockIssues == null ? List.of() : List.copyOf(blockIssues);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
