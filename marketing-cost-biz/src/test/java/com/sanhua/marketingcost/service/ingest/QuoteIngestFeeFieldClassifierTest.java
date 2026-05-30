package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.enums.QuoteExtraFeeCategory;
import org.junit.jupiter.api.Test;

class QuoteIngestFeeFieldClassifierTest {
  @Test
  void classifiesKnownFeeFieldsAndIgnoresBearers() {
    assertThat(QuoteIngestFeeFieldClassifier.isFeeField("fixtureTotalAmount", "工装夹具费")).isTrue();
    assertThat(QuoteIngestFeeFieldClassifier.isFeeField("moldTotalAmount", "模具费")).isTrue();
    assertThat(QuoteIngestFeeFieldClassifier.isFeeField("toolCost", "刀具费")).isTrue();
    assertThat(QuoteIngestFeeFieldClassifier.isFeeField("moldBearer", "模具费承担方")).isFalse();
  }

  @Test
  void mapsFeeCategoryAndUnit() {
    assertThat(QuoteIngestFeeFieldClassifier.feeCategory("certificationFee", "认证费"))
        .isEqualTo(QuoteExtraFeeCategory.CERTIFICATION.getCode());
    assertThat(QuoteIngestFeeFieldClassifier.feeCategory("equipmentFee", "设备费"))
        .isEqualTo(QuoteExtraFeeCategory.EQUIPMENT.getCode());
    assertThat(QuoteIngestFeeFieldClassifier.unit("工装夹具费（万元）")).isEqualTo("万元");
    assertThat(QuoteIngestFeeFieldClassifier.unit("模具费")).isEqualTo("元");
  }
}
