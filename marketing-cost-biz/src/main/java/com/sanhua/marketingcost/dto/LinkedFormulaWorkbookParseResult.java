package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

public class LinkedFormulaWorkbookParseResult {
  private String sourceFileName;
  private final List<LinkedFormulaSheetParseResult> sheets = new ArrayList<>();
  private final List<String> errors = new ArrayList<>();

  public String getSourceFileName() {
    return sourceFileName;
  }

  public void setSourceFileName(String sourceFileName) {
    this.sourceFileName = sourceFileName;
  }

  public List<LinkedFormulaSheetParseResult> getSheets() {
    return sheets;
  }

  public List<String> getErrors() {
    return errors;
  }

  public int getRowCount() {
    return sheets.stream().mapToInt(sheet -> sheet.getRows().size()).sum();
  }

  public int getFormulaRowCount() {
    return sheets.stream()
        .flatMap(sheet -> sheet.getRows().stream())
        .mapToInt(row -> Boolean.TRUE.equals(row.getHasFormula()) ? 1 : 0)
        .sum();
  }
}
