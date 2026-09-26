package com.sanhua.marketingcost.integration.oa.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import com.sanhua.marketingcost.integration.oa.OaInterfaceLog;
import org.springframework.stereotype.Component;

/** I03 技术资料提交的 OA 原生协议；任务校验、冻结及人员权限由调用方业务服务负责。 */
@Component
public class OaTechnicalSubmissionClient {
  // 暂按双方讨论使用“报价系统地址”；OA 确认实际字段名后，只修改这一处。
  private static final String REVIEW_URL_DATA_KEY = "bjxtdz";

  private final ObjectMapper json;
  private final OaWorkflowClient workflow;

  public OaTechnicalSubmissionClient(ObjectMapper json, OaWorkflowClient workflow) {
    this.json = json;
    this.workflow = workflow;
  }

  /** requestId 为 OA 推送的原流程编号；remark 接收既有生成器产出的本次实际补录说明。 */
  public record Request(String requestId, String technicianEmployeeNo, String remark, String reviewUrl) {}

  /** 只构造报文，供核对；不申请 token，不发送 OA。 */
  public ObjectNode preview(Request request) {
    if (request == null) throw new IllegalArgumentException("技术资料提交请求不能为空");
    String requestId = required(request.requestId(), "原 OA 流程 requestId").strip();
    String employeeNo = required(request.technicianEmployeeNo(), "技术员工号").strip();
    if (!requestId.matches("[A-Za-z0-9._:-]{1,100}")) {
      throw new IllegalArgumentException("原 OA 流程 requestId 格式不正确，最长 100 字符");
    }
    if (!employeeNo.matches("[A-Za-z0-9._-]{1,64}")) {
      throw new IllegalArgumentException("技术员工号格式不正确，最长 64 字符");
    }
    String remark = required(request.remark(), "本次实际补录说明");
    OaWorkflowClient.validateRemark(remark);
    String reviewUrl = reviewUrl(request.reviewUrl());

    ObjectNode body = json.createObjectNode();
    body.put("userid", employeeNo);
    body.put("requestId", requestId);
    body.put("remark", remark);
    body.putObject("otherParams").put("src", "submit");
    ObjectNode form = body.putObject("formData").put("module", "workflow");
    form.putArray("dataDetails").addObject()
        .put("dataKey", REVIEW_URL_DATA_KEY).put("content", reviewUrl);
    return body;
  }

  /** 复用公共 token、回执解析及单次发送规则；超时不自动再次提交。 */
  public OaWorkflowResult submit(Request request) {
    try (var call = OaInterfaceLog.start("I03_TECHNICAL_SUBMISSION")) {
      if (request != null) call.field("requestId", request.requestId()).field("employeeNo", request.technicianEmployeeNo());
      try {
        var result = workflow.submit(preview(request));
        call.result(result.status().name(), result.httpStatus(), result.errorCode());
        return result;
      } catch (RuntimeException exception) {
        call.failure(exception);
        throw exception;
      }
    }
  }

  private static String required(String value, String label) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
    return value;
  }

  private static String reviewUrl(String value) {
    String address = required(value, "本次资料查看地址").strip();
    try {
      URI uri = URI.create(address);
      // 保存稳定的资料链接；OA 打开链接时的身份凭证不属于表单地址。
      if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException();
      }
      return address;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("本次资料查看地址须为完整 HTTP(S) 地址，且不含账号密码或片段凭证");
    }
  }
}
