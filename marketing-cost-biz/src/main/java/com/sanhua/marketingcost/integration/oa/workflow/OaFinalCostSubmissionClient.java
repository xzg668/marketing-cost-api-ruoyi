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
  public record Row(Integer sourceRowIndex, String cost, String shippingFee) {}
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
    String withShippingKey = costWithShippingField(processCode);
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
      BigDecimal cost = amount(row.cost(), "产品核算成本");
      BigDecimal shipping = row.shippingFee() == null ? BigDecimal.ZERO : amount(row.shippingFee(), "产品运费");
      details.addObject().put("dataKey", key).put("content", row.cost())
          .put("subFormId", subFormId).put("dataIndex", row.sourceRowIndex());
      details.addObject().put("dataKey", withShippingKey).put("content", cost.add(shipping).toPlainString())
          .put("subFormId", subFormId).put("dataIndex", row.sourceRowIndex());
    }
    return body;
  }

  private String costWithShippingField(String processCode) {
    return switch (processCode) {
      case "FI-SC-005" -> "hyfzcbbhs";
      case "FI-SC-006" -> "zcbbhs";
      case "FI-SC-020" -> "hysfzcbbhs";
      default -> throw new IllegalArgumentException("未配置该 OA 流程的含运费成本字段：" + processCode);
    };
  }

  private static BigDecimal amount(String value, String label) {
    if (value == null) throw new IllegalArgumentException(label + "缺失");
    final BigDecimal amount;
    try { amount = new BigDecimal(value); }
    catch (NumberFormatException invalid) { throw new IllegalArgumentException(label + "不是有效金额"); }
    if (amount.signum() < 0) throw new IllegalArgumentException(label + "不能为负数");
    return amount;
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
      var positions = new HashSet<Integer>();
      body.at("/formData/dataDetails").forEach(field -> positions.add(field.path("dataIndex").asInt()));
      call.business(body).field("productCount", positions.size());
      try {
        var result = workflow.submit(body);
        call.result(result.status().name(), result.httpStatus(), result.errorCode());
        return result;
      } catch (RuntimeException error) { call.failure(error); throw error; }
    }
  }
}
