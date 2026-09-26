package com.sanhua.marketingcost.integration.oa.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;
import java.util.TreeSet;

/** I02 与 I05 共用的 OA 人员表单协议；业务说明由各自场景生成。 */
public final class OaTechnicalPeopleRequest {
  private OaTechnicalPeopleRequest() {}

  public static ObjectNode build(
      ObjectMapper json,
      String processCode,
      String requestId,
      String operator,
      Collection<String> employees,
      String remark) {
    if (!"FI-SC-005".equals(processCode)) {
      throw new IllegalArgumentException("当前仅已明确 FI-SC-005 的技术员字段 jsy，其他流程须确认字段后接入");
    }
    if (requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,128}")) {
      throw new IllegalArgumentException("原 OA 流程 requestId 格式不正确");
    }
    if (employees == null
        || employees.isEmpty()
        || employees.stream().anyMatch(java.util.Objects::isNull))
      throw new IllegalArgumentException("技术员不能为空");
    var body =
        json.createObjectNode()
            .put("userid", employeeNo(operator))
            .put("requestId", requestId)
            .put("remark", remark);
    body.putObject("otherParams").put("src", "submit");
    var options =
        body.putObject("formData")
            .put("module", "workflow")
            .putArray("dataDetails")
            .addObject()
            .put("dataKey", "jsy")
            .putArray("dataOptions");
    new TreeSet<>(employees)
        .forEach(
            employee ->
                options.addObject().put("optionId", employeeNo(employee)).put("type", "resource"));
    return body;
  }

  private static String employeeNo(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) {
      throw new IllegalArgumentException("OA 工号不能为空且必须为有效的文本编号");
    }
    return value;
  }
}
