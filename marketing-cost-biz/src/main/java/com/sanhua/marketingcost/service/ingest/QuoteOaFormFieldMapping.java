package com.sanhua.marketingcost.service.ingest;

final class QuoteOaFormFieldMapping {
  private final String scope;
  private final String fieldCode;
  private final String fieldName;
  private final String sourceRange;
  private final String targetTable;

  QuoteOaFormFieldMapping(
      String scope, String fieldCode, String fieldName, String sourceRange, String targetTable) {
    this.scope = scope;
    this.fieldCode = fieldCode;
    this.fieldName = fieldName;
    this.sourceRange = sourceRange;
    this.targetTable = targetTable;
  }

  String getScope() {
    return scope;
  }

  String getFieldCode() {
    return fieldCode;
  }

  String getFieldName() {
    return fieldName;
  }

  String getSourceRange() {
    return sourceRange;
  }

  String getTargetTable() {
    return targetTable;
  }
}
