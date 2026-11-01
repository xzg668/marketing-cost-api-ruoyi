package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MonthlyPriceDocumentReuseTest {
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
}
