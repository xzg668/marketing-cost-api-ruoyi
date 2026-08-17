package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.integration.drawing.ElectronicBomFetchResult;
import com.sanhua.marketingcost.integration.drawing.ElectronicBomNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-11 电子图库BOM本地完整性校验")
class ElectronicBomStructureValidatorTest {
  private final ElectronicBomStructureValidator validator = new ElectronicBomStructureValidator();

  @Test
  void normalizesCompleteParentChildTreeAndCumulativeQuantity() {
    var result = validator.validate(valid(), "P-1", "COMMERCIAL", "主制造",
        LocalDate.of(2026, 8, 13));

    assertThat(result.passed()).isTrue();
    assertThat(result.bom().nodes()).hasSize(2);
    assertThat(result.bom().nodes().get(1).parentMaterialCode()).isEqualTo("P-1");
    assertThat(result.bom().nodes().get(1).quantityToTop())
        .isEqualByComparingTo("0.286");
    assertThat(result.bom().nodes().get(1).path()).isEqualTo("/P-1/C-1/");
  }

  @Test
  void rejectsIncompleteManufactureCycleOrphanAndReportedLevelMismatch() {
    ElectronicBomFetchResult result = result(List.of(
        node("R", null, 0, "P-1", "MANUFACTURE", "1"),
        node("A", "B", 1, "A-1", "MANUFACTURE", "1"),
        node("B", "A", 2, "B-1", "PURCHASE", "1"),
        node("O", "MISSING", 1, "O-1", "PURCHASE", "1")));

    var validation = validator.validate(result, "P-1", "COMMERCIAL", "主制造",
        LocalDate.of(2026, 8, 13));

    assertThat(validation.passed()).isFalse();
    assertThat(validation.issues()).extracting(ElectronicBomValidationIssue::code)
        .contains("ORPHAN_NODE", "BOM_CYCLE");
  }

  @Test
  void rejectsWrongTargetInactiveVersionBadUnitQuantityAndLeafManufacture() {
    ElectronicBomFetchResult invalid = new ElectronicBomFetchResult(
        ElectronicBomFetchResult.Status.FOUND, null, "DRAWING", "OTHER", "PLATE",
        "其他", "V1", "DRAFT", LocalDate.of(2027, 1, 1), null,
        null, List.of(new ElectronicBomNode("R", null, 4, "OTHER", "产品", null,
            null, null, "MANUFACTURE", BigDecimal.ZERO, null, 1, false)));

    var validation = validator.validate(invalid, "P-1", "COMMERCIAL", "主制造",
        LocalDate.of(2026, 8, 13));

    assertThat(validation.issues()).extracting(ElectronicBomValidationIssue::code)
        .contains("PRODUCT_MISMATCH", "MATERIAL_ORG_MISMATCH", "BOM_PURPOSE_MISMATCH",
            "VERSION_NOT_ACTIVE", "VERSION_NOT_EFFECTIVE", "QUERY_TIME_REQUIRED",
            "ROOT_PRODUCT_MISMATCH", "UNIT_REQUIRED", "QUANTITY_INVALID", "NODE_NOT_ACTIVE",
            "CHILD_REQUIRED", "LEVEL_MISMATCH");
  }

  private ElectronicBomFetchResult valid() {
    return result(List.of(
        node("R", null, 0, "P-1", "MANUFACTURE", "1"),
        node("C", "R", 1, "C-1", "PURCHASE", "0.286")));
  }

  private ElectronicBomFetchResult result(List<ElectronicBomNode> nodes) {
    return new ElectronicBomFetchResult(ElectronicBomFetchResult.Status.FOUND, null,
        "ELECTRONIC_DRAWING", "P-1", "COMMERCIAL", "主制造", "V3", "ACTIVE",
        LocalDate.of(2026, 1, 1), null,
        OffsetDateTime.parse("2026-08-13T10:00:00+08:00"), nodes);
  }

  private ElectronicBomNode node(
      String key, String parent, int level, String code, String nature, String quantity) {
    return new ElectronicBomNode(key, parent, level, code, "名称" + code, "规格", "型号",
        "图号", nature, new BigDecimal(quantity), "件", level + 1, true);
  }
}
