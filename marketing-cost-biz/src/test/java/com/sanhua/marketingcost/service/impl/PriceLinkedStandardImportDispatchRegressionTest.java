package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportCommand;
import com.sanhua.marketingcost.dto.PriceLinkedType2ImportPreviewResponse;
import com.sanhua.marketingcost.dto.PriceLinkedWorkbookDetectionResult;
import com.sanhua.marketingcost.enums.PriceLinkedWorkbookType;
import com.sanhua.marketingcost.service.PriceLinkedItemService;
import com.sanhua.marketingcost.service.PriceLinkedType2ImportOrchestrator;
import com.sanhua.marketingcost.service.PriceLinkedWorkbookTypeDetector;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("PLI2-10 标准模板分流回归")
class PriceLinkedStandardImportDispatchRegressionTest {

  private static final Path REAL_STANDARD_WORKBOOK =
      Path.of("/Users/xiexicheng/Desktop/demo9/联动价.xls");

  @Test
  @DisplayName("标准模板确认导入原样调用旧Service路径")
  void standardTemplateDelegatesToLegacyImport() {
    Fixture fixture = new Fixture(PriceLinkedWorkbookType.STANDARD);
    PriceItemImportResponse legacy = new PriceItemImportResponse();
    legacy.setLinkedCount(3);
    when(fixture.standard.importExcel(
        any(InputStream.class),
        anyString(),
        anyBoolean(),
        anyString(),
        anyString(),
        anyString(),
        anyString(),
        anyString()))
        .thenReturn(legacy);

    PriceItemImportResponse result = fixture.dispatch.confirm(fixture.command(null));

    assertThat(result.getTemplateType()).isEqualTo("STANDARD");
    assertThat(result.getLinkedCount()).isEqualTo(3);
    assertThat(result.getFileSha256()).hasSize(64);
    verify(fixture.standard).importExcel(
        any(InputStream.class),
        anyString(),
        anyBoolean(),
        anyString(),
        anyString(),
        anyString(),
        anyString(),
        anyString());
    verify(fixture.type2, never()).confirm(any());
  }

  @Test
  @DisplayName("真实demo9工作簿识别为STANDARD并只调用旧Service路径")
  void realStandardWorkbookDelegatesToLegacyImport() throws Exception {
    assertThat(Files.exists(REAL_STANDARD_WORKBOOK))
        .as("真实原标准联动价工作簿存在")
        .isTrue();
    byte[] bytes = Files.readAllBytes(REAL_STANDARD_WORKBOOK);
    assertThat(PriceLinkedImportFileDigest.sha256(bytes))
        .isEqualTo("b1609a65380129a51ee623ce78bad70226ec0ecf0d689b19c4d4b346d12740a1");

    PriceLinkedItemService standard = mock(PriceLinkedItemService.class);
    PriceLinkedType2ImportOrchestrator type2 =
        mock(PriceLinkedType2ImportOrchestrator.class);
    PriceLinkedImportDispatchServiceImpl dispatch =
        new PriceLinkedImportDispatchServiceImpl(
            new PriceLinkedWorkbookTypeDetectorImpl(), standard, type2);
    PriceItemImportResponse legacy = new PriceItemImportResponse();
    legacy.setLinkedCount(7);
    legacy.setFactorRecognizedCount(62);
    when(standard.importExcel(
        any(InputStream.class),
        anyString(),
        anyBoolean(),
        anyString(),
        anyString(),
        any(),
        anyString(),
        anyString()))
        .thenReturn(legacy);
    PriceLinkedImportCommand command = new PriceLinkedImportCommand(
        bytes,
        REAL_STANDARD_WORKBOOK.getFileName().toString(),
        "2026-07",
        "COMMERCIAL",
        false,
        null,
        "2026-07-01",
        "KEEP_EXISTING",
        null);

    PriceItemImportResponse result = dispatch.confirm(command);

    assertThat(result.getTemplateType()).isEqualTo("STANDARD");
    assertThat(result.getLinkedCount()).isEqualTo(7);
    assertThat(result.getFactorRecognizedCount()).isEqualTo(62);
    assertThat(result.getImportDataSheetName()).isNotBlank();
    verify(standard).importExcel(
        any(InputStream.class),
        anyString(),
        anyBoolean(),
        anyString(),
        anyString(),
        any(),
        anyString(),
        anyString());
    verify(type2, never()).confirm(any());
  }

