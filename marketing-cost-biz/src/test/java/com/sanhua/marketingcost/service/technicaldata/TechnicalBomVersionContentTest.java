package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TechnicalBomVersionContentTest {
  private final TechnicalDataVersionContentCodec codec = new TechnicalDataVersionContentCodec(new ObjectMapper().findAndRegisterModules());
  private QuoteTechDataVersion version() {
    var version = new QuoteTechDataVersion();
    version.setId(1L); version.setProductId(2L); version.setContentSchemaVersion(2);
    version.setVersionStatus("DRAFT"); version.setProductModel("NEW-MODEL");
    return version;
  }
  private TechnicalBomContent content() {
    var row = new TechnicalBomRows.Row(2, "1.10", "000001", "子件", "", "", BigDecimal.ONE, "kg", "采购件", "铜", null, "");
    return new TechnicalBomContent(1, "UPLOAD", new TechnicalBomContent.Scope(3L, "OA-1", "2026-11", "COMMERCIAL", "210", "COMMERCIAL", "OA_ITEM_3"),
        new TechnicalBomRows.Product("", "新品", "NEW-MODEL", ""),
        List.of(new TechnicalBomContent.Node(row, "000001", "301", "原材料", "主要材料", "kg", BigDecimal.ONE, BigDecimal.ONE, null)),
        null, new TechnicalBomContent.Upload("BOM.xlsx", "a".repeat(64), 1024, "BOM填写"), "U9_NOT_FOUND:NO_DRAWING", LocalDateTime.of(2026,11,16,12,0));
  }
  @Test void absentNewContentDoesNotChangeLegacyJsonProtocolAndARealChangeAffectsFingerprint() {
    var version = version();
    String legacy = codec.versionContentJson(version, List.of(), List.of(), List.of(), List.of());
    assertThat(legacy).doesNotContain("technicalBom");
    String oldHash = codec.fingerprint(version, List.of(), List.of(), List.of(), List.of());
    version.setTechnicalBomJson(codec.technicalBomJson(content()));
    assertThat(codec.technicalBom(version)).isEqualTo(content());
    assertThat(codec.supplementItemCount(version, "DRAWING_BOM")).isEqualTo(1);
    assertThat(codec.drawingBom(version)).isNull();
    assertThat(codec.fingerprint(version, List.of(), List.of(), List.of(), List.of())).isNotEqualTo(oldHash);
    version.setTechnicalBomJson(null);
    assertThat(codec.versionContentJson(version, List.of(), List.of(), List.of(), List.of())).isEqualTo(legacy);
    assertThat(codec.fingerprint(version, List.of(), List.of(), List.of(), List.of())).isEqualTo(oldHash);
  }
  @Test void scopedCopiesIncludeCompleteBomOnlyForItsOwnModule() {
    var version = version(); version.setTechnicalBomJson(codec.technicalBomJson(content()));
    var bom = TechnicalDataVersionCopies.scopedDraft(version, 2, 1L, LocalDateTime.now(), Set.of("DRAWING_BOM"));
    var price = TechnicalDataVersionCopies.scopedDraft(version, 3, 1L, LocalDateTime.now(), Set.of("PRICE"));
    assertThat(bom.getTechnicalBomJson()).isEqualTo(version.getTechnicalBomJson());
    assertThat(price.getTechnicalBomJson()).isNull();
    TechnicalDataVersionCopies.copyModule(bom, price, "DRAWING_BOM");
    assertThat(price.getTechnicalBomJson()).isEqualTo(bom.getTechnicalBomJson());
    assertThat(version.getTechnicalBomJson()).isNotNull();
  }
  @Test void legacySchemaCannotContainTechnicalBom() {
    var version = version(); version.setContentSchemaVersion(1); version.setTechnicalBomJson(codec.technicalBomJson(content()));
    assertThatThrownBy(() -> codec.schemaVersion(version)).hasMessageContaining("旧版本结构");
  }
}
