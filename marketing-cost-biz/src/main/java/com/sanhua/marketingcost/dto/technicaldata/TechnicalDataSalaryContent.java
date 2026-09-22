package com.sanhua.marketingcost.dto.technicaldata;

public final class TechnicalDataSalaryContent {
  private TechnicalDataSalaryContent() {}

  public record ItemEvidence(String laborType, TechnicalDataSalaryCmsSource reference,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      TechnicalDataSalaryUploadResponse upload) {
    public TechnicalDataSalaryCmsSource.Item cmsItem() {
      return reference == null ? null : "DIRECT".equals(laborType) ? reference.direct() : reference.indirect();
    }
    public java.math.BigDecimal sourceAmount() {
      return upload != null ? upload.amountYuan() : cmsItem() == null ? null : cmsItem().amountYuan();
    }
  }
}
