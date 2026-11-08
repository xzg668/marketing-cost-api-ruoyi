package com.sanhua.marketingcost.mapper.typehandler;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class MonthlyRepriceGroupInputTypeHandlerTest {
  @Test void aCompleteCollectionCanExceedTheParsersDefaultSingleStringLimit() {
    String row="{\"source\":\""+"x".repeat(1024)+"\",\"quantity\":0.123456789012345678}";
    String original="{\"schemaVersion\":1,\"lines\":["+String.join(",",java.util.Collections.nCopies(22000,row))+"]}";
    assertThat(original.length()).isGreaterThan(20_000_000).isLessThan(64*1024*1024);
    String stored=MonthlyRepriceGroupInputTypeHandler.store(original);
    assertThat(MonthlyRepriceGroupInputTypeHandler.restore(stored)).isEqualTo(original);
    assertThat(MonthlyRepriceGroupInputTypeHandler.restore(original)).isEqualTo(original);
  }

  @Test void completeRepeatedOccurrencesUnicodeAndExactDecimalsRoundTripWithoutTruncation() throws Exception {
    String row="{\"price\":0.123456789012345678,\"note\":\"原始依据\\\\路径\\n\",\"quantity\":-0.000069,\"fee\":null,\"zero\":0}";
    String original="{\"schemaVersion\":1,\"lines\":["+String.join(",",java.util.Collections.nCopies(100,row))+"]}";
    String stored=MonthlyRepriceGroupInputTypeHandler.store(original);
    assertThat(stored.length()).isLessThan(original.length()/4);
    assertThat(new ObjectMapper().readTree(stored).path("schemaVersion").intValue()).isEqualTo(1);
    assertThat(MonthlyRepriceGroupInputTypeHandler.restore(stored)).isEqualTo(original);
    assertThat(MonthlyRepriceGroupInputTypeHandler.restore(original)).isEqualTo(original);
  }

  @Test void tinyOrIncompressibleInputsRemainPlainAndNeverGrow() {
    String small="{\"schemaVersion\":1,\"fee\":null,\"zero\":0}";
    assertThat(MonthlyRepriceGroupInputTypeHandler.store(small)).isEqualTo(small);
    byte[] data=new byte[8192];new java.util.Random(7).nextBytes(data);
    String large="{\"schemaVersion\":1,\"opaque\":\""+java.util.Base64.getEncoder().encodeToString(data)+"\"}";
    String stored=MonthlyRepriceGroupInputTypeHandler.store(large);
    assertThat(stored.length()).isLessThanOrEqualTo(large.length());
    assertThat(MonthlyRepriceGroupInputTypeHandler.restore(stored)).isEqualTo(large);
    assertThat(MonthlyRepriceGroupInputTypeHandler.restore(null)).isNull();
  }

  @Test void unknownMalformedOrCorruptStorageCannotBecomeAValidGroupInput() throws Exception {
    var json=new ObjectMapper();
    String stored=MonthlyRepriceGroupInputTypeHandler.store("{\"schemaVersion\":1,\"source\":\""+"凭据".repeat(3000)+"\"}");
    for(String invalid:java.util.List.of(
        stored.replace("\"inputStorageVersion\":1","\"inputStorageVersion\":2"),
        stored.replace("\"inputStorageVersion\":1","\"inputStorageVersion\":1.0"),
        stored.substring(0,stored.length()-1)+",\"unexpected\":1}")) {
      assertThatThrownBy(()->MonthlyRepriceGroupInputTypeHandler.restore(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    var root=(ObjectNode)json.readTree(stored);
    root.put("payload","{\"schemaVersion\":2,\"document\":\"broken\"}");
    assertThatThrownBy(()->MonthlyRepriceGroupInputTypeHandler.restore(root.toString())).isInstanceOf(IllegalArgumentException.class);
  }
}
