package com.sanhua.marketingcost.service.oa;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.integration.oa.workflow.OaTechnicalDispatchRequest;
import com.sanhua.marketingcost.integration.oa.workflow.OaTechnicalDispatchRequestBuilder;
import com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowClient;
import com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult;
import com.sanhua.marketingcost.service.SysUserService;
import java.util.Map;
import com.sanhua.marketingcost.integration.oa.OaInterfaceLog;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** 独立联调只读取操作人身份；不创建报价任务，不更新产品、分工或审批版本。 */
@Service
public class OaTechnicalDispatchDebugService {
  private final SysUserService users;
  private final OaTechnicalDispatchRequestBuilder builder;
  private final OaWorkflowClient client;

  public OaTechnicalDispatchDebugService(SysUserService users,
      OaTechnicalDispatchRequestBuilder builder, OaWorkflowClient client) {
    this.users = users;
    this.builder = builder;
    this.client = client;
  }

  public Preview preview(OaTechnicalDispatchRequest request) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()
        || authentication.getDetails() instanceof Map<?, ?> details && details.get("technicalDataTaskId") != null) {
      throw new AccessDeniedException("须使用系统管理员的正常登录会话");
    }
    var user = users.findByUsername(authentication.getName());
    if (user == null || !"0".equals(user.getStatus()) || !"0".equals(user.getDelFlag())) {
      throw new AccessDeniedException("当前账号不存在或已停用");
    }
    if (request == null) throw new IllegalArgumentException("技术分派请求不能为空");
    boolean explicitOperator = request.operatorEmployeeNo() != null;
    String operator = explicitOperator ? request.operatorEmployeeNo() : user.getEmployeeNo();
    if (operator == null || operator.isBlank()) {
      throw new IllegalArgumentException(explicitOperator ? "调试指定的报价员工号不能为空"
          : "当前账号未维护工号，请先完善，或在调试请求中明确指定报价员工号");
    }
    return new Preview("POST", OaWorkflowClient.SUBMIT_PATH,
        Map.of("access_token", "[由后台获取]", "userType", "JOB_NUM"),
        builder.build(request, operator), explicitOperator ? "DEBUG_INPUT" : "CURRENT_USER", user.getUserId());
  }

  public Sent send(OaTechnicalDispatchRequest request) {
    try (var call = OaInterfaceLog.start("I02_TECHNICAL_DISPATCH")) {
      if (request != null) call.field("requestId", request.requestId()).field("processCode", request.processCode());
      try {
        Preview preview = preview(request);
        call.field("userId", preview.actorUserId()).business(preview.body());
        var result = client.submit(preview.body());
        call.result(result.status().name(), result.httpStatus(), result.errorCode());
        return new Sent(preview, result);
      } catch (RuntimeException exception) { call.failure(exception); throw exception; }
    }
  }

  public record Preview(String method, String path, Map<String, String> query,
      ObjectNode body, String operatorSource, Long actorUserId) {}
  public record Sent(Preview request, OaWorkflowResult result) {}
}
