package com.sanhua.marketingcost.service.technicaldata;

/** 已成功提交的冻结资料。APPROVED 仅用于读取上线前已有的审批历史，不再产生新审批结论。 */
public final class TechnicalDataSubmissionState {
  private TechnicalDataSubmissionState() {}

  public static boolean submitted(String status) {
    return "SUBMITTED".equals(status) || "APPROVED".equals(status);
  }

  /** 退回尚未确认时，原提交内容仍可在本报价中读取；不赋予技术员编辑权限。 */
  public static boolean retainsSubmittedMaterials(String taskStatus) {
    return submitted(taskStatus) || "RETURN_PENDING".equals(taskStatus);
  }

  public static boolean acceptedByOa(String status) {
    return "SENT".equals(status) || "APPROVED".equals(status);
  }

  public static boolean submittedTodo(String status) {
    return "SUBMITTED".equals(status) || "DONE".equals(status);
  }
}
