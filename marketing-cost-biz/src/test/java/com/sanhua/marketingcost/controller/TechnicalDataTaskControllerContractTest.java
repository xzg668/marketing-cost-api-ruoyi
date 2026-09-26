package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPublishRequest;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataDocumentSubmissionService.Request;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class TechnicalDataTaskControllerContractTest {
  @Test
  void exposesTaskLifecycleV2EndpointsWithNewPermissions() throws Exception {
    RequestMapping root = TechnicalDataTaskController.class.getAnnotation(RequestMapping.class);
    assertThat(root.value()).containsExactly("/api/v2/technical-data");

    Method publish = TechnicalDataTaskController.class.getMethod(
        "publishFromQuote", TechnicalDataTaskPublishRequest.class);
    assertThat(publish.getAnnotation(PostMapping.class).value())
        .containsExactly("/tasks/publish-from-quote");
    assertThat(publish.getAnnotation(PreAuthorize.class).value())
        .contains("ingest:quote:cost-run:execute")
        .doesNotContain("collaboration:");

    Method workbench = TechnicalDataTaskController.class.getMethod(
        "workbench", int.class, int.class, String.class, String.class, String.class, String.class);
    assertThat(workbench.getAnnotation(GetMapping.class).value())
        .containsExactly("/products");
    assertThat(workbench.getAnnotation(PreAuthorize.class).value())
        .contains("technical:data:task:list", "technical:data:admin:operate");

    Method detail = TechnicalDataTaskController.class.getMethod("detail", Long.class);
    assertThat(detail.getAnnotation(GetMapping.class).value())
        .containsExactly("/tasks/{taskId}");

    Method validate = TechnicalDataTaskController.class.getMethod("validate", Long.class);
    assertThat(validate.getAnnotation(PostMapping.class).value())
        .containsExactly("/tasks/{taskId}/validate");
    assertThat(validate.getAnnotation(PreAuthorize.class).value())
        .contains("technical:data:task:edit")
        .doesNotContain("collaboration:");

    Method submit = TechnicalDataTaskController.class.getMethod(
        "submitDocument", long.class, Request.class);
    assertThat(submit.getAnnotation(PostMapping.class).value())
        .containsExactly("/forms/{formId}/submit");
    assertThat(submit.getAnnotation(PreAuthorize.class).value())
        .contains("technical:data:task:edit")
        .doesNotContain("collaboration:");
  }

  @Test
  void batchPublishAcceptsProductIdsAndCapturesUntrustedSourceFields() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var request = mapper.readValue("""
        {"requestId":"batch-1","oaFormItemIds":[10,11],"accountingMonth":"2026-09",
         "assigneeUserId":101,"validPackageSource":true,"oaNo":"forged"}
        """, TechnicalDataTaskPublishRequest.class);
    assertThat(request.getOaFormItemIds()).containsExactly(10L, 11L);
    assertThat(request.getAssigneeUserId()).isEqualTo(101L);
    assertThat(request.getUnknownFields()).containsKeys("validPackageSource", "oaNo");
  }

  @Test
  void contractDoesNotExposeLegacyCollaborationNamespace() {
    RequestMapping root = TechnicalDataTaskController.class.getAnnotation(RequestMapping.class);
    assertThat(root.value()).allMatch(path -> !path.contains("collaboration"));
    assertThat(Arrays.stream(TechnicalDataTaskController.class.getDeclaredMethods())
        .flatMap(method -> Arrays.stream(method.getAnnotations()))
        .map(Object::toString))
        .noneMatch(annotation -> annotation.contains("collaboration:"));
  }
}
