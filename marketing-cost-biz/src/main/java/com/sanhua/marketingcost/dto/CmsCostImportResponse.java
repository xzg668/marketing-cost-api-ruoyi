package com.sanhua.marketingcost.dto;

public class CmsCostImportResponse {
  private Long importBatchId;
  private String batchNo;
  private String status;
  private int planRowCount;
  private int workshopRowCount;
  private int subjectRowCount;
  private int subjectSettingRowCount;
  private int salaryInsertCount;
  private int salarySkipCount;
  private int salaryBlockedCount;
  private int auxInsertCount;
  private int auxSkipCount;
  private int errorCount;
  private String errorMessage;

  public Long getImportBatchId() {
    return importBatchId;
  }

  public void setImportBatchId(Long importBatchId) {
    this.importBatchId = importBatchId;
  }

  public String getBatchNo() {
    return batchNo;
  }

  public void setBatchNo(String batchNo) {
    this.batchNo = batchNo;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public int getPlanRowCount() {
    return planRowCount;
  }

  public void setPlanRowCount(int planRowCount) {
    this.planRowCount = planRowCount;
  }

  public int getWorkshopRowCount() {
    return workshopRowCount;
  }

  public void setWorkshopRowCount(int workshopRowCount) {
    this.workshopRowCount = workshopRowCount;
  }

  public int getSubjectRowCount() {
    return subjectRowCount;
  }

  public void setSubjectRowCount(int subjectRowCount) {
    this.subjectRowCount = subjectRowCount;
  }

  public int getSubjectSettingRowCount() {
    return subjectSettingRowCount;
  }

  public void setSubjectSettingRowCount(int subjectSettingRowCount) {
    this.subjectSettingRowCount = subjectSettingRowCount;
  }

  public int getSalaryInsertCount() {
    return salaryInsertCount;
  }

  public void setSalaryInsertCount(int salaryInsertCount) {
    this.salaryInsertCount = salaryInsertCount;
  }

  public int getSalarySkipCount() {
    return salarySkipCount;
  }

  public void setSalarySkipCount(int salarySkipCount) {
    this.salarySkipCount = salarySkipCount;
  }

  public int getSalaryBlockedCount() {
    return salaryBlockedCount;
  }

  public void setSalaryBlockedCount(int salaryBlockedCount) {
    this.salaryBlockedCount = salaryBlockedCount;
  }

  public int getAuxInsertCount() {
    return auxInsertCount;
  }

  public void setAuxInsertCount(int auxInsertCount) {
    this.auxInsertCount = auxInsertCount;
  }

  public int getAuxSkipCount() {
    return auxSkipCount;
  }

  public void setAuxSkipCount(int auxSkipCount) {
    this.auxSkipCount = auxSkipCount;
  }

  public int getErrorCount() {
    return errorCount;
  }

  public void setErrorCount(int errorCount) {
    this.errorCount = errorCount;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }
}
