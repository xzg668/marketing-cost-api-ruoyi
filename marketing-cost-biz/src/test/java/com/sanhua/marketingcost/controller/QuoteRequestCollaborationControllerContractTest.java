package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationBatchStartRequest;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationStartRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@DisplayName("QCBP-08 当前报价详情协作接口契约")
class QuoteRequestCollaborationControllerContractTest {
  @Test
  void exposesOnlyDesignedCurrentQuoteEndpointsWithSeparatedPermissions() throws Exception {
    RequestMapping root = QuoteRequestCollaborationController.class.getAnnotation(RequestMapping.class);
    assertThat(root.value()).containsExactly("/api/v1/quote-requests/{oaNo}");

    Method summary = method("summary", String.class);
    assertThat(summary.getAnnotation(GetMapping.class).value()).containsExactly("/collaboration-summary");
    assertThat(summary.getAnnotation(PreAuthorize.class).value()).contains("ingest:quote:list");

    Method scan = method("scan", String.class, Long.class);
    assertThat(scan.getAnnotation(PostMapping.class).value()).containsExactly("/items/{itemId}/collaboration/scan");
    assertThat(scan.getAnnotation(PreAuthorize.class).value()).contains("ingest:quote:bom-check");

    Method start = method("start", String.class, Long.class, QuoteCollaborationStartRequest.class);
    assertThat(start.getAnnotation(PostMapping.class).value()).containsExactly("/items/{itemId}/collaboration/start");
    assertThat(start.getAnnotation(PreAuthorize.class).value()).contains("collaboration:task:create");

    Method candidates = method("technicianCandidates", String.class, Long.class);
    assertThat(candidates.getAnnotation(GetMapping.class).value())
        .containsExactly("/items/{itemId}/collaboration/technician-candidates");
    assertThat(candidates.getAnnotation(PreAuthorize.class).value())
        .contains("collaboration:task:create");

    Method batch = method("batchStart", String.class, QuoteCollaborationBatchStartRequest.class);
    assertThat(batch.getAnnotation(PostMapping.class).value()).containsExactly("/collaboration/batch-start");
    assertThat(batch.getAnnotation(PreAuthorize.class).value()).contains("collaboration:task:create");

    Method history = method("history", String.class, Long.class);
    assertThat(history.getAnnotation(GetMapping.class).value()).containsExactly("/items/{itemId}/collaboration/history");
    assertThat(history.getAnnotation(PreAuthorize.class).value()).contains("ingest:quote:list");
  }

  private static Method method(String name, Class<?>... parameters) throws Exception {
    return QuoteRequestCollaborationController.class.getMethod(name, parameters);
  }
}
