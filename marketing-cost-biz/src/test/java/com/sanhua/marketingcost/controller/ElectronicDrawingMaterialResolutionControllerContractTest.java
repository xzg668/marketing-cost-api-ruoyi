package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialResolutionRequest;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialSearchResponse;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@DisplayName("电子图库财务选料接口契约")
class ElectronicDrawingMaterialResolutionControllerContractTest {

  @Test
  void staysInsideQuotationFlowAndSeparatesReadFromSavePermission() throws Exception {
    RequestMapping root = ElectronicDrawingMaterialResolutionController.class
        .getAnnotation(RequestMapping.class);
    assertThat(root.value())
        .containsExactly("/api/v1/quote-requests/electronic-drawing/tasks");

    Method state = ElectronicDrawingMaterialResolutionController.class
        .getMethod("state", Long.class, String.class);
    assertThat(state.getAnnotation(GetMapping.class).value())
        .containsExactly("/{taskId}/material-resolution");
    assertThat(state.getAnnotation(PreAuthorize.class).value()).contains("ingest:quote:list");

    Method search = ElectronicDrawingMaterialResolutionController.class.getMethod(
        "search", Long.class, Long.class, String.class, String.class, Integer.class, String.class);
    assertThat(search.getAnnotation(GetMapping.class).value())
        .containsExactly("/{taskId}/material-options");
    assertThat(search.getAnnotation(PreAuthorize.class).value()).contains("ingest:quote:list");

    Method apply = ElectronicDrawingMaterialResolutionController.class.getMethod(
        "apply", Long.class, ElectronicDrawingMaterialResolutionRequest.class, String.class);
    assertThat(apply.getAnnotation(PutMapping.class).value())
        .containsExactly("/{taskId}/material-resolutions");
    assertThat(apply.getAnnotation(PreAuthorize.class).value())
        .contains("ingest:quote:cost-run:execute");
    assertThat(Arrays.stream(apply.getAnnotations()).map(Object::toString))
        .noneMatch(annotation -> annotation.contains("quoteCollaborationSummaries"));
  }

  @Test
  void searchContractContainsNoRecommendationOrPreselectedCandidateField() {
    assertThat(Arrays.stream(ElectronicDrawingMaterialSearchResponse.class.getRecordComponents())
        .map(RecordComponent::getName))
        .containsExactly("productTaskId", "sourceVersionId", "searchType", "keyword", "options")
        .noneMatch(name -> name.toLowerCase().contains("recommend"));
    assertThat(Arrays.stream(
            ElectronicDrawingMaterialSearchResponse.Option.class.getRecordComponents())
        .map(RecordComponent::getName))
        .noneMatch(name -> name.toLowerCase().contains("recommend")
            || name.toLowerCase().contains("selected"));
  }
}
