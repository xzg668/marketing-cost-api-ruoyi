package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class QuoteEffectiveBomConfirmationApiContractTest {

  @Test
  void exposesApprovedSingleProductConfirmationRoutesAndPermission()
      throws Exception {
    RequestMapping root =
        QuoteEffectiveBomConfirmationController.class.getAnnotation(
            RequestMapping.class);
    Method confirm =
        QuoteEffectiveBomConfirmationController.class.getMethod(
            "confirm", String.class, Long.class, QuoteBomConfirmRequest.class);
    Method prepare =
        QuoteEffectiveBomConfirmationController.class.getMethod(
            "prepareCostingBom", String.class, Long.class);
    Method rebuild =
        QuoteEffectiveBomConfirmationController.class.getMethod(
            "rebuildFromEffective", String.class, Long.class);

    assertThat(root.value())
        .containsExactly("/api/v1/quote-requests/{oaNo}/items/{oaFormItemId}");
    assertThat(confirm.getAnnotation(PostMapping.class).value())
        .containsExactly("/effective-bom/confirm");
    assertThat(prepare.getAnnotation(PostMapping.class).value())
        .containsExactly("/effective-bom/prepare-costing");
    assertThat(rebuild.getAnnotation(PostMapping.class).value())
        .containsExactly("/costing-bom/rebuild-from-effective");
    assertThat(confirm.getAnnotation(PreAuthorize.class).value())
        .contains("quote:costing:bom:confirm");
    assertThat(prepare.getAnnotation(PreAuthorize.class).value())
        .contains("quote:costing:bom:confirm");
    assertThat(rebuild.getAnnotation(PreAuthorize.class).value())
        .contains("quote:costing:bom:confirm");
  }
}
