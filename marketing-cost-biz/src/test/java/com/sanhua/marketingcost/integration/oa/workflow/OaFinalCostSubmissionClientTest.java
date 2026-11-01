package com.sanhua.marketingcost.integration.oa.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class OaFinalCostSubmissionClientTest {
  private final OaWorkflowProperties properties = new OaWorkflowProperties();
  private final ObjectMapper json = new ObjectMapper();
  private final OaFinalCostSubmissionClient client = new OaFinalCostSubmissionClient(json, mock(OaWorkflowClient.class), properties);

  @ParameterizedTest
  @CsvSource({"FI-SC-005,zcbbhyf,hyfzcbbhs,1315730937451335132", "FI-SC-006,zcb1bhs,zcbbhs,1317497487388790985", "FI-SC-020,bhysfzcbbhs,hysfzcbbhs,1315726685492431674"})
  void commercialRowsUseTheConfiguredTableAndOriginalRowIndexes(String process, String field, String withShipping, String tableId) throws Exception {
    properties.getFinalCostSubFormIds().put(process, tableId);
    var body = client.preview("1137224760702033922", "001001", process,
        List.of(new OaFinalCostSubmissionClient.Row(7, "152.503400", "0.7534"), row(3, "0.000000")));
    // 核验真实 JSON 序列化后的 19 位 ID，避免经浮点数而失真。
    var sent = json.readTree(json.writeValueAsBytes(body));
    assertThat(sent.path("userid").asText()).isEqualTo("001001");
    assertThat(sent.path("requestId").asText()).isEqualTo("1137224760702033922");
    assertThat(sent.at("/otherParams/src").asText()).isEqualTo("submit");
    var details = sent.at("/formData/dataDetails");
    assertThat(details.size()).isEqualTo(4);
    for (var detail : details) {
      assertThat(detail.path("dataKey").asText()).isIn(field, withShipping);
      assertThat(detail.path("subFormId").isIntegralNumber()).isTrue();
      assertThat(detail.path("subFormId").asText()).isEqualTo(tableId);
    }
    assertThat(details.get(0).path("dataIndex").intValue()).isEqualTo(7);
    assertThat(details.get(1).path("dataIndex").intValue()).isEqualTo(7);
    assertThat(details.get(2).path("dataIndex").intValue()).isEqualTo(3);
    assertThat(details.get(3).path("dataIndex").intValue()).isEqualTo(3);
    assertThat(details.get(0).path("dataKey").asText()).isEqualTo(field);
    assertThat(details.get(1).path("dataKey").asText()).isEqualTo(withShipping);
    assertThat(details.get(0).path("content").asText()).isEqualTo("152.503400");
    assertThat(details.get(1).path("content").asText()).isEqualTo("153.256800");
    assertThat(details.get(2).path("content").asText()).isEqualTo("0.000000");
    assertThat(details.get(3).path("content").asText()).isEqualTo("0.000000");
  }

  @Test void emptyFreightAndExplicitZeroAddNoChargeWithoutRoundingTheCost() {
    properties.getFinalCostSubFormIds().put("FI-SC-006", "1317497487388790985");
    var body = client.preview("REQ", "001", "FI-SC-006", List.of(
        new OaFinalCostSubmissionClient.Row(1, "292.15974237", null),
        new OaFinalCostSubmissionClient.Row(2, "292.15974237", "0.000000")));
    assertThat(body.at("/formData/dataDetails/1/content").asText()).isEqualTo("292.15974237");
    assertThat(body.at("/formData/dataDetails/3/content").asText()).isEqualTo("292.15974237");
  }

  @Test void invalidOrNegativeFreightCannotBeWrittenToOa() {
    properties.getFinalCostSubFormIds().put("FI-SC-006", "1317497487388790985");
    for (String shipping : new String[]{"-1", "not-money", ""}) {
      assertThatThrownBy(() -> client.preview("REQ", "001", "FI-SC-006",
          List.of(new OaFinalCostSubmissionClient.Row(1, "100", shipping)))).hasMessageContaining("运费");
    }
  }

  @Test void deploymentConfigurationCanChangeTheTableWithoutChangingRows() {
    var source = new MapConfigurationPropertySource();
    source.put("integration.oa-workflow.final-cost-sub-form-ids[FI-SC-005]", "2315730937451335132");
    new Binder(source).bind("integration.oa-workflow", Bindable.ofInstance(properties));
    var body = client.preview("REQ", "001", "FI-SC-005", List.of(row(2, "1")));
    assertThat(body.at("/formData/dataDetails/0/subFormId").asText()).isEqualTo("2315730937451335132");
    assertThat(body.at("/formData/dataDetails/0/dataIndex").intValue()).isEqualTo(2);
  }

  @Test void rejectsMissingAndInvalidTableConfiguration() {
    assertThatThrownBy(() -> client.preview("REQ", "001", "FI-SC-006", List.of(row(1, "1"))))
        .hasMessageContaining("未配置 FI-SC-006").hasMessageContaining("subFormId");
    for (String id : new String[]{"", "table-x", "0", "-1", "1.5", "9223372036854775808"}) {
      properties.getFinalCostSubFormIds().put("FI-SC-006", id);
      assertThatThrownBy(() -> client.preview("REQ", "001", "FI-SC-006", List.of(row(1, "1"))))
          .hasMessageContaining("subFormId");
    }
  }

  @Test void rejectsMissingInvalidAndDuplicateRowIndexes() {
    properties.getFinalCostSubFormIds().put("FI-SC-006", "1317497487388790985");
    for (Integer index : new Integer[]{null, 0, -1}) {
      assertThatThrownBy(() -> client.preview("REQ", "001", "FI-SC-006", List.of(row(index, "1"))))
          .hasMessageContaining("rowIndex");
    }
    assertThatThrownBy(() -> client.preview("REQ", "001", "FI-SC-006", List.of(row(1, "1"), row(1, "2"))))
        .hasMessageContaining("行号重复");
  }

  @Test void rejectsMissingNegativeCostsAndProcessesOutsideCurrentScope() {
    properties.getFinalCostSubFormIds().put("FI-SC-006", "1317497487388790985");
    assertThatThrownBy(() -> client.preview("REQ", "001", "FI-SC-006", List.of(row(1, null)))).hasMessageContaining("成本");
    assertThatThrownBy(() -> client.preview("REQ", "001", "FI-SC-006", List.of(row(1, "-1")))).hasMessageContaining("成本");
    for (String process : List.of("FI-SR-005", "UNKNOWN")) {
      assertThatThrownBy(() -> client.preview("REQ", "001", process, List.of(row(1, "1")))).hasMessageContaining("最终成本字段");
    }
  }

  private OaFinalCostSubmissionClient.Row row(Integer index, String cost) {
    return new OaFinalCostSubmissionClient.Row(index, cost, null);
  }
}
