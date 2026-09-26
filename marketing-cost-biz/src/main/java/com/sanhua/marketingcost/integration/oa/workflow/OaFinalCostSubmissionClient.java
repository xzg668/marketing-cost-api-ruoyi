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
  public record Row(String businessType, String sourceRowId, Integer sourceRowIndex, String cost) {}
  private final ObjectMapper json;
  private final OaWorkflowClient workflow;

  public OaFinalCostSubmissionClient(ObjectMapper json, OaWorkflowClient workflow) {
    this.json = json;
    this.workflow = workflow;
  }

  public ObjectNode preview(String requestId, String employeeNo, String processCode, List<Row> rows) {
    if (requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,100}")) {
      throw new IllegalArgumentException("缺少有效的原 OA requestId");
    }
    if (employeeNo == null || !employeeNo.matches("[A-Za-z0-9._-]{1,64}")) {
      throw new IllegalArgumentException("当前报价员未维护有效工号");
    }
    if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("整单没有可提交的成本结果");
    var body = json.createObjectNode();
    body.put("userid", employeeNo);
    body.put("requestId", requestId);
    body.put("remark", "报价系统回写最终成本结果");
    body.putObject("otherParams").put("src", "submit");
    var details = body.putObject("formData").put("module", "workflow").putArray("dataDetails");
    var positions = new HashSet<String>();
    for (var row : rows) {
      String key = costField(processCode, row.businessType());
      long subFormId = subFormId(row.sourceRowId());
      if (row.sourceRowIndex() == null || row.sourceRowIndex() < 1) {
        throw new IllegalArgumentException("产品缺少原 OA 明细行号 rowIndex");
      }
      if (!positions.add(key + ":" + row.sourceRowIndex())) {
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

  public String costField(String processCode, String businessType) {
    if (processCode == null) throw new IllegalArgumentException("缺少 OA 流程类型");
    return switch (processCode) {
      case "FI-SC-005" -> "zcbbhyf";
      case "FI-SC-006" -> "zcb1bhs";
      case "FI-SC-020" -> "bhysfzcbbhs";
      case "FI-SR-005" -> switch (businessType == null ? "" : businessType) {
        case "批量品" -> "cb1";
        case "新品", "衍生品" -> "zcbbhs";
        default -> throw new IllegalArgumentException("FI-SR-005 缺少明确的产品业务类型");
      };
      default -> throw new IllegalArgumentException("未配置该 OA 流程的最终成本字段：" + processCode);
    };
  }

  private long subFormId(String sourceRowId) {
    // 2026-09-26 暂定：subFormId 取 I01 的 rowId；待 OA 确认后只调整此处映射。
    // 原 rowId 仍完整保存在接入数据和提交快照中；LONG 序列化不经过前端 Number。
    try {
      if (sourceRowId == null || !sourceRowId.matches("[0-9]+")) throw new NumberFormatException();
      long id = Long.parseLong(sourceRowId);
      if (id <= 0) throw new NumberFormatException();
      return id;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("原 OA rowId 不能作为 subFormId，请核对 OA 明细标识");
    }
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
