package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class QuoteEffectiveBomTransactionBoundaryTest {

  @Test
  void queryAndRebuildCanRepairTheCurrentProductsInternalMonthlyRelation() throws Exception {
    Transactional query =
        QuoteEffectiveBomApplicationServiceImpl.class
            .getMethod("getEffectiveBom", String.class, Long.class)
            .getAnnotation(Transactional.class);
    Transactional rebuild =
        QuoteEffectiveBomApplicationServiceImpl.class
            .getMethod("rebuildPreview", String.class, Long.class)
            .getAnnotation(Transactional.class);

    assertThat(query).isNotNull();
    assertThat(query.readOnly()).isFalse();
    assertThat(rebuild).isNotNull();
    assertThat(rebuild.readOnly()).isFalse();
  }
}
