package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileUpdateRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class TechnicalDataProfileControllerContractTest {
  @Test
  void exposesOnlyTheDeclaredProfilePatchWithEditPermission() throws Exception {
    RequestMapping root = TechnicalDataProfileController.class.getAnnotation(RequestMapping.class);
    Method method = TechnicalDataProfileController.class.getMethod(
        "save", Long.class, TechnicalDataProfileUpdateRequest.class);
    PatchMapping patch = method.getAnnotation(PatchMapping.class);
    PreAuthorize permission = method.getAnnotation(PreAuthorize.class);

    assertThat(root.value()).containsExactly("/api/v2/technical-data");
    assertThat(patch.value()).containsExactly("/products/{productId}/profile");
    assertThat(permission.value())
        .contains("technical:data:task:edit")
        .contains("technical:data:admin:operate")
        .contains("ingest:quote:cost-run:execute");
  }

  @Test
  void requestCapturesAnyFieldOutsideProductPropertyFeeValuesAndExpectedVersion()
      throws Exception {
    Method anySetter = TechnicalDataProfileUpdateRequest.class.getMethod(
        "captureUnknownField", String.class, Object.class);
    assertThat(anySetter.getAnnotation(JsonAnySetter.class)).isNotNull();

    TechnicalDataProfileUpdateRequest request = new TechnicalDataProfileUpdateRequest();
    request.captureUnknownField("materialNo", "ILLEGAL");
    assertThat(request.getUnknownFields()).containsEntry("materialNo", "ILLEGAL");
  }
}
