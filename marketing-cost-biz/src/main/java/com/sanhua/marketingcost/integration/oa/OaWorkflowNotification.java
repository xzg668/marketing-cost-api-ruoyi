package com.sanhua.marketingcost.integration.oa;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** I04/I08 的对外契约；编号是业务字符串，不能当作本地数据库主键解析。 */
public record OaWorkflowNotification(String requestId, String workflowRequestId, String eventType,
    long formVersion, long version, String state, List<WorkItem> activeWorkItems,
    String taskId, String submissionId, String resultSubmissionId, String taskState,
    String employeeNo, String reason) {
  public record WorkItem(String workItemId, String nodeRole, String employeeNo, String taskId) {}
  public static final Set<String> CLOSED = Set.of("COMPLETED", "CANCELLED", "TERMINATED");
  private static final Set<String> STATES = Set.of("MATERIAL_REVIEW", "TECHNICAL", "COSTING", "RECOSTING",
      "RESULT_APPROVAL", "SALES_REVISION", "COMPLETED", "CANCELLED", "WITHDRAWN", "TERMINATED");
  private static final Set<String> EVENTS = Set.of("TECH_APPROVED", "TECH_REJECTED", "COSTING_ENTERED",
      "FLOW_STATE_CHANGED", "RESULT_RETURNED", "RESULT_APPROVAL_PROGRESS", "PROCESS_COMPLETED",
      "PROCESS_CANCELLED", "PROCESS_WITHDRAWN", "PROCESS_TERMINATED", "TASK_CHANGED");

  public static OaWorkflowNotification parse(JsonNode root) {
    keys(root, Set.of("requestId", "workflowRequestId", "eventType", "formVersion", "workflowState",
        "taskId", "submissionId", "resultSubmissionId", "taskState", "employeeNo", "reason"), "请求");
    String event = text(root, "eventType", true, 40);
    if (!EVENTS.contains(event)) throw invalid("未知 eventType：" + event);
    JsonNode workflow = root.path("workflowState");
    keys(workflow, Set.of("version", "state", "activeWorkItems"), "workflowState");
    String state = text(workflow, "state", true, 32);
    if (!STATES.contains(state)) throw invalid("未知 workflowState.state：" + state);
    JsonNode items = workflow.path("activeWorkItems");
    if (!items.isArray() || items.size() > 1000) throw invalid("activeWorkItems 必须是当前全部有效待办数组，最多1000项");
    List<WorkItem> workItems = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (JsonNode item : items) {
      keys(item, Set.of("workItemId", "nodeRole", "employeeNo", "taskId"), "activeWorkItems[]");
      String id = text(item, "workItemId", true, 128);
      String role = text(item, "nodeRole", true, 32);
      if (!Set.of("MATERIAL", "TECHNICIAN", "TECH_LEADER", "COSTING", "RESULT_APPROVER", "SALES").contains(role)) throw invalid("未知 nodeRole");
      if (!ids.add(id)) throw invalid("workItemId 不得重复");
      String task = text(item, "taskId", role.startsWith("TECH"), 128);
      workItems.add(new WorkItem(id, role, text(item, "employeeNo", true, 128), task));
    }
    boolean technical = event.equals("TECH_APPROVED") || event.equals("TECH_REJECTED");
    boolean changed = event.equals("TASK_CHANGED");
    boolean result = Set.of("RESULT_RETURNED", "RESULT_APPROVAL_PROGRESS", "PROCESS_COMPLETED").contains(event)
        || (event.equals("FLOW_STATE_CHANGED") && state.equals("RESULT_APPROVAL"));
    boolean reasonRequired = changed || Set.of("TECH_REJECTED", "RESULT_RETURNED",
        "PROCESS_CANCELLED", "PROCESS_WITHDRAWN", "PROCESS_TERMINATED").contains(event);
    String task = text(root, "taskId", technical || changed, 128);
    String submission = text(root, "submissionId", technical, 128);
    String taskState = text(root, "taskState", changed, 32);
    if (changed && (!root.has("submissionId") || !Set.of("EDITABLE", "IN_REVIEW", "RETURNED", "CANCELLED").contains(taskState))) throw invalid("TASK_CHANGED 必须提供 taskState 和 submissionId（未提交为null）");
    if (changed && "IN_REVIEW".equals(taskState) && submission == null) throw invalid("IN_REVIEW 必须提供本次 submissionId");
    if (!technical && !changed && (root.has("taskId") || root.has("submissionId") || root.has("employeeNo") || root.has("taskState"))) throw invalid("本事件不接受技术任务字段");
    if (!result && root.has("resultSubmissionId")) throw invalid("本事件不接受成本提交编号");
    if (technical && (root.has("employeeNo") || root.has("taskState"))) throw invalid("技术审批不接受任务改派字段");
    String expected = switch (event) {
      case "TECH_REJECTED" -> "TECHNICAL";
      case "COSTING_ENTERED" -> "COSTING";
      case "RESULT_RETURNED" -> "RECOSTING";
      case "RESULT_APPROVAL_PROGRESS" -> "RESULT_APPROVAL";
      case "PROCESS_COMPLETED" -> "COMPLETED";
      case "PROCESS_CANCELLED" -> "CANCELLED";
      case "PROCESS_WITHDRAWN" -> "WITHDRAWN";
      case "PROCESS_TERMINATED" -> "TERMINATED";
      default -> null;
    };
    if (expected != null && !expected.equals(state)) throw invalid(event + " 对应 state 必须为 " + expected);
    if (event.equals("TECH_APPROVED") && !Set.of("TECHNICAL", "COSTING").contains(state)) throw invalid("TECH_APPROVED 对应 state 必须为 TECHNICAL 或 COSTING");
    if (changed && !state.equals("TECHNICAL")) throw invalid("TASK_CHANGED 对应 state 必须为 TECHNICAL");
    if (event.equals("FLOW_STATE_CHANGED") && (CLOSED.contains(state) || Set.of("WITHDRAWN", "RECOSTING").contains(state))) throw invalid("退回、撤回及结束流程必须使用对应的专用eventType");
    if (CLOSED.contains(state) && !workItems.isEmpty()) throw invalid("结束状态的 activeWorkItems 必须为空");
    if (state.equals("WITHDRAWN") && workItems.stream().anyMatch(w -> !w.nodeRole().equals("SALES"))) throw invalid("撤回后只能保留营业待办");
    String role = switch (state) {
      case "MATERIAL_REVIEW" -> "MATERIAL";
      case "COSTING", "RECOSTING" -> "COSTING";
      case "RESULT_APPROVAL" -> "RESULT_APPROVER";
      default -> null;
    };
    if (role != null && workItems.stream().noneMatch(w -> w.nodeRole().equals(role))) throw invalid(state + " 缺少有效 " + role + " 待办");
    if (event.equals("TECH_REJECTED") && workItems.stream().noneMatch(w -> "TECHNICIAN".equals(w.nodeRole()) && Objects.equals(task,w.taskId()))) throw invalid("技术退回须提供该任务的新技术员待办");
    return new OaWorkflowNotification(text(root,"requestId",true,128), text(root,"workflowRequestId",true,128), event,
        positive(root,"formVersion"), positive(workflow,"version"), state, List.copyOf(workItems), task,
        submission, text(root,"resultSubmissionId",result,128), taskState,
        text(root,"employeeNo",changed,128), text(root,"reason",reasonRequired,512));
  }
  private static void keys(JsonNode node, Set<String> allowed, String location) {
    if (!node.isObject()) throw invalid(location + " 必须为JSON对象");
    node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw invalid(location + " 包含未定义字段 " + key); });
  }
  private static String text(JsonNode node, String key, boolean required, int max) {
    var value = node.get(key);
    if (value == null || value.isNull()) { if (required) throw invalid(key + " 必填"); return null; }
    if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max || !value.asText().equals(value.asText().trim())) throw invalid(key + " 必须为非空字符串，最多" + max + "字符且不含首尾空格");
    return value.asText();
  }
  private static long positive(JsonNode node, String key) {
    var v = node.path(key);
    if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() <= 0) throw invalid(key + " 必须为正整数");
    return v.longValue();
  }
  private static OaIntegrationException invalid(String message) { return OaIntegrationException.invalid("VALIDATION_FAILED",message); }
}
