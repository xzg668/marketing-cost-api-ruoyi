package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class TechnicalDataPackageControllerContractTest {

  @Test
  void exposesOnlyV2PackageReadReferenceChildrenAndSaveEndpointsWithPermissions()
      throws Exception {
    RequestMapping root = TechnicalDataPackageController.class.getAnnotation(RequestMapping.class);
    assertThat(root.value()).containsExactly("/api/v2/technical-data/products/{productId}/package");

    Method get = TechnicalDataPackageController.class.getMethod("get", Long.class, Long.class);
    Method references = TechnicalDataPackageController.class.getMethod(
        "references", Long.class, String.class);
    Method children = TechnicalDataPackageController.class.getMethod("children", Long.class, String.class);
    Method save = TechnicalDataPackageController.class.getMethod(
        "save", Long.class,
        com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPackageSaveRequest.class);

    assertThat(get.getAnnotation(GetMapping.class).value()).isEmpty();
    assertThat(references.getAnnotation(GetMapping.class).value()).containsExactly("/references");
    assertThat(children.getAnnotation(GetMapping.class).value()).containsExactly("/children");
    assertThat(save.getAnnotation(PutMapping.class).value()).isEmpty();
    assertThat(get.getAnnotation(PreAuthorize.class).value()).contains("technical:data:task:list");
    assertThat(save.getAnnotation(PreAuthorize.class).value()).contains("technical:data:task:edit");
    assertThat(save.getAnnotation(PreAuthorize.class).value()).contains("technical:data:admin:operate");
    assertThat(save.getAnnotation(PreAuthorize.class).value()).contains("ingest:quote:cost-run:execute");
  }

  @Test
  void packageWriteDtosRejectUnknownFieldsAndThereIsNoAttachmentContract() throws Exception {
    String request = Files.readString(Path.of(
        "src/main/java/com/sanhua/marketingcost/dto/technicaldata/TechnicalDataPackageSaveRequest.java"));
    String item = Files.readString(Path.of(
        "src/main/java/com/sanhua/marketingcost/dto/technicaldata/TechnicalDataPackageItemRequest.java"));
    String controller = Files.readString(Path.of(
        "src/main/java/com/sanhua/marketingcost/controller/TechnicalDataPackageController.java"));
    assertThat(request).contains("@JsonAnySetter", "unknownFields");
    assertThat(item).contains("@JsonAnySetter", "unknownFields");
    assertThat(controller).doesNotContain("MultipartFile", "upload", "attachment");
  }
}
