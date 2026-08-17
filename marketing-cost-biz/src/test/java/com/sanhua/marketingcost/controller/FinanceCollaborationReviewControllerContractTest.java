package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.collaboration.FinanceReviewDecisionRequest;
import com.sanhua.marketingcost.dto.collaboration.FinanceReviewSubmitRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@DisplayName("QCBP-19/20 财务补录审核接口契约")
class FinanceCollaborationReviewControllerContractTest {
  @Test
  void exposesMineRealItemDecisionRejectAndApproveWithSeparatedPermissions() throws Exception {
    RequestMapping root = FinanceCollaborationReviewController.class.getAnnotation(RequestMapping.class);
    assertThat(root.value()).containsExactly("/api/v1/collaboration/finance-reviews");

    assertGet("mine", new Class<?>[] {boolean.class}, "/mine", "collaboration:review:read");
    assertGet("detail", new Class<?>[] {Long.class}, "/{reviewId}", "collaboration:review:read");
    assertGet("item", new Class<?>[] {Long.class, Long.class},
        "/{reviewId}/items/{itemId}", "collaboration:review:read");
    assertPut("decide", new Class<?>[] {Long.class, Long.class, FinanceReviewDecisionRequest.class},
        "/{reviewId}/items/{itemId}/decision", "collaboration:review:decide");
    assertPost("reject", "/{reviewId}/reject");
    assertPost("approve", "/{reviewId}/approve");
    Method retry = FinanceCollaborationReviewController.class.getMethod("retryRecheck", Long.class);
    assertThat(retry.getAnnotation(PostMapping.class).value())
        .containsExactly("/{reviewId}/retry-recheck");
    assertThat(retry.getAnnotation(PreAuthorize.class).value())
        .contains("collaboration:review:decide");
  }

  private static void assertGet(String name, Class<?>[] params, String path, String permission)
      throws Exception {
    Method method = FinanceCollaborationReviewController.class.getMethod(name, params);
    assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
    assertThat(method.getAnnotation(PreAuthorize.class).value()).contains(permission);
  }

  private static void assertPut(String name, Class<?>[] params, String path, String permission)
      throws Exception {
    Method method = FinanceCollaborationReviewController.class.getMethod(name, params);
    assertThat(method.getAnnotation(PutMapping.class).value()).containsExactly(path);
    assertThat(method.getAnnotation(PreAuthorize.class).value()).contains(permission);
  }

  private static void assertPost(String name, String path) throws Exception {
    Method method = FinanceCollaborationReviewController.class.getMethod(
        name, Long.class, FinanceReviewSubmitRequest.class);
    assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
    assertThat(method.getAnnotation(PreAuthorize.class).value())
        .contains("collaboration:review:decide");
  }
}
