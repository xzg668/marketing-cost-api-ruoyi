package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MonthlyPriceDocumentReuseTest {
  @Test void compactPreparationRestoresEveryByteIncludingPrecisionAndRepeatedOccurrences() throws Exception {
    var codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
    String row = "{\"material\":\"重复物料\\\\原文\",\"quantity\":0.1234567890123456789,\"optional\":null,\"explicit\":0}";
    String document = "{\"rows\":[" + String.join(",",java.util.Collections.nCopies(100,row)) + "]}";
    var original = codec.wrap(document); var packed = codec.compact(original);
    assertThat(new ObjectMapper().readTree(packed).path("schemaVersion").intValue()).isEqualTo(2);
    assertThat(packed.length()).isLessThan(original.length()/5);
    assertThat(codec.unwrap(packed)).isEqualTo(document);
    assertThat(codec.unwrap(original)).isEqualTo(document);
    assertThat(codec.decode(packed,ObjectNode.class).path("rows")).hasSize(100);
    assertThat(codec.compact(packed)).isEqualTo(packed);
    assertThat(codec.compact(codec.wrap("{\"small\":1}"))).isEqualTo(codec.wrap("{\"small\":1}"));
  }

  @Test void damagedCompressionAndUnknownFormatsFailInsteadOfInventingValues() throws Exception {
    var codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
    assertThatThrownBy(() -> codec.unwrap("{\"schemaVersion\":2,\"document\":\"invalid gzip\"}"))
        .isInstanceOf(IllegalArgumentException.class);
    var packed = (ObjectNode)new ObjectMapper().readTree(codec.compact(codec.wrap("{\"value\":\""+"same input".repeat(1000)+"\"}")));
    var bytes = java.util.Base64.getDecoder().decode(packed.path("document").asText());
    bytes[bytes.length-5] ^= 1;
    packed.put("document",java.util.Base64.getEncoder().encodeToString(bytes));
    assertThatThrownBy(() -> codec.unwrap(packed.toString())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.unwrap("{\"schemaVersion\":3,\"document\":\"{}\"}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
  @Test void repeatedImmutableInputsReuseParsingWhileChangedDocumentsRemainIndependent() {
    var codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
    String encoded = codec.encode(new MonthlyPreparationInputs(1, "root/A", List.of(), List.of(), Set.of()));
    var first = codec.decode(encoded, MonthlyPreparationInputs.class);
    assertThat(codec.decode(new String(encoded), MonthlyPreparationInputs.class)).isSameAs(first);
    var changed = codec.decode(encoded.replace("root/A", "root/B"), MonthlyPreparationInputs.class);
    assertThat(changed.rootPath()).isEqualTo("root/B");
    assertThat(changed).isNotSameAs(first);
    assertThatThrownBy(() -> first.uses().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> codec.decode(encoded + "{}", MonthlyPreparationInputs.class))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void mutableJsonTreesAreNeverSharedBetweenCallers() {
    var codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
    String encoded = codec.wrap("{\"price\":2}");
    codec.decode(encoded, ObjectNode.class).put("price", 99);
    assertThat(codec.decode(encoded, ObjectNode.class).path("price").intValue()).isEqualTo(2);
  }

  @Test void repeatedTextRetainsExactPrecisionAndEveryChangedEnvelopeIsValidatedIndependently() {
    var codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
    String document = "{\"amount\":0.123456789012345678900,\"missing\":null,\"zero\":0,\"name\":\"原文\"}";
    String envelope = codec.wrap(document);
    assertThat(codec.wrap(new String(document))).isEqualTo(envelope);
    assertThat(codec.unwrap(envelope)).isEqualTo(document);
    assertThat(codec.unwrap(new String(envelope))).isEqualTo(document);
    String changed = document.replace("123456789012345678900", "123456789012345678901");
    assertThat(codec.unwrap(codec.wrap(changed))).isEqualTo(changed);
    assertThatThrownBy(() -> codec.unwrap(envelope.replace("\"schemaVersion\":1", "\"schemaVersion\":99")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.unwrap(envelope + "{}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
