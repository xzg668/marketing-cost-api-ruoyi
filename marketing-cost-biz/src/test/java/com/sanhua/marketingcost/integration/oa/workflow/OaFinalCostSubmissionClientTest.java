package com.sanhua.marketingcost.integration.oa.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OaFinalCostSubmissionClientTest {
  final OaFinalCostSubmissionClient client = new OaFinalCostSubmissionClient(new ObjectMapper(), mock(OaWorkflowClient.class));
  @ParameterizedTest
  @CsvSource({"FI-SC-005,新品,zcbbhyf", "FI-SC-006,批量品,zcb1bhs", "FI-SC-020,批量品,bhysfzcbbhs",
      "FI-SR-005,批量品,cb1", "FI-SR-005,新品,zcbbhs", "FI-SR-005,衍生品,zcbbhs"})
  void mapsAllBusinessTypes(String process, String business, String key) {
    assertThat(client.costField(process, business)).isEqualTo(key);
  }
  @Test void keepsOriginalIdentityPositionAndDecimalPrecisionInOneRequest() {
    var body = client.preview("1137224760702033922", "001001", "FI-SC-006", List.of(
        row("1137224760702033921", 7, "152.503400"), row("1137224760702033922", 3, "0.000000")));
    assertThat(body.path("userid").asText()).isEqualTo("001001");
    assertThat(body.path("requestId").asText()).isEqualTo("1137224760702033922");
    assertThat(body.at("/otherParams/src").asText()).isEqualTo("submit");
    assertThat(body.at("/formData/dataDetails").size()).isEqualTo(2);
    assertThat(body.at("/formData/dataDetails/0/subFormId").longValue()).isEqualTo(1137224760702033921L);
    assertThat(body.at("/formData/dataDetails/0/dataIndex").intValue()).isEqualTo(7);
    assertThat(body.at("/formData/dataDetails/0/content").asText()).isEqualTo("152.503400");
  }
  @Test void rejectsMissingOrNonNumericSourceRowAndDuplicatePositions() {
    for (String id : new String[]{null,"row-x","0","9223372036854775808"}) {
      assertThatThrownBy(() -> client.preview("REQ", "001", "FI-SC-006", List.of(row(id, 1, "1")))).hasMessageContaining("subFormId");
    }
    assertThatThrownBy(() -> client.preview("REQ", "001", "FI-SC-006", List.of(row("10",1,"1"),row("20",1,"2"))))
        .hasMessageContaining("行号重复");
  }
  @Test void rejectsMissingNegativeCostsAndUnknownProcesses() {
    assertThatThrownBy(() -> client.preview("REQ","001","FI-SC-006",List.of(row("1",1,null)))).hasMessageContaining("成本");
    assertThatThrownBy(() -> client.preview("REQ","001","FI-SC-006",List.of(row("1",1,"-1")))).hasMessageContaining("成本");
    assertThatThrownBy(() -> client.costField("FI-SR-005",null)).hasMessageContaining("业务类型");
    assertThatThrownBy(() -> client.costField("UNKNOWN","新品")).hasMessageContaining("最终成本字段");
  }
  OaFinalCostSubmissionClient.Row row(String id, int index, String cost) {
    return new OaFinalCostSubmissionClient.Row("批量品",id,index,cost);
  }
}
