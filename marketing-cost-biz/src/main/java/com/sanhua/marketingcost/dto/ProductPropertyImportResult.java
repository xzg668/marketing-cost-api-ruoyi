package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

public class ProductPropertyImportResult {
  private int total;
  private int inserted;
  private int updated;
  private int removed;
  private int resolvedDivision;
  private int excelDivision;
  private List<String> errors = new ArrayList<>();
  private List<String> warnings = new ArrayList<>();

  public int getTotal() { return total; }
  public void setTotal(int value) { this.total = value; }
  public int getInserted() { return inserted; }
  public void setInserted(int value) { this.inserted = value; }
  public int getUpdated() { return updated; }
  public void setUpdated(int value) { this.updated = value; }
  public int getRemoved() { return removed; }
  public void setRemoved(int value) { this.removed = value; }
  public int getResolvedDivision() { return resolvedDivision; }
  public void setResolvedDivision(int value) { this.resolvedDivision = value; }
  public int getExcelDivision() { return excelDivision; }
  public void setExcelDivision(int value) { this.excelDivision = value; }
  public List<String> getErrors() { return errors; }
  public void setErrors(List<String> value) { this.errors = value == null ? new ArrayList<>() : value; }
  public List<String> getWarnings() { return warnings; }
  public void setWarnings(List<String> value) { this.warnings = value == null ? new ArrayList<>() : value; }
  public void addError(String value) { errors.add(value); }
  public void addWarning(String value) { warnings.add(value); }
  public boolean isSuccess() { return errors.isEmpty(); }
}
