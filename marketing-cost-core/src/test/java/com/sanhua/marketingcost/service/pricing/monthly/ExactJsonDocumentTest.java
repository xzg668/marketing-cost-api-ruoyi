package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ExactJsonDocumentTest {
  @Test void sameCanonicalBytesAsExistingSourceEvidenceForExactNumbersUnicodeAndOrderedArrays() {
    String input="{\"z\":[null,0,0,1e-30],\"a\":12345678901234567890.123456789012345678,\"中\":\"原文\"}";
    String canonical=ExactJsonDocument.canonicalObject(input,4096);
    assertThat(canonical).isEqualTo(MonthlyAdoptionPlan.SourceEvidence.ofJson(input).json())
        .contains("[null,0,0,0.000000000000000000000000000001]","12345678901234567890.123456789012345678");
  }

  @Test void utf8InputAndNumericExpansionBothRespectTheExplicitBudget() {
    assertThat(ExactJsonDocument.canonicalObject("{\"x\":\"中\"}",11)).isEqualTo("{\"x\":\"中\"}");
    assertThatThrownBy(()->ExactJsonDocument.canonicalObject("{\"x\":\"中\"}",10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->ExactJsonDocument.canonicalObject("{\"x\":1e100}",50)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->ExactJsonDocument.canonicalObject("{\"x\":1}",0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->ExactJsonDocument.canonicalObject("{\"x\":1}",ExactJsonDocument.MAX_DOCUMENT_BYTES+1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void theSinglePriceEvidenceLimitRemainsOneMib() {
    String object="{\"source\":\""+"中".repeat(350000)+"\"}";
    assertThat(ExactJsonDocument.canonicalObject(object,2*1024*1024)).isEqualTo(object);
    assertThatThrownBy(()->MonthlyAdoptionPlan.SourceEvidence.ofJson(object)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void reusingAnExactDocumentNeverBypassesANarrowerExpansionBudgetOrChangedInputValidation() {
    String expanded = "{\"amount\":1e100}";
    String canonical = ExactJsonDocument.canonicalObject(expanded, 256);
    assertThat(ExactJsonDocument.canonicalObject(new String(expanded), 256)).isEqualTo(canonical);
    assertThatThrownBy(() -> ExactJsonDocument.canonicalObject(expanded, 50))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ExactJsonDocument.canonicalObject("{\"amount\":null}", 256)).isEqualTo("{\"amount\":null}");
    assertThat(ExactJsonDocument.canonicalObject("{\"amount\":0}", 256)).isEqualTo("{\"amount\":0}");
    assertThatThrownBy(() -> ExactJsonDocument.canonicalObject("{\"amount\":1,\"amount\":2}", 256))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
