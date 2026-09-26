package com.sanhua.marketingcost.integration.oa.workflow;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OaTechnicalDispatchRequestBuilderTest {
  private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();
  private final ObjectMapper json = new ObjectMapper();
  private final OaTechnicalDispatchRequestBuilder builder = new OaTechnicalDispatchRequestBuilder(json, VALIDATION.getValidator());

  @AfterAll static void close() { VALIDATION.close(); }

  @Test void screenshotScenarioProducesNativeContractAndActualSalaryScope() throws Exception {
    var command = command(product("ROW-1", "1053900000078", assignment("12211470", "丁云龙", "SALARY")));
    assertThat(builder.build(command, "00100203")).isEqualTo(json.readTree("""
        {"userid":"00100203","requestId":"1137224760702033922",
         "remark":"技术补录：产品1053900000078：丁云龙（12211470）补工资。",
         "otherParams":{"src":"submit"},"formData":{"module":"workflow","dataDetails":[
           {"dataKey":"jsy","dataOptions":[{"optionId":"12211470","type":"resource"}]}]}}
        """));
  }

  @Test void multipleProductsAndPeopleKeepEachScopeAndDeduplicateOaPeople() {
    var command = command(
        product("1", "A", assignment("0002", "乙", "SALARY", "PACKAGE", "SALARY"), assignment("0001", "甲", "PRICE")),
        product("2", "B", assignment("0002", "乙", "NET_LOSS")));
    var body = builder.build(command, "0009");
    assertThat(body.path("remark").asText()).isEqualTo("技术补录：产品A：甲（0001）补价格；乙（0002）补包装、工资。\n"
        + "产品B：乙（0002）补净损失率。");
    assertThat(body.at("/formData/dataDetails/0/dataOptions")).hasSize(2);
    assertThat(body.at("/formData/dataDetails/0/dataOptions/0/optionId").asText()).isEqualTo("0001");
  }

  @Test void repeatedProductNumbersAreDistinguishedBySourceRow() {
    var body = builder.build(command(product("1", "A", assignment("001", "甲", "SALARY")),
        product("2", "A", assignment("001", "甲", "PACKAGE"))), "0009");
    assertThat(body.path("remark").asText()).contains("产品A（明细行1）", "产品A（明细行2）");
  }

  @Test void sameAssignmentsShareOneShortDescriptionWithAllProducts() {
    var assignments = new OaTechnicalDispatchRequest.Assignment[] {
        assignment("89092501", "验收技术员甲", "SALARY", "NET_LOSS"),
        assignment("89092502", "验收技术员乙", "PACKAGE")
    };
    var body = builder.build(command(product("1", "SUB25-a823d2f7-1", assignments),
        product("2", "SUB25-a823d2f7-2", assignments)), "89092500");
    String remark = body.path("remark").asText()
        + "\n补录地址：http://localhost:5173/collaboration/technical-data/forms/991305";
    assertThat(remark).contains("产品SUB25-a823d2f7-1、产品SUB25-a823d2f7-2", "补工资、净损失率", "补包装");
    assertThatCode(() -> OaWorkflowClient.validateRemark(remark)).doesNotThrowAnyException();
  }

  @Test void oneModuleCannotHaveTwoOwners() {
    assertThatThrownBy(() -> builder.build(command(product("1", "A",
        assignment("001", "甲", "SALARY"), assignment("002", "乙", "SALARY"))), "0009"))
        .hasMessageContaining("工资分派给了不同人员");
  }

  @Test void repeatedSourceRowIsRejected() {
    var row = product("1", "A", assignment("001", "甲", "SALARY"));
    assertThatThrownBy(() -> builder.build(command(row, row), "0009")).hasMessageContaining("产品行不能重复");
  }

  @Test void inconsistentNameForSameEmployeeIsRejected() {
    assertThatThrownBy(() -> builder.build(command(product("1", "A", assignment("001", "甲", "SALARY")),
        product("2", "B", assignment("001", "乙", "SALARY"))), "0009")).hasMessageContaining("姓名不一致");
  }

  @Test void unconfirmedProcessKeyCannotFallBackToJsy() {
    var request = new OaTechnicalDispatchRequest("123", "FI-SC-006", null,
        command(product("1", "A", assignment("001", "甲", "SALARY"))).products());
    assertThatThrownBy(() -> builder.build(request, "0009")).hasMessageContaining("其他流程须确认字段");
  }

  @ParameterizedTest @ValueSource(strings = {"", " ", "001\n002", "12&34", "中文"})
  void operatorNumberMustBeExplicitAndWellFormed(String operator) {
    assertThatThrownBy(() -> builder.build(command(product("1", "A", assignment("001", "甲", "SALARY"))), operator))
        .hasMessageContaining("报价员工号");
  }

  @ParameterizedTest @ValueSource(strings = {"ALL", "salary", "UNKNOWN"})
  void unknownModuleCannotBecomeARequirement(String module) {
    assertThatThrownBy(() -> builder.build(command(product("1", "A", assignment("001", "甲", module))), "0009"))
        .hasMessageContaining("不支持的补录模块");
  }

  @Test void emptyScopeAndNullRowsAreRejected() {
    assertThatThrownBy(() -> builder.build(command(), "0009")).hasMessageContaining("products");
    assertThatThrownBy(() -> builder.build(command(product("1", "A", assignment("001", "甲"))), "0009"))
        .hasMessageContaining("moduleTypes");
    assertThatThrownBy(() -> builder.build(new OaTechnicalDispatchRequest("1", "FI-SC-005", null,
        java.util.Arrays.asList((OaTechnicalDispatchRequest.Product) null)), "0009")).hasMessageContaining("products");
  }

  @Test void unknownFieldsAreRejectedAtAllLevels() {
    var node = json.valueToTree(command(product("1", "A", assignment("001", "甲", "SALARY"))));
    for (String pointer : List.of("", "/products/0", "/products/0/assignments/0")) {
      var copy = node.deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) copy.at(pointer)).put("unexpected", "value");
      assertThatThrownBy(() -> json.treeToValue(copy, OaTechnicalDispatchRequest.class)).hasMessageContaining("未定义字段");
    }
  }

  @Test void controlCharactersCannotAddMisleadingRemarkLines() {
    assertThatThrownBy(() -> builder.build(command(product("1", "A\nB", assignment("001", "甲", "SALARY"))), "0009"))
        .hasMessageContaining("控制字符");
  }

  static OaTechnicalDispatchRequest command(OaTechnicalDispatchRequest.Product... products) {
    return new OaTechnicalDispatchRequest("1137224760702033922", "FI-SC-005", null, List.of(products));
  }
  static OaTechnicalDispatchRequest.Product product(String row, String product, OaTechnicalDispatchRequest.Assignment... assignments) {
    return new OaTechnicalDispatchRequest.Product(row, product, List.of(assignments));
  }
  static OaTechnicalDispatchRequest.Assignment assignment(String employee, String name, String... modules) {
    return new OaTechnicalDispatchRequest.Assignment(employee, name, List.of(modules));
  }
}
