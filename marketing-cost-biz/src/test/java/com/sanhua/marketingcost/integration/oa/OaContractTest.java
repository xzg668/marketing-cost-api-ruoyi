package com.sanhua.marketingcost.integration.oa;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OaContractTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final OaMessageCodec codec = new OaMessageCodec(json);
  static final OaPeer PEER = new OaPeer("OA_TEST", "TW02", Set.of("COMMERCIAL"));

  static ObjectNode envelope(ObjectMapper json, String request, String document) {
    ObjectNode node = json.createObjectNode();
    node.put("schemaVersion", 1).put("sourceSystem", "OA_TEST").put("environment", "TW02")
        .put("requestId", request).put("occurredAt", "2026-09-14T09:00:00+08:00");
    ObjectNode payload = node.putObject("payload");
    payload.put("documentId", document).put("eventId", request).put("externalFlowId", "FLOW-"+document)
        .put("eventType", "TECH_APPROVED").put("sequence", 1).put("taskId", 1).put("submissionId", 1)
        .put("technicalVersionId", 1).put("round", 1).put("operatorExternalId", "oa-user");
    return node;
  }

  @Test void whitespaceAndObjectOrderDoNotCauseDuplicateConflict() {
    var request = envelope(json, "req", "doc");
    var first = codec.decode(request.toString(), PEER, OaMessageCodec.InterfaceType.WORKFLOW_EVENT);
    var second = codec.decode(request.toPrettyString(), PEER, OaMessageCodec.InterfaceType.WORKFLOW_EVENT);
    assertThat(first.hash()).isEqualTo(second.hash());
    assertThat(codec.decode(request.toString(), PEER, OaMessageCodec.InterfaceType.TECH_SUBMISSION).hash()).isNotEqualTo(first.hash());
  }

  @Test void malformedDuplicateTrailingAndEmptyJsonAreRejectedWithoutDetails() {
    for (String raw : new String[]{"", "null", "[]", "{", "{\"x\":1,\"x\":2}", "{} {}"}) {
      assertThatThrownBy(() -> codec.decode(raw, PEER, OaMessageCodec.InterfaceType.WORKFLOW_EVENT))
          .isInstanceOf(OaIntegrationException.class);
    }
  }

  @Test void sourceAndEnvironmentCannotBeSelfAsserted() {
    var request = envelope(json, "req", "doc"); request.put("environment", "PROD");
    assertThatThrownBy(() -> codec.decode(request.toString(), PEER, OaMessageCodec.InterfaceType.WORKFLOW_EVENT))
        .isInstanceOf(OaIntegrationException.class).hasMessageContaining("环境");
  }

  @Test void credentialsAnywhereInPayloadAreRefusedBeforeStorage() {
    var request = envelope(json, "req", "doc");
    ((ObjectNode) request.path("payload")).putArray("extras").addObject().put("access_token", "sensitive");
    assertThatThrownBy(() -> codec.decode(request.toString(), PEER, OaMessageCodec.InterfaceType.WORKFLOW_EVENT))
        .isInstanceOf(OaIntegrationException.class).hasMessageNotContaining("sensitive");
  }

  @Test void quotationNumbersAndHashesRetainDecimalPrecision() {
    var first=codec.readQuotation("{\"n\":123456789012.123456}");
    var second=codec.readQuotation("{\"n\":123456789012.123457}");
    assertThat(first.path("n").decimalValue()).isEqualByComparingTo("123456789012.123456");
    assertThat(codec.canonicalHash(first)).isNotEqualTo(codec.canonicalHash(second));
  }

  @Test void productionModeCannotEnableMockClient() {
    var properties = new OaIntegrationProperties(); properties.setMode(OaIntegrationProperties.Mode.REAL);
    properties.setEnvironment("PROD"); var client = new OaIntegrationProperties.Client();
    client.setSourceSystem("OA"); client.setEnvironment("PROD"); client.setMode(OaIntegrationProperties.Mode.MOCK);
    client.setSecret("test-only-credential-with-32-chars"); client.setBusinessUnits(Set.of("COMMERCIAL"));
    properties.getClients().put("peer", client);
    assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
  }
}
