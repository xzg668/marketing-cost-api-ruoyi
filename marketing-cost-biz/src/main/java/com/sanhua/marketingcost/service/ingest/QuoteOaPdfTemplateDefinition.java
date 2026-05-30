package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.enums.QuoteExcelTemplateType;
import java.util.List;

public class QuoteOaPdfTemplateDefinition {
  private final QuoteExcelTemplateType templateType;
  private final String processCode;
  private final String processName;
  private final String quoteScenario;
  private final String businessUnitType;
  private final String expenseProductCategory;
  private final String defaultBusinessType;
  private final List<String> sectionAnchors;
  private final List<QuoteOaPdfFieldDefinition> headerFields;
  private final List<QuoteOaPdfFieldDefinition> itemFields;
  private final List<QuoteOaPdfFieldDefinition> feeFields;
  private final QuoteOaPdfTableDefinition itemTable;

  QuoteOaPdfTemplateDefinition(
      QuoteExcelTemplateType templateType,
      String processName,
      String businessUnitType,
      String expenseProductCategory,
      String defaultBusinessType,
      List<String> sectionAnchors,
      List<QuoteOaPdfFieldDefinition> headerFields,
      List<QuoteOaPdfFieldDefinition> itemFields,
      List<QuoteOaPdfFieldDefinition> feeFields,
      QuoteOaPdfTableDefinition itemTable) {
    this.templateType = templateType;
    this.processCode = templateType.getProcessCode();
    this.processName = processName;
    this.quoteScenario = templateType.getQuoteScenario();
    this.businessUnitType = businessUnitType;
    this.expenseProductCategory = expenseProductCategory;
    this.defaultBusinessType = defaultBusinessType;
    this.sectionAnchors = sectionAnchors == null ? List.of() : List.copyOf(sectionAnchors);
    this.headerFields = headerFields == null ? List.of() : List.copyOf(headerFields);
    this.itemFields = itemFields == null ? List.of() : List.copyOf(itemFields);
    this.feeFields = feeFields == null ? List.of() : List.copyOf(feeFields);
    this.itemTable = itemTable;
  }

  public QuoteExcelTemplateType getTemplateType() {
    return templateType;
  }

  public String getProcessCode() {
    return processCode;
  }

  public String getProcessName() {
    return processName;
  }

  public String getQuoteScenario() {
    return quoteScenario;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public String getExpenseProductCategory() {
    return expenseProductCategory;
  }

  public String getDefaultBusinessType() {
    return defaultBusinessType;
  }

  public List<String> getSectionAnchors() {
    return sectionAnchors;
  }

  public List<QuoteOaPdfFieldDefinition> getHeaderFields() {
    return headerFields;
  }

  public List<QuoteOaPdfFieldDefinition> getItemFields() {
    return itemFields;
  }

  public List<QuoteOaPdfFieldDefinition> getFeeFields() {
    return feeFields;
  }

  public QuoteOaPdfTableDefinition getItemTable() {
    return itemTable;
  }
}
