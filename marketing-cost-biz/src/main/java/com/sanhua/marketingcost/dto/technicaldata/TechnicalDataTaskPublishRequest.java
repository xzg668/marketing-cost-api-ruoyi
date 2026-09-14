package com.sanhua.marketingcost.dto.technicaldata;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 报价系统向技术资料工作台发布的完整任务快照。 */
public record TechnicalDataTaskPublishRequest(
    String sourceRequestId,
    String sourceSystem,
    Long oaFormId,
    String oaNo,
    String quoteNo,
    String accountingMonth,
    String businessUnitType,
    String applicableOrgCode,
    Long assigneeUserId,
    String assigneeName,
    Long reviewerUserId,
    String reviewerName,
    String externalSystem,
    String externalTaskId,
    String externalTaskStatus,
    LocalDateTime dueAt,
    List<Product> products) {

  public record Product(
      Long oaFormItemId,
      Integer levelNo,
      String materialNo,
      String productName,
      String sourceModel,
      String sourceSpec,
      String sourceProductProperty,
      Boolean newProduct,
      Boolean nonStandardPackage,
      Boolean validPackageSource,
      Boolean validCmsAuxiliarySource,
      Boolean validCmsSalarySource,
      Boolean auxiliaryRequested,
      Boolean salaryRequested,
      Map<String, Object> sourceFields) {}
}
