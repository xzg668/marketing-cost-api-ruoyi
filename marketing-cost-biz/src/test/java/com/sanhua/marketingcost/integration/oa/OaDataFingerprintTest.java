package com.sanhua.marketingcost.integration.oa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OaDataFingerprintTest {
  @Test
  void decimalScaleAndScientificNotationDoNotChangeTechnicalDependency() {
    var codec = new OaMessageCodec(new ObjectMapper());
    var expected = codec.dataFingerprint(Map.of("weight", new BigDecimal("10.0"), "quantity", new BigDecimal("2")));
    assertThat(codec.dataFingerprint(codec.read("{\"quantity\":2.0,\"weight\":1E+1}"))).isEqualTo(expected);
    assertThat(codec.dataFingerprint(Map.of("weight", new BigDecimal("10.01"), "quantity", new BigDecimal("2")))).isNotEqualTo(expected);
  }

  @Test
  void originalApprovedObjectAndJsonRoundTripFingerprintsRemainVerifiable() {
    var mapper = new ObjectMapper();
    var codec = new OaMessageCodec(mapper);
    var original = Map.of("weight", new BigDecimal("10.0"), "quantity", new BigDecimal("2"));
    var objectHash = codec.canonicalHash(original);
    var wireHash = codec.canonicalHash(codec.read(codec.write(mapper.valueToTree(original))));
    assertThat(objectHash).isNotEqualTo(wireHash);
    for (var approved : java.util.List.of(objectHash, wireHash)) {
      var restarted = new OaMessageCodec(new ObjectMapper());
      assertThat(restarted.matchesDataFingerprint(approved, original)).isTrue();
      assertThat(restarted.matchesDataFingerprint(approved, Map.of("weight", new BigDecimal("11"), "quantity", new BigDecimal("2")))).isFalse();
    }
  }
}
