package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceDraftChangeReferenceRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceDraftCreateRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceDraftSaveRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceDraftValidateRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@DisplayName("QCBP-14 技术补价接口契约")
class TechnicalPriceCollaborationControllerContractTest {

  @Test
  void exposesServerScopedReadAndEditRoutesWithoutClientOwnedScope() throws Exception {
    RequestMapping root = TechnicalPriceCollaborationController.class
        .getAnnotation(RequestMapping.class);
    assertThat(root.value()).containsExactly("/api/v1/collaboration");

    assertGet("workspace", new Class<?>[] {Long.class},
        "/product-tasks/{taskId}/price-gaps", "collaboration:task:read");
    assertGet("formalPrices", new Class<?>[] {Long.class, String.class, String.class},
        "/price-gaps/{gapId}/formal-prices", "collaboration:task:read");
    assertPost("copy", new Class<?>[] {Long.class, TechnicalPriceDraftCreateRequest.class},
        "/price-gaps/{gapId}/draft/copy", "collaboration:task:edit");
    assertPost("direct", new Class<?>[] {Long.class, TechnicalPriceDraftCreateRequest.class},
        "/price-gaps/{gapId}/draft/direct", "collaboration:task:edit");
    assertGet("draft", new Class<?>[] {Long.class},
        "/price-drafts/{draftId}", "collaboration:task:read");
    assertPut("save", new Class<?>[] {Long.class, TechnicalPriceDraftSaveRequest.class},
        "/price-drafts/{draftId}", "collaboration:task:edit");
    assertPost("validate", new Class<?>[] {Long.class, TechnicalPriceDraftValidateRequest.class},
        "/price-drafts/{draftId}/validate", "collaboration:task:edit");
    assertPost("changeReference",
        new Class<?>[] {Long.class, TechnicalPriceDraftChangeReferenceRequest.class},
        "/price-drafts/{draftId}/change-reference", "collaboration:task:edit");

    assertThat(TechnicalPriceDraftCreateRequest.class.getRecordComponents())
        .extracting(component -> component.getName())
        .containsExactly("priceType", "referenceSourceType", "referenceSourceId")
        .doesNotContain("businessUnitType", "orgCode", "technicianUserId", "productTaskId");
    assertThat(TechnicalPriceDraftSaveRequest.class.getRecordComponents())
        .extracting(component -> component.getName())
        .contains("expectedVersion", "fields")
        .doesNotContain("businessUnitType", "orgCode", "technicianUserId", "gapId");
  }

  private static void assertGet(
      String methodName, Class<?>[] parameterTypes, String path, String permission)
      throws Exception {
    Method method = TechnicalPriceCollaborationController.class
        .getMethod(methodName, parameterTypes);
    assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
    assertThat(method.getAnnotation(PreAuthorize.class).value()).contains(permission);
  }

  private static void assertPost(
      String methodName, Class<?>[] parameterTypes, String path, String permission)
      throws Exception {
    Method method = TechnicalPriceCollaborationController.class
        .getMethod(methodName, parameterTypes);
    assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
    assertThat(method.getAnnotation(PreAuthorize.class).value()).contains(permission);
  }

  private static void assertPut(
      String methodName, Class<?>[] parameterTypes, String path, String permission)
      throws Exception {
    Method method = TechnicalPriceCollaborationController.class
        .getMethod(methodName, parameterTypes);
    assertThat(method.getAnnotation(PutMapping.class).value()).containsExactly(path);
    assertThat(method.getAnnotation(PreAuthorize.class).value()).contains(permission);
  }
}
