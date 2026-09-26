package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;

class TechnicalDataAuxiliaryControllerContractTest {
  @Test void amountIsEditableButSourceFieldsAndMultipliersCannotBeForged() throws Exception {
    var json = new ObjectMapper();
    var row = json.readValue("{\"itemKey\":\"CMS:1\",\"amount\":12,\"multiplier\":2,\"sourceAmount\":999}", TechnicalDataAuxiliaryItemRequest.class);
    assertThat(row.getAmount()).isEqualByComparingTo("12");
    assertThat(row.getUnknownFields()).containsKeys("multiplier", "sourceAmount");
    assertThat(json.readValue("{\"expectedVersion\":0,\"entryMode\":\"REFERENCE\",\"items\":[],\"sourceType\":\"TECHNICAL_VERSION\"}", TechnicalDataAuxiliarySaveRequest.class).getUnknownFields()).containsKey("sourceType");
  }
  @Test void readHistoryAndFilesAreScopedAndUploadRequiresEditPermission() throws Exception {
    var type = TechnicalDataAuxiliaryController.class;
    assertThat(type.getMethod("get", Long.class, Long.class).getAnnotation(PreAuthorize.class).value()).contains("ingest:quote:cost-run:execute");
    assertThat(type.getMethod("file", Long.class, Long.class).getAnnotation(PreAuthorize.class).value()).contains("technical:data:task:list");
    assertThat(type.getMethod("preview", Long.class, MultipartFile.class).getAnnotation(PreAuthorize.class).value())
        .contains("technical:data:task:edit", "ingest:quote:cost-run:execute");
    assertThat(type.getDeclaredMethods()).noneMatch(method -> method.getName().equals("delete") || method.getName().equals("applyReference"));
  }
}
