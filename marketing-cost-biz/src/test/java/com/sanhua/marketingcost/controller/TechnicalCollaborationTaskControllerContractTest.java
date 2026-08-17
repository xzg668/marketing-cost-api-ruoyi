package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.collaboration.TechnicalTaskActionRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalTaskDetailResponse;
import com.sanhua.marketingcost.dto.collaboration.ElectronicBomVerifyRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackageCopyRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackageDraftRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@DisplayName("QCBP-09 技术本人任务接口契约")
class TechnicalCollaborationTaskControllerContractTest {
  @Test
  void exposesTaskIdOnlyRoutesWithSeparatedReadEditSubmitPermissions() throws Exception {
    RequestMapping root = TechnicalCollaborationTaskController.class
        .getAnnotation(RequestMapping.class);
    assertThat(root.value()).containsExactly("/api/v1/collaboration/product-tasks");

    assertGet("mine", new Class<?>[0], "/mine", "collaboration:task:read");
    assertGet("detail", new Class<?>[] {Long.class}, "/{taskId}", "collaboration:task:read");
    assertPost("start", "/{taskId}/start", "collaboration:task:edit");
    assertPost("validate", "/{taskId}/validate", "collaboration:task:edit");
    assertPost("submit", "/{taskId}/submit", "collaboration:task:submit");
    assertGet("changeLog", new Class<?>[] {Long.class}, "/{taskId}/change-log",
        "collaboration:task:read");
    assertGet("exportElectronicTemplate",
        new Class<?>[] {Long.class, jakarta.servlet.http.HttpServletResponse.class},
        "/{taskId}/bom-draft/export-electronic-template", "collaboration:task:edit");
    Method verify = TechnicalCollaborationTaskController.class.getMethod(
        "verifyElectronicBom", Long.class, ElectronicBomVerifyRequest.class);
    assertThat(verify.getAnnotation(PostMapping.class).value())
        .containsExactly("/{taskId}/electronic-bom/verify");
    assertThat(verify.getAnnotation(PreAuthorize.class).value())
        .contains("collaboration:task:edit");
    assertThat(ElectronicBomVerifyRequest.class.getRecordComponents())
        .extracting(component -> component.getName())
        .containsExactly("expectedVersion", "bomPurpose", "remark")
        .doesNotContain("completed", "valid", "verified", "success");

    assertGet("packageReferenceProducts", new Class<?>[] {Long.class, String.class},
        "/{taskId}/package/reference-products", "collaboration:task:read");
    assertGet("packageParents", new Class<?>[] {Long.class, String.class},
        "/{taskId}/package/package-parents", "collaboration:task:read");
    assertGet("packageDraft", new Class<?>[] {Long.class},
        "/{taskId}/package-draft", "collaboration:task:read");
    Method copyPackage = TechnicalCollaborationTaskController.class.getMethod(
        "copyPackageDraft", Long.class, TechnicalPackageCopyRequest.class);
    assertThat(copyPackage.getAnnotation(PostMapping.class).value())
        .containsExactly("/{taskId}/package-draft/copy");
    assertThat(copyPackage.getAnnotation(PreAuthorize.class).value())
        .contains("collaboration:task:edit");
    Method savePackage = TechnicalCollaborationTaskController.class.getMethod(
        "savePackageDraft", Long.class, TechnicalPackageDraftRequest.class);
    assertThat(savePackage.getAnnotation(PutMapping.class).value())
        .containsExactly("/{taskId}/package-draft");
    assertThat(savePackage.getAnnotation(PreAuthorize.class).value())
        .contains("collaboration:task:edit");
    assertPost("checkPackagePrice", "/{taskId}/package-draft/check-price",
        "collaboration:task:edit");
    assertThat(TechnicalTaskDetailResponse.Gap.class.getRecordComponents())
        .extracting(component -> component.getName())
        .contains("bomPath", "bomQuantity", "bomUnit", "accountingMonth",
            "applicableOrgCode", "sourceType", "sourceId");
  }

  private static void assertGet(
      String name, Class<?>[] parameters, String path, String permission) throws Exception {
    Method method = TechnicalCollaborationTaskController.class.getMethod(name, parameters);
    assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
    assertThat(method.getAnnotation(PreAuthorize.class).value()).contains(permission);
  }

  private static void assertPost(String name, String path, String permission) throws Exception {
    Method method = TechnicalCollaborationTaskController.class.getMethod(
        name, Long.class, TechnicalTaskActionRequest.class);
    assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
    assertThat(method.getAnnotation(PreAuthorize.class).value()).contains(permission);
  }
}
