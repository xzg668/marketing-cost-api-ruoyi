package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.service.jjb.*;
import java.io.*;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class JjbExportServiceTest {
  @Test
  void bothEntryPointsRenderTheExactModelSelectedByTheOwnedVersionReader() throws Exception {
    var reader = mock(JjbReadService.class);
    var model = JjbExcelRendererTest.model(2, 2, 1);
    var view = new JjbReadService.View(model, List.of(), List.of());
    when(reader.read("OA", 72L, 71L)).thenReturn(view);
    when(reader.resolve("OA", "P", 72L, 71L, "RUN")).thenReturn(view);
    var service = new JjbExportService(reader, new JjbExcelRenderer());
    var explicit = new ByteArrayOutputStream();
    var legacy = new ByteArrayOutputStream();
    assertThat(service.exportVersion("OA", 72L, 71L, explicit)).isEqualTo(model.rows().size());
    assertThat(service.export("OA", "P", 72L, 71L, "RUN", legacy)).isEqualTo(model.rows().size());
    try (var a = new XSSFWorkbook(new ByteArrayInputStream(explicit.toByteArray()));
        var b = new XSSFWorkbook(new ByteArrayInputStream(legacy.toByteArray()))) {
      assertThat(a.getSheetAt(0).getCTWorksheet().xmlText())
          .isEqualTo(b.getSheetAt(0).getCTWorksheet().xmlText());
      assertThat(a.getNumberOfSheets()).isEqualTo(1);
      assertThat(b.getNumberOfSheets()).isEqualTo(1);
    }
    verify(reader).read("OA", 72L, 71L);
    verify(reader).resolve("OA", "P", 72L, 71L, "RUN");
    verifyNoMoreInteractions(reader);
  }

  @Test
  void rejectedOwnershipCannotStartAnXlsxStream() {
    var reader = mock(JjbReadService.class);
    var renderer = mock(JjbExcelRenderer.class);
    var output = new ByteArrayOutputStream();
    when(reader.read("OTHER", 72L, 71L)).thenThrow(new AccessDeniedException("denied"));
    assertThatThrownBy(
            () -> new JjbExportService(reader, renderer).exportVersion("OTHER", 72L, 71L, output))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(output.size()).isZero();
    verifyNoInteractions(renderer);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.NullAndEmptySource
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"RUNNING", "TRIAL", "FAILED", "CANCELLED"})
  void incompleteVersionsNeverProduceDownloadBytes(String status) {
    var reader = mock(JjbReadService.class);
    var renderer = mock(JjbExcelRenderer.class);
    var base = JjbExcelRendererTest.model(0, 0, 0);
    var id = base.identity();
    var identity =
        new com.sanhua.marketingcost.dto.jjb.JjbReadModel.Identity(
            id.versionId(),
            id.costRunNo(),
            id.versionNo(),
            id.oaNo(),
            id.oaFormItemId(),
            id.productCode(),
            id.businessUnitType(),
            id.pricingMonth(),
            id.algorithmVersion(),
            id.feePolicyVersion(),
            status,
            id.monthly());
    var model =
        new com.sanhua.marketingcost.dto.jjb.JjbReadModel(
            identity,
            base.header(),
            base.rows(),
            base.monthlyPrices(),
            base.totals(),
            base.warnings());
    when(reader.read("OA", 72L, 71L))
        .thenReturn(new JjbReadService.View(model, List.of(), List.of()));
    var output = new ByteArrayOutputStream();
    assertThatThrownBy(
            () -> new JjbExportService(reader, renderer).exportVersion("OA", 72L, 71L, output))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("成功保存");
    assertThat(output.size()).isZero();
    verifyNoInteractions(renderer);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"SUCCESS", "CONFIRMED", "HISTORY", "VOIDED", "STALE"})
  void historicalStatusVocabularyRemainsExportable(String status) throws Exception {
    var reader = mock(JjbReadService.class);
    var renderer = mock(JjbExcelRenderer.class);
    var base = JjbExcelRendererTest.model(0, 0, 0);
    var id = base.identity();
    var identity =
        new com.sanhua.marketingcost.dto.jjb.JjbReadModel.Identity(
            id.versionId(),
            id.costRunNo(),
            id.versionNo(),
            id.oaNo(),
            id.oaFormItemId(),
            id.productCode(),
            id.businessUnitType(),
            id.pricingMonth(),
            id.algorithmVersion(),
            id.feePolicyVersion(),
            status,
            id.monthly());
    var model =
        new com.sanhua.marketingcost.dto.jjb.JjbReadModel(
            identity,
            base.header(),
            base.rows(),
            base.monthlyPrices(),
            base.totals(),
            base.warnings());
    when(reader.read("OA", 72L, 71L))
        .thenReturn(new JjbReadService.View(model, List.of(), List.of()));
    var output = new ByteArrayOutputStream();
    when(renderer.write(model, output)).thenReturn(5);
    assertThat(new JjbExportService(reader, renderer).exportVersion("OA", 72L, 71L, output))
        .isEqualTo(5);
    verify(renderer).write(model, output);
  }
}
