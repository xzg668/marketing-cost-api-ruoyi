package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomAlternativePreviewRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class QuoteEffectiveBomApiContractTest {

  @Test
  void exposesTheApprovedSingleItemRoutesAndPermissions() throws Exception {
    RequestMapping root = QuoteEffectiveBomController.class.getAnnotation(RequestMapping.class);
    Method query =
        QuoteEffectiveBomController.class.getMethod(
            "getEffectiveBom", String.class, Long.class);
    Method rebuild =
        QuoteEffectiveBomController.class.getMethod(
            "rebuildPreview", String.class, Long.class);
    Method alternativePreview =
        QuoteEffectiveBomController.class.getMethod(
            "previewAlternative",
            String.class,
            Long.class,
            QuoteEffectiveBomAlternativePreviewRequest.class);

    assertThat(root.value())
        .containsExactly("/api/v1/quote-requests/{oaNo}/items/{oaFormItemId}/effective-bom");
    assertThat(query.getAnnotation(GetMapping.class).value()).isEmpty();
    assertThat(query.getAnnotation(PreAuthorize.class).value()).contains("ingest:quote:list");
    assertThat(rebuild.getAnnotation(PostMapping.class).value()).containsExactly("/rebuild");
    assertThat(rebuild.getAnnotation(PreAuthorize.class).value())
        .contains("quote:costing:bom:alternative-select");
    assertThat(alternativePreview.getAnnotation(PostMapping.class).value())
        .containsExactly("/alternative-preview");
    assertThat(alternativePreview.getAnnotation(PreAuthorize.class).value())
        .contains("quote:costing:bom:alternative-select");
  }
}
