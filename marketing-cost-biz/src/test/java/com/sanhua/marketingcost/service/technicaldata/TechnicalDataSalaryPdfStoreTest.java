package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TechnicalDataSalaryPdfStoreTest {
  @TempDir Path root;
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T02:03:04Z"), ZoneOffset.UTC);

  private TechnicalDataSalaryPdfStore store() {
    return new TechnicalDataSalaryPdfStore(root.toString(), new ObjectMapper(), CLOCK);
  }

  @Test void storesGeneratedNameAndOriginalBytesAcrossRestartWithoutReplacingOtherProducts() throws Exception {
    var store = store();
    byte[] pdf = pdf(1);
    var saved = store.save(1L, "1004000123", pdf, null, 1L);
    assertThat(saved.fileName()).isEqualTo("1004000123工时_20261124100304.pdf");
    assertThat(root.resolve(saved.fileName())).hasBinaryContent(pdf);
    assertThat(root.resolve("1")).doesNotExist();
    var reopened = new TechnicalDataSalaryPdfStore(root.toString(), new ObjectMapper());
    assertThat(reopened.current(1L)).isEqualTo(saved);
    assertThat(reopened.read(1L).bytes()).isEqualTo(pdf);
    assertThat(reopened.current(2L)).isNull();
    assertThatThrownBy(() -> reopened.read(2L)).hasMessageContaining("尚未上传");
    var repeated = store.save(1L, "1004000123", pdf, saved.sha256(), 2L);
    assertThat(repeated).isEqualTo(saved);
    assertThat(root.resolve(saved.fileName())).hasBinaryContent(pdf);
  }

  @Test void rejectsInvalidStaleAndCorruptedFilesWhilePreservingPreviousDocument() throws Exception {
    var store = store();
    byte[] first = pdf(1), second = pdf(2);
    var saved = store.save(1L, "SHF/50", first, null, 1L);
    assertThat(saved.fileName()).isEqualTo("SHF_50工时_20261124100304.pdf");
    assertThatThrownBy(() -> store.save(1L, "SHF/50", "%PDF-broken".getBytes(), saved.sha256(), 1L)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.save(1L, "SHF/50", new byte[TechnicalDataSalaryPdfStore.MAX_BYTES + 1], saved.sha256(), 1L)).hasMessageContaining("10MB");
    assertThatThrownBy(() -> store.save(1L, "SHF/50", second, null, 1L)).hasMessageContaining("其他会话");
    assertThat(store.read(1L).bytes()).isEqualTo(first);
    var updated = store.save(1L, "SHF/50", second, saved.sha256(), 2L);
    assertThat(store.read(1L).bytes()).isEqualTo(second);
    assertThat(Files.readAllBytes(root.resolve(saved.fileName()))).isEqualTo(first);
    Files.write(root.resolve(updated.fileName()), first);
    assertThatThrownBy(() -> store.read(1L)).hasMessageContaining("校验失败");
  }

  @Test void requiresIdentityAndRealPdfInsteadOfOnlyTrustingExtension() throws Exception {
    var store = store();
    assertThatThrownBy(() -> store.save(1L, "X", "not pdf".getBytes(), null, 1L)).hasMessageContaining("有效的PDF");
    assertThatThrownBy(() -> store.save(1L, " ", pdf(1), null, 1L)).hasMessageContaining("料号或型号");
    assertThat(store.save(1L, "  ZJF-A44168-048  ", pdf(1), null, 1L).fileName())
        .isEqualTo("ZJF-A44168-048工时_20261124100304.pdf");
  }

  @Test void identicalMaterialAcrossProductsAndStoreInstancesNeverOverwritesFiles() throws Exception {
    byte[] first = pdf(1), second = pdf(2);
    var one = store().save(1L, "SAME", first, null, 1L);
    var two = store().save(2L, "SAME", second, null, 1L);
    assertThat(two.fileName()).isEqualTo("SAME工时_20261124100304_2.pdf");
    assertThat(store().read(1L).bytes()).isEqualTo(first);
    assertThat(store().read(2L).bytes()).isEqualTo(second);
    assertThat(root.resolve(one.fileName())).hasBinaryContent(first);
  }

  @Test void configurableRootContainsDocumentsAndIndexAndCanBeMovedTogether() throws Exception {
    var customRoot = root.resolve("configured-location");
    var store = new TechnicalDataSalaryPdfStore(customRoot.toString(), new ObjectMapper(), CLOCK);
    byte[] bytes = pdf(1);
    var document = store.save(8L, "MATERIAL", bytes, null, 1L);
    assertThat(customRoot.resolve(document.fileName())).hasBinaryContent(bytes);
    var moved = root.resolve("new-location");
    Files.move(customRoot, moved);
    assertThat(new TechnicalDataSalaryPdfStore(moved.toString(), new ObjectMapper()).read(8L).bytes()).isEqualTo(bytes);
    assertThatThrownBy(() -> new TechnicalDataSalaryPdfStore(" ", new ObjectMapper())).hasMessageContaining("配置");
  }

  @Test void flatStorageKeepsFilenameSanitizationAndValidProductChecks() throws Exception {
    var store = store();
    var saved = store.save(1L, "../A\\B:C", pdf(1), null, 1L);
    assertThat(root.resolve(saved.fileName()).getParent()).isEqualTo(root);
    assertThat(saved.fileName()).isEqualTo(".._A_B_C工时_20261124100304.pdf");
    assertThatThrownBy(() -> store.save(0L, "M", pdf(1), null, 1L)).hasMessageContaining("产品标识");
  }

  static byte[] pdf(int pages) throws Exception {
    try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
      for (int i = 0; i < pages; i++) document.addPage(new PDPage());
      document.save(output); return output.toByteArray();
    }
  }
}
