package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-06 新品临时产品键")
class CollaborationTemporaryProductKeyFactoryTest {

  @Test
  @DisplayName("同一报价产品行在扫描和发起阶段得到同一稳定键")
  void createsStableQuoteItemKey() {
    assertThat(CollaborationTemporaryProductKeyFactory.fromQuoteItem(275L))
        .isEqualTo("OA_FORM_ITEM:275");
  }

  @Test
  @DisplayName("拒绝空值和非正数产品行ID")
  void rejectsInvalidQuoteItemId() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CollaborationTemporaryProductKeyFactory.fromQuoteItem(null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CollaborationTemporaryProductKeyFactory.fromQuoteItem(0L));
  }
}
