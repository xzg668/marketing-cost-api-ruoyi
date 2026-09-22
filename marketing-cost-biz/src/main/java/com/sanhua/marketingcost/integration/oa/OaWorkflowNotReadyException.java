package com.sanhua.marketingcost.integration.oa;

/** 回调先于发送回执到达时保持等待，确认同一提交后再继续处理。 */
public class OaWorkflowNotReadyException extends RuntimeException {
  public OaWorkflowNotReadyException() { super("审批事件已保存，等待原提交发送结果确认"); }
}
