package com.sanhua.marketingcost.integration.oa.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.integration.oa.OaInterfaceLog;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataModuleType;
import java.net.URI;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** I05 仅负责退回说明和原生报文；负责人、范围及编辑权限由业务服务校验。 */
@Component
public class OaTechnicalReturnClient {
  public record Target(
      String productNo, List<String> modules, String employeeNo, String name, String reason) {}

  public record Request(
      String requestId,
      String processCode,
      String operatorEmployeeNo,
      List<Target> targets,
      String workbenchUrl) {}

  private final ObjectMapper json;
  private final OaWorkflowClient workflow;

  public OaTechnicalReturnClient(ObjectMapper json, OaWorkflowClient workflow) {
    this.json = json;
    this.workflow = workflow;
  }

  public ObjectNode preview(Request request) {
    if (request == null || request.targets() == null || request.targets().isEmpty()) {
      throw new IllegalArgumentException("请选择需要退回的产品和板块");
    }
    String url = request.workbenchUrl();
    try {
      var uri = URI.create(url);
      if (!List.of("http", "https").contains(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null) throw new IllegalArgumentException();
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("补录工作台地址无效");
    }
    var employees = new TreeSet<String>();
    String remark =
        "退回修改："
            + request.targets().stream()
                .map(
                    target -> {
                      if (target.modules() == null
                          || target.modules().isEmpty()
                          || !TechnicalDataModuleType.codes().containsAll(target.modules())) {
                        throw new IllegalArgumentException("退回板块无效");
                      }
                      employees.add(line(target.employeeNo(), "技术员工号"));
                      String modules =
                          target.modules().stream()
                              .distinct()
                              .sorted(
                                  java.util.Comparator.comparingInt(
                                      TechnicalDataModuleType::orderOf))
                              .map(code -> TechnicalDataModuleType.valueOf(code).displayName())
                              .collect(Collectors.joining("、"));
                      return "产品"
                          + line(target.productNo(), "产品")
                          + " "
                          + modules
                          + "（"
                          + line(target.name(), "负责人")
                          + "）："
                          + line(target.reason(), "退回原因");
                    })
                .collect(Collectors.joining("；"))
            + "。\n修改地址："
            + url;
    OaWorkflowClient.validateRemark(remark);
    return OaTechnicalPeopleRequest.build(
        json,
        request.processCode(),
        request.requestId(),
        request.operatorEmployeeNo(),
        employees,
        remark);
  }

  public OaWorkflowResult submit(Request request) {
    var body = preview(request);
    try (var call = OaInterfaceLog.start("I05_TECHNICAL_RETURN")) {
      call.business(body);
      var result = workflow.submit(body);
      call.result(result.status().name(), result.httpStatus(), result.errorCode());
      return result;
    }
  }

  private static String line(String value, String label) {
    if (value == null || value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(label + "不能为空或包含控制字符");
    }
    return value.trim();
  }
}
