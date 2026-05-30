package com.sanhua.marketingcost.service.ingest;

import java.util.List;

public class QuoteOaPdfFieldDefinition {
  private final String scope;
  private final String fieldCode;
  private final String fieldName;
  private final String section;
  private final String targetTable;
  private final List<String> aliases;

  public QuoteOaPdfFieldDefinition(
      String scope,
      String fieldCode,
      String fieldName,
      String section,
      String targetTable,
      List<String> aliases) {
    this.scope = scope;
    this.fieldCode = fieldCode;
    this.fieldName = fieldName;
    this.section = section;
    this.targetTable = targetTable;
    this.aliases = aliases == null ? List.of() : List.copyOf(aliases);
  }

  public String getScope() {
    return scope;
  }

  public String getFieldCode() {
    return fieldCode;
  }

  public String getFieldName() {
    return fieldName;
  }

  public String getSection() {
    return section;
  }

  public String getTargetTable() {
    return targetTable;
  }

  public List<String> getAliases() {
    return aliases;
  }
}
