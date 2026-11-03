package com.sanhua.marketingcost.integration.oa.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OaTechnicalReturnClientTest {
  final OaWorkflowClient workflow = mock(OaWorkflowClient.class);
  final OaWorkflowProperties properties = new OaWorkflowProperties();
  final OaTechnicalReturnClient client = new OaTechnicalReturnClient(new ObjectMapper(), workflow, properties);

  @Test
  void rejectionUsesExactOaFieldAndPreservesLeadingZeros() {
    when(workflow.reject(any())).thenReturn(new OaWorkflowResult("test", OaWorkflowResult.Status.SUCCESS,
        200, "0", "成功", "00123", null, 1));
    var body = client.previewRejection("00123", "0001", "000987654321");
    assertThat(body.path("RejectToNodeid").asText()).isEqualTo("000987654321");
    assertThat(body.has("rejectNodeid")).isFalse();
    client.reject(body);
    verify(workflow).reject(eq(body));
  }

  @Test
  void savedLegacyBatchIsConvertedOnlyForDelivery() {
    when(workflow.reject(any())).thenReturn(new OaWorkflowResult("test", OaWorkflowResult.Status.SUCCESS,
        200, "0", "成功", "00123", null, 1));
    var saved = new ObjectMapper().createObjectNode().put("userid", "0001")
        .put("requestId", "00123").put("RejectToType", "0").put("rejectNodeid", "000987654321");
    var original = saved.deepCopy();
    client.reject(saved);
    verify(workflow).reject(argThat(body -> body.path("RejectToNodeid").asText().equals("000987654321")
        && !body.has("rejectNodeid") && body.path("requestId").asText().equals("00123")));
    assertThat(saved).isEqualTo(original);
  }

  @Test
  void conflictingSavedNodesCannotChooseAnotherReturnTarget() {
    var body = client.previewRejection("00123", "0001", "NODE1").put("rejectNodeid", "NODE2");
    assertThatThrownBy(() -> client.reject(body)).hasMessageContaining("节点字段冲突");
    verifyNoInteractions(workflow);
  }

  OaTechnicalReturnClient.Request request(List<OaTechnicalReturnClient.Target> targets) {
    return new OaTechnicalReturnClient.Request(
        "1137224760702033922",
        "FI-SC-005",
        "00101516",
        targets,
        "https://quote.test/technical-data/workbench?formId=1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"FI-SC-006", "FI-SC-020"})
  void returningUsesTheSameConfiguredPeopleFieldAsDispatch(String process) {
    var original = request(List.of(new OaTechnicalReturnClient.Target(
        "P1", List.of("NET_LOSS"), "001234", "张三", "调整净损失率")));
    var command = new OaTechnicalReturnClient.Request(original.requestId(), process,
        original.operatorEmployeeNo(), original.targets(), original.workbenchUrl());
    assertThatThrownBy(() -> client.preview(command)).hasMessageContaining("请先确认OA字段");
    properties.getTechnicalPeopleDataKeys().put(process, "SIM_" + process.replace('-', '_') + "_JSY");
    var body = client.preview(command);
    assertThat(body.at("/formData/dataDetails/0/dataKey").asText())
        .isEqualTo(properties.requireTechnicalPeopleDataKey(process));
    assertThat(body.at("/formData/dataDetails/0/dataOptions/0/optionId").asText()).isEqualTo("001234");
    assertThat(body.path("remark").asText()).contains("退回修改", "净损失率", "调整净损失率");
    verifyNoInteractions(workflow);
  }

  @Test
  void sameTechnicianIsDeduplicatedAndRemarkContainsSelectedContentAndUrl() {
    var body =
        client.preview(
            request(
                List.of(
                    new OaTechnicalReturnClient.Target(
                        "P1", List.of("SALARY"), "001234", "张三", "工资改为2元"),
                    new OaTechnicalReturnClient.Target(
                        "P2", List.of("PACKAGE"), "001234", "张三", "核实包装用量"))));
    assertThat(body.path("userid").asText()).isEqualTo("00101516");
    assertThat(body.at("/otherParams/src").asText()).isEqualTo("submit");
    assertThat(body.at("/formData/dataDetails/0/dataKey").asText()).isEqualTo("jsy");
    assertThat(body.at("/formData/dataDetails/0/dataOptions")).hasSize(1);
    assertThat(body.at("/formData/dataDetails/0/dataOptions/0/optionId").asText())
        .isEqualTo("001234");
    assertThat(body.path("remark").asText())
        .contains("产品P1", "工资改为2元", "产品P2", "核实包装用量", "https://quote.test")
        .doesNotContain("净损失率");
    verifyNoInteractions(workflow);
  }

  @Test
  void validationCannotSilentlyTruncateRequiredReturnDetails() {
    assertThatThrownBy(
            () ->
                client.preview(
                    request(
                        List.of(
                            new OaTechnicalReturnClient.Target(
                                "P1", List.of("SALARY"), "001234", "张三", "修".repeat(201))))))
        .hasMessageContaining("200");
    assertThatThrownBy(
            () ->
                client.preview(
                    request(
                        List.of(
                            new OaTechnicalReturnClient.Target(
                                "P1", List.of("INVALID"), "001234", "张三", "调整")))))
        .hasMessageContaining("板块");
    verifyNoInteractions(workflow);
  }
}
