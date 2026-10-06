package com.sanhua.marketingcost.integration.oa.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.integration.oa.OaInterfaceLog;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/** I07 黄色报文：整单成本写入与核算节点提交只发送一次。 */
@Component
public class OaFinalCostSubmissionClient {
  public record Row(Integer sourceRowIndex, String cost) {}
  private final ObjectMapper json;
  private final OaWorkflowClient workflow;
  private final OaWorkflowProperties properties;

  public OaFinalCostSubmissionClient(ObjectMapper json, OaWorkflowClient workflow, OaWorkflowProperties properties) {
    this.json = json;
    this.workflow = workflow;
    this.properties = properties;
  }

  public ObjectNode preview(String requestId, String employeeNo, String processCode, List<Row> rows) {
    if (requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,100}")) {
      throw new IllegalArgumentException("缺少有效的原 OA requestId");
    }
    if (employeeNo == null || !employeeNo.matches("[A-Za-z0-9._-]{1,64}")) {
      throw new IllegalArgumentException("当前报价员未维护有效工号");
    }
    if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("整单没有可提交的成本结果");
    String key = costField(processCode);
    long subFormId = properties.requireFinalCostSubFormId(processCode);
    var body = json.createObjectNode();
    body.put("userid", employeeNo);
    body.put("requestId", requestId);
    body.put("remark", "报价系统回写最终成本结果");
    body.putObject("otherParams").put("src", "submit");
    var details = body.putObject("formData").put("module", "workflow").putArray("dataDetails");
    var positions = new HashSet<Integer>();
    for (var row : rows) {
      if (row.sourceRowIndex() == null || row.sourceRowIndex() < 1) {
        throw new IllegalArgumentException("产品缺少原 OA 明细行号 rowIndex");
      }
      if (!positions.add(row.sourceRowIndex())) {
        throw new IllegalArgumentException("原 OA 明细行号重复，不能确定成本写入位置");
      }
      if (row.cost() == null || new BigDecimal(row.cost()).signum() < 0) {
        throw new IllegalArgumentException("产品核算成本缺失或为负数");
      }
      details.addObject().put("dataKey", key).put("content", row.cost())
          .put("subFormId", subFormId).put("dataIndex", row.sourceRowIndex());
    }
    return body;
  }

  public String costField(String processCode) {
    if (processCode == null) throw new IllegalArgumentException("缺少 OA 流程类型");
    return switch (processCode) {
      case "FI-SC-005" -> "zcbbhyf";
      case "FI-SC-006" -> "zcb1bhs";
      case "FI-SC-020" -> "bhysfzcbbhs";
      default -> throw new IllegalArgumentException("未配置该 OA 流程的最终成本字段：" + processCode);
    };
  }

  public OaWorkflowResult submit(ObjectNode body) {
    try (var call = OaInterfaceLog.start("I07_FINAL_COST_SUBMISSION")) {
      call.business(body).field("productCount", body.at("/formData/dataDetails").size());
      try {
        var result = workflow.submit(body);
        call.result(result.status().name(), result.httpStatus(), result.errorCode());
        return result;
      } catch (RuntimeException error) { call.failure(error); throw error; }
    }
  }
}
