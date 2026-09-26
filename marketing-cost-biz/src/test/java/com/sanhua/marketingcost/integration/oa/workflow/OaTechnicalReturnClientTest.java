package com.sanhua.marketingcost.integration.oa.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class OaTechnicalReturnClientTest {
  final OaWorkflowClient workflow = mock(OaWorkflowClient.class);
  final OaTechnicalReturnClient client = new OaTechnicalReturnClient(new ObjectMapper(), workflow);

  OaTechnicalReturnClient.Request request(List<OaTechnicalReturnClient.Target> targets) {
    return new OaTechnicalReturnClient.Request(
        "1137224760702033922",
        "FI-SC-005",
        "00101516",
        targets,
        "https://quote.test/technical-data/workbench?formId=1");
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
