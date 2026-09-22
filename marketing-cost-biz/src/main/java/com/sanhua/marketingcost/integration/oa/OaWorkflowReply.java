package com.sanhua.marketingcost.integration.oa;

public record OaWorkflowReply(String code, String message, Data data) {
  public record Data(String status) {}
  public static OaWorkflowReply success() { return new OaWorkflowReply("0", "通知处理成功", new Data("SUCCEEDED")); }
  public static OaWorkflowReply rejected(String code, String message) { return new OaWorkflowReply(code, message, new Data("REJECTED")); }
}
