package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;

/** 技术负责人来源统一使用的最小业务上下文。 */
public record TechnicianAssignmentContext(
    String businessUnitType,
    String processCode,
    String sourceBusinessDivision,
    String applicantDepartment,
    String applicantOffice,
    OaFormItem item) {

  public static TechnicianAssignmentContext of(
      OaForm form, OaFormItem item, String businessUnitType) {
    return new TechnicianAssignmentContext(
        businessUnitType,
        form == null ? null : form.getProcessCode(),
        form == null ? null : form.getSourceBusinessDivision(),
        form == null ? null : form.getApplicantDept(),
        form == null ? null : form.getApplicantOffice(),
        item);
  }
}
