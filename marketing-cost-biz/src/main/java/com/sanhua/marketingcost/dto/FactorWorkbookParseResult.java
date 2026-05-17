package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

public class FactorWorkbookParseResult {
  private String sourceFileName;
  private final List<FactorSheetParseResult> sheets = new ArrayList<>();

  public String getSourceFileName() {
    return sourceFileName;
  }

  public void setSourceFileName(String sourceFileName) {
    this.sourceFileName = sourceFileName;
  }

  public List<FactorSheetParseResult> getSheets() {
    return sheets;
  }

  public int getValidRowCount() {
    return sheets.stream().mapToInt(sheet -> sheet.getRows().size()).sum();
  }

  public int getErrorCount() {
    return sheets.stream().mapToInt(sheet -> sheet.getErrors().size()).sum();
  }
}
