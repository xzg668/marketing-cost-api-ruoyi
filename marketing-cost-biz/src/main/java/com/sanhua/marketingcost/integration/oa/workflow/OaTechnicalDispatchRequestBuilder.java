package com.sanhua.marketingcost.integration.oa.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataModuleType;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 调试及正式分派共用的 I02 报文构造；调用方负责提供已核实的产品缺口和实际操作人。 */
@Component
public class OaTechnicalDispatchRequestBuilder {
  private final ObjectMapper json;
  private final Validator validator;

  public OaTechnicalDispatchRequestBuilder(ObjectMapper json, Validator validator) {
    this.json = json;
    this.validator = validator;
  }

  public ObjectNode build(OaTechnicalDispatchRequest command, String operatorEmployeeNo) {
    if (command == null) throw new IllegalArgumentException("技术分派请求不能为空");
    var violations = validator.validate(command);
    if (!violations.isEmpty()) {
      throw new IllegalArgumentException(violations.stream()
          .map(item -> item.getPropertyPath() + ": " + item.getMessage()).sorted()
          .collect(Collectors.joining("；")));
    }
    if (!"FI-SC-005".equals(command.processCode())) {
      throw new IllegalArgumentException("当前仅已明确 FI-SC-005 的技术员字段 jsy，其他流程须确认字段后接入");
    }
    String operator = employeeNo(operatorEmployeeNo, "报价员工号");
    String requestId = command.requestId().trim();
    if (!requestId.matches("[A-Za-z0-9._:-]{1,128}")) {
      throw new IllegalArgumentException("原 OA 流程 requestId 格式不正确");
    }

    var rowIds = new HashSet<String>();
    var people = new TreeMap<String, String>();
    Map<String, List<String>> groupedProducts = new LinkedHashMap<>();
    Map<String, Long> productCounts = command.products().stream().collect(Collectors.groupingBy(
        product -> product.productNo().trim(), Collectors.counting()));
    for (var product : command.products()) {
      String rowId = singleLine(product.rowId(), "产品行编号");
      String productNo = singleLine(product.productNo(), "产品编号");
      if (!rowIds.add(rowId)) throw new IllegalArgumentException("同一产品行不能重复分派：" + rowId);
      Map<TechnicalDataModuleType, String> owners = new HashMap<>();
      Map<String, EnumSet<TechnicalDataModuleType>> groups = new TreeMap<>();
      for (var assignment : product.assignments()) {
        String employee = employeeNo(assignment.technicianEmployeeNo(), "技术员工号");
        String name = singleLine(assignment.technicianName(), "技术员姓名");
        String oldName = people.putIfAbsent(employee, name);
        if (oldName != null && !oldName.equals(name)) {
          throw new IllegalArgumentException("同一技术员工号对应的姓名不一致：" + employee);
        }
        var modules = groups.computeIfAbsent(employee, ignored -> EnumSet.noneOf(TechnicalDataModuleType.class));
        for (String code : assignment.moduleTypes()) {
          if (!TechnicalDataModuleType.codes().contains(code)) {
            throw new IllegalArgumentException("不支持的补录模块：" + code);
          }
          var type = TechnicalDataModuleType.valueOf(code);
          String oldOwner = owners.putIfAbsent(type, employee);
          if (oldOwner != null && !oldOwner.equals(employee)) {
            throw new IllegalArgumentException("产品行 " + rowId + " 的" + type.displayName() + "分派给了不同人员");
          }
          modules.add(type);
        }
      }
      List<String> assignments = new ArrayList<>();
      for (var group : groups.entrySet()) {
        String scope = group.getValue().stream().map(TechnicalDataModuleType::displayName)
            .collect(Collectors.joining("、"));
        assignments.add(people.get(group.getKey()) + "（" + group.getKey() + "）补" + scope);
      }
      String rowLabel = productCounts.get(productNo) > 1 ? "（明细行" + rowId + "）" : "";
      groupedProducts.computeIfAbsent(String.join("；", assignments), ignored -> new ArrayList<>())
          .add("产品" + productNo + rowLabel);
    }

    String remark = "技术补录：" + groupedProducts.entrySet().stream()
        .map(group -> String.join("、", group.getValue()) + "：" + group.getKey() + "。")
        .collect(Collectors.joining("\n"));
    return OaTechnicalPeopleRequest.build(json, command.processCode(), requestId, operator, people.keySet(), remark);
  }

  private static String employeeNo(String value, String label) {
    if (value == null || !value.trim().matches("[A-Za-z0-9._-]{1,64}")) {
      throw new IllegalArgumentException(label + "不能为空，须为最多64位的字母、数字、点、下划线或短横线");
    }
    return value.trim();
  }

  private static String singleLine(String value, String label) {
    String result = value.trim();
    if (result.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(label + "不能包含换行或控制字符");
    }
    return result;
  }
}
