package com.sanhua.marketingcost.integration.oa;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** I01 业务回执：HTTP 200 后仍须检查 code 与 data.status；保存成功也不等于核算输入齐全。 */
public record OaQuotationResponse(String code, String message, Data data) {
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Data(
      String status,
      String quoteId,
      String quoteUrl,
      List<ItemMapping> itemMappings,
      String inputStatus,
      List<InputIssue> inputIssues,
      List<FieldError> errors) {}

  public record ItemMapping(String tableKey, String rowId, String quoteItemId) {}

  public record InputIssue(
      String field,
      String tableKey,
      String rowId,
      String issueCode,
      String severity,
      String responsibility,
      String message) {}

  public record FieldError(String field, String message) {}

  static OaQuotationResponse rejected(String code, String message, List<FieldError> errors) {
    return new OaQuotationResponse(
        code, message, new Data("REJECTED", null, null, null, null, null, errors));
  }

  static OaQuotationResponse failed(String code, String message) {
    return new OaQuotationResponse(
        code, message, new Data("FAILED", null, null, null, null, null, null));
  }
}
