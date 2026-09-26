package com.sanhua.marketingcost.integration.oa;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** I04/I08：requestId 始终是 I01 的流程 ID，employeeNos 始终指技术员。 */
public record OaWorkflowNotification(String requestId, String eventType, List<String> employeeNos,
    String reason) {
  public boolean technical() { return Set.of("TECHNICAL", "TECH_APPROVED").contains(eventType); }

  public static OaWorkflowNotification parse(JsonNode root) {
    if (!root.isObject()) throw invalid("请求必须为 JSON 对象");
    var allowed = Set.of("requestId", "eventType", "employeeNos", "reason");
    root.fieldNames().forEachRemaining(key -> {
      if (!allowed.contains(key)) throw invalid("请求包含未定义字段：" + key);
    });
    String requestId = text(root, "requestId", true, 128);
    String type = text(root, "eventType", true, 32);
    if (!Set.of("TECHNICAL", "TECH_APPROVED", "COSTING", "COMPLETED").contains(type)) {
      throw invalid("未知 eventType：" + type);
    }
    boolean technical = Set.of("TECHNICAL", "TECH_APPROVED").contains(type);
    var numbers = new TreeSet<String>();
    var employees = root.get("employeeNos");
    if (technical && (employees == null || !employees.isArray() || employees.isEmpty())) {
      throw invalid(type + " 必须提供非空的技术员工号数组 employeeNos");
    }
    if (employees != null && !employees.isNull()) {
      if (!employees.isArray() || employees.size() > 1000) throw invalid("employeeNos 必须为数组，最多 1000 项");
      for (var employee : employees) {
        if (!employee.isTextual() || employee.asText().isBlank() || employee.asText().length() > 128
            || !employee.asText().equals(employee.asText().trim())) {
          throw invalid("技术员工号必须为非空字符串，请保留前导零");
        }
        if (!numbers.add(employee.asText())) throw invalid("employeeNos 中的工号不得重复");
      }
      if (!technical && !numbers.isEmpty()) throw invalid(type + " 按整单处理，不接受技术员工号");
    }
    return new OaWorkflowNotification(requestId, type, List.copyOf(numbers),
        text(root, "reason", Set.of("TECHNICAL", "COSTING").contains(type), 512));
  }

  private static String text(JsonNode root, String field, boolean required, int max) {
    var value = root.get(field);
    if (value == null || value.isNull() || value.isTextual() && value.asText().isBlank()) {
      if (required) throw invalid(field + " 必填");
      return null;
    }
    if (!value.isTextual() || value.asText().length() > max || !value.asText().equals(value.asText().trim())) {
      throw invalid(field + " 必须为字符串，最多 " + max + " 字符且不含首尾空格");
    }
    return value.asText();
  }
  private static OaIntegrationException invalid(String message) {
    return OaIntegrationException.invalid("VALIDATION_FAILED", message);
  }
}
