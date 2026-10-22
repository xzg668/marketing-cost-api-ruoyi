package com.sanhua.marketingcost.integration.drawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DrawingVerificationCodecTest {
  private final ObjectMapper json = new ObjectMapper();
  private final DrawingVerificationCodec codec = new DrawingVerificationCodec(json);

  @Test void preservesAllGroupsDuplicateRowsAndNineOriginalValues() throws Exception {
    String raw = DrawingVerificationFixtures.initial("task-01").toPrettyString();
    var payload = codec.parse(raw);
    assertThat(payload.rawJson()).isEqualTo(raw);
    var summary = payload.summary();
    assertThat(summary.data().keySet()).containsExactly("DRAW-01", "DRAW-02");
    var rows = summary.data().get("DRAW-01").params().items();
    assertThat(rows).hasSize(2).extracting(DrawingVerificationSummary.Item::no).containsExactly("001", "001");
    assertThat(rows.getFirst().qty()).isEqualTo("2.0000");
    assertThat(rows.getFirst().mat()).isNull();
    assertThat(rows.getFirst().note()).isEmpty();
    assertThat(rows.getFirst().weight()).isEqualTo("0.50");
    assertThat(rows.get(1).qty()).isEqualTo("0.1250");
    assertThat(summary.data().get("DRAW-02").params().items().getFirst().no()).isNull();
    assertThat(json.readTree(payload.dataJson())).isEqualTo(json.readTree(raw).get("data"));
    assertThat(json.valueToTree(summary).size()).isEqualTo(17);
    assertThat(json.valueToTree(summary).get("plm_data")).isEqualTo(summary.plmData());
  }

  @Test void preservesPlmOrderExtensionsAndCopiesMutableJson() throws Exception {
    var root = DrawingVerificationFixtures.initial("task-01");
    var payload = codec.parse(root.toString());
    assertThat(payload.summary().plmData()).isEqualTo(root.get("plm_data"));
    assertThat(json.readTree(payload.plmDataJson()).get(0).get("plmVersion").asText()).isEqualTo("V002");
    assertThat(json.readTree(payload.plmDataJson()).get(1).get("plmVersion").asText()).isEqualTo("V001");
    ((ObjectNode) payload.summary().plmData().get(0)).put("plmVersion", "forged");
    ((ObjectNode) payload.summary().drafts()).put("forged", true);
    assertThat(payload.summary().plmData().get(0).get("plmVersion").asText()).isEqualTo("V002");
    assertThat(payload.summary().drafts().has("forged")).isFalse();
    assertThatThrownBy(() -> payload.summary().data().putAll(Map.of("other", rootDrawing())))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test void preservesUnknownPlmNumbersWithoutFloatingPointRounding() throws Exception {
    String raw = DrawingVerificationFixtures.initial("precision").toString()
        .replace("\"plmVersion\":\"V002\"", "\"precise\":123456789.1234567890123400,\"plmVersion\":\"V002\"");
    var payload = codec.parse(raw);
    assertThat(payload.plmDataJson()).contains("123456789.1234567890123400");
    assertThat(payload.rawJson()).isEqualTo(raw);
  }

  @Test void zeroDifferencesDoesNotRequireApprovedCompleteOrEmptyReasons() {
    var summary = codec.parse(DrawingVerificationFixtures.initial("zero").toString()).summary();
    assertThat(summary.differenceCount()).isZero();
    assertThat(summary.status()).isEqualTo("reviewRequired");
    assertThat(summary.verificationStatus()).isEqualTo("incomplete");
    assertThat(summary.reviewReasons()).isNotEmpty();
  }

  @Test void waitingAndConfirmedPayloadsKeepTheirDifferentDataAndOriginalDifferenceCounts() {
    var waiting = DrawingVerificationFixtures.initial("waiting");
    waiting.put("differenceCount", 2);
    waiting.putObject("data");
    assertThat(codec.parse(waiting.toString()).summary().data()).isEmpty();
    var confirmed = codec.parse(DrawingVerificationFixtures.confirmed("waiting").toString());
    assertThat(confirmed.summary().differenceCount()).isEqualTo(2);
    assertThat(confirmed.summary().plmData()).isEmpty();
    assertThat(confirmed.summary().data()).hasSize(2);
  }

  @Test void rejectsMissingNumbersAndNumericItemCoercion() {
    var root = DrawingVerificationFixtures.initial("bad");
    root.remove("differenceCount");
    assertThatThrownBy(() -> codec.parse(root.toString())).hasMessageContaining("differenceCount");
    root.put("differenceCount", -1);
    assertThatThrownBy(() -> codec.parse(root.toString())).hasMessageContaining("非负整数");
    root.put("differenceCount", 0);
    ((ObjectNode) root.path("data").path("DRAW-01").path("params").path("items").get(0)).put("qty", 2);
    assertThatThrownBy(() -> codec.parse(root.toString())).hasMessageContaining("qty 必须是字符串或 null");
  }

  @Test void fingerprintsIgnoreOnlyAttemptCountAndObjectKeyOrder() {
    var root = DrawingVerificationFixtures.confirmed("hash");
    String fingerprint = codec.parse(root.toString()).contentFingerprint();
    root.put("deliveryAttempts", 7);
    assertThat(codec.parse(root.toString()).contentFingerprint()).isEqualTo(fingerprint);
    var reordered = json.createObjectNode();
    var names = new java.util.ArrayList<String>();
    root.fieldNames().forEachRemaining(names::add);
    java.util.Collections.reverse(names);
    names.forEach(name -> reordered.set(name, root.get(name)));
    assertThat(codec.parse(reordered.toString()).contentFingerprint()).isEqualTo(fingerprint);
    reordered.put("revision", 4);
    assertThat(codec.parse(reordered.toString()).contentFingerprint()).isNotEqualTo(fingerprint);
  }

  @Test void invalidJsonNeverEchoesPotentialCredentials() {
    assertThatThrownBy(() -> codec.parse("{\"fileUrl\":\"?token=do-not-log"))
        .hasMessage("电子图库协议错误：响应不是有效 JSON");
  }

  @Test void rejectsDuplicateKeysTrailingValuesAndMissingItemKeys() {
    String raw = DrawingVerificationFixtures.initial("strict").toString();
    assertThatThrownBy(() -> codec.parse(raw + " {}" )).hasMessageContaining("有效 JSON");
    assertThatThrownBy(() -> codec.parse(raw.replace("\"differenceCount\":0", "\"differenceCount\":0,\"differenceCount\":1")))
        .hasMessageContaining("有效 JSON");
    var root = DrawingVerificationFixtures.initial("strict");
    ((ObjectNode) root.path("data").path("DRAW-01").path("params").path("items").get(0)).remove("note");
    assertThatThrownBy(() -> codec.parse(root.toString())).hasMessageContaining("固定九字段");
  }

  @Test void fingerprintPreservesArrayOrderAndEveryOtherBusinessValue() {
    var root = DrawingVerificationFixtures.initial("ordered");
    String fingerprint = codec.parse(root.toString()).contentFingerprint();
    var sources = root.withArray("plm_data");
    var first = sources.get(0);
    sources.set(0, sources.get(1)); sources.set(1, first);
    assertThat(codec.parse(root.toString()).contentFingerprint()).isNotEqualTo(fingerprint);
    var confirmation = DrawingVerificationFixtures.confirmed("ordered");
    String confirmedFingerprint = codec.parse(confirmation.toString()).contentFingerprint();
    confirmation.put("deliveryStatus", "delivered");
    assertThat(codec.parse(confirmation.toString()).contentFingerprint()).isNotEqualTo(confirmedFingerprint);
  }

  private DrawingVerificationSummary.Drawing rootDrawing() {
    return codec.parse(DrawingVerificationFixtures.initial("copy").toString()).summary().data().get("DRAW-01");
  }
}