  @Test
  @DisplayName("标准模板预检只识别结构，不调用旧导入写服务")
  void standardPreviewHasNoLegacyWrites() {
    Fixture fixture = new Fixture(PriceLinkedWorkbookType.STANDARD);

    PriceLinkedType2ImportPreviewResponse preview =
        fixture.dispatch.preview(fixture.command(null));

    assertThat(preview.getTemplateType()).isEqualTo("STANDARD");
    assertThat(preview.isCanConfirm()).isTrue();
    verify(fixture.standard, never()).importExcel(
        any(), anyString(), anyBoolean(), anyString(), anyString(),
        anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("类型2模板且SHA一致时只走新编排器")
  void type2TemplateDelegatesToNewOrchestrator() {
    Fixture fixture = new Fixture(PriceLinkedWorkbookType.TYPE2);
    PriceItemImportResponse imported = new PriceItemImportResponse();
    imported.setTemplateType("TYPE2");
    when(fixture.type2.confirm(any())).thenReturn(imported);
    String hash = PriceLinkedImportFileDigest.sha256(fixture.bytes);

    PriceItemImportResponse result = fixture.dispatch.confirm(fixture.command(hash));

    assertThat(result.getTemplateType()).isEqualTo("TYPE2");
    verify(fixture.type2).confirm(any());
    verify(fixture.standard, never()).importExcel(
        any(), anyString(), anyBoolean(), anyString(), anyString(),
        anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("类型2文件被替换时在任何写入编排前拒绝")
  void rejectsType2HashMismatchBeforeOrchestration() {
    Fixture fixture = new Fixture(PriceLinkedWorkbookType.TYPE2);

    assertThatThrownBy(() -> fixture.dispatch.confirm(fixture.command("wrong")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SHA-256");
    verify(fixture.type2, never()).confirm(any());
  }

  @Test
  @DisplayName("UNKNOWN和AMBIGUOUS确认导入均直接失败")
  void rejectsUnknownAndAmbiguousTemplates() {
    for (PriceLinkedWorkbookType type : List.of(
        PriceLinkedWorkbookType.UNKNOWN, PriceLinkedWorkbookType.AMBIGUOUS)) {
      Fixture fixture = new Fixture(type);

      assertThatThrownBy(() -> fixture.dispatch.confirm(fixture.command(null)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(type.name());
      verify(fixture.standard, never()).importExcel(
          any(), anyString(), anyBoolean(), anyString(), anyString(),
          anyString(), anyString(), anyString());
      verify(fixture.type2, never()).confirm(any());
    }
  }

  @Test
  @DisplayName("回滚开关关闭时类型2预检和确认均拒绝，标准模板继续走旧链路")
  void type2RollbackSwitchDoesNotDisableStandardImport() {
    Fixture type2Fixture = new Fixture(PriceLinkedWorkbookType.TYPE2);
    ReflectionTestUtils.setField(type2Fixture.dispatch, "type2ImportEnabled", false);
    String hash = PriceLinkedImportFileDigest.sha256(type2Fixture.bytes);

    assertThatThrownBy(() -> type2Fixture.dispatch.preview(type2Fixture.command(hash)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("类型2联动价导入当前已关闭")
        .hasMessageContaining("原标准联动价模板仍可正常导入");
    assertThatThrownBy(() -> type2Fixture.dispatch.confirm(type2Fixture.command(hash)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("类型2联动价导入当前已关闭");
    verify(type2Fixture.type2, never()).preview(any());
    verify(type2Fixture.type2, never()).confirm(any());

    Fixture standardFixture = new Fixture(PriceLinkedWorkbookType.STANDARD);
    ReflectionTestUtils.setField(standardFixture.dispatch, "type2ImportEnabled", false);
    PriceItemImportResponse legacy = new PriceItemImportResponse();
    legacy.setLinkedCount(3);
    when(standardFixture.standard.importExcel(
        any(InputStream.class),
        anyString(),
        anyBoolean(),
        anyString(),
        anyString(),
        anyString(),
        anyString(),
        anyString()))
        .thenReturn(legacy);

    PriceItemImportResponse result =
        standardFixture.dispatch.confirm(standardFixture.command(null));

    assertThat(result.getTemplateType()).isEqualTo("STANDARD");
    assertThat(result.getLinkedCount()).isEqualTo(3);
    verify(standardFixture.standard).importExcel(
        any(InputStream.class),
        anyString(),
        anyBoolean(),
        anyString(),
        anyString(),
        anyString(),
        anyString(),
        anyString());
  }

  private static final class Fixture {

    private final byte[] bytes = {5, 6, 7};
    private final PriceLinkedWorkbookTypeDetector detector =
        mock(PriceLinkedWorkbookTypeDetector.class);
    private final PriceLinkedItemService standard = mock(PriceLinkedItemService.class);
    private final PriceLinkedType2ImportOrchestrator type2 =
        mock(PriceLinkedType2ImportOrchestrator.class);
    private final PriceLinkedImportDispatchServiceImpl dispatch =
        new PriceLinkedImportDispatchServiceImpl(detector, standard, type2);

    private Fixture(PriceLinkedWorkbookType type) {
      List<String> standardSheets =
          type == PriceLinkedWorkbookType.STANDARD || type == PriceLinkedWorkbookType.TYPE2
              ? List.of("importdata1") : List.of();
      List<String> type2Sheets = type == PriceLinkedWorkbookType.TYPE2
          ? List.of("Sheet1") : List.of();
      when(detector.detect(any(InputStream.class), anyString()))
          .thenReturn(new PriceLinkedWorkbookDetectionResult(
              type, standardSheets, type2Sheets, "detect-" + type));
    }

    private PriceLinkedImportCommand command(String hash) {
      return new PriceLinkedImportCommand(
          bytes,
          "sample.xls",
          "2026-07",
          "COMMERCIAL",
          false,
          "APPEND_ONLY",
          "2026-07-01",
          "KEEP_EXISTING",
          hash);
    }
  }
}
