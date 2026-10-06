package com.sanhua.marketingcost.service.technicaldata;

/** 已成功提交的冻结资料。APPROVED 仅用于读取上线前已有的审批历史，不再产生新审批结论。 */
public final class TechnicalDataSubmissionState {
  private TechnicalDataSubmissionState() {}

  public static boolean submitted(String status) {
    return "SUBMITTED".equals(status) || "APPROVED".equals(status);
  }

  public static boolean acceptedByOa(String status) {
    return "SENT".equals(status) || "APPROVED".equals(status);
  }

  public static boolean submittedTodo(String status) {
    return "SUBMITTED".equals(status) || "DONE".equals(status);
  }
}
