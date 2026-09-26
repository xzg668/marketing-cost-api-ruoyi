package com.sanhua.marketingcost.integration.oa.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.integration.oa.OaInterfaceLog;
import org.springframework.stereotype.Component;

/** I06 黄色报文：原流程编号、报价员工号、提交资料节点；公共鉴权及回执解析复用原生通道。 */
@Component
public class OaMaterialConfirmationClient {
  private final ObjectMapper json;
  private final OaWorkflowClient workflow;

  public OaMaterialConfirmationClient(ObjectMapper json, OaWorkflowClient workflow) {
    this.json = json;
    this.workflow = workflow;
  }

  public ObjectNode preview(String requestId, String employeeNo, boolean supplemented) {
    if (requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,100}")) {
      throw new IllegalArgumentException("I06 原 OA requestId 格式不正确");
    }
    if (employeeNo == null || !employeeNo.matches("[A-Za-z0-9._-]{1,64}")) {
      throw new IllegalArgumentException("当前报价员未维护有效工号");
    }
    ObjectNode body = json.createObjectNode();
    body.put("userid", employeeNo);
    body.put("requestId", requestId);
    body.put("remark", supplemented ? "补录资料已确认，同意继续核算" : "无需技术员补录，直接提交");
    body.putObject("otherParams").put("src", "submit");
    body.putObject("formData").put("module", "workflow").putArray("dataDetails");
    return body;
  }

  public OaWorkflowResult submit(ObjectNode body) {
    try (var call = OaInterfaceLog.start("I06_MATERIAL_CONFIRMATION")) {
      call.business(body);
      try {
        var result = workflow.submit(body);
        call.result(result.status().name(), result.httpStatus(), result.errorCode());
        return result;
      } catch (RuntimeException exception) {
        call.failure(exception);
        throw exception;
      }
    }
  }
}
