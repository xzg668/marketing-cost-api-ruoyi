package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@DisplayName("电子图库图号匹配真实 MySQL 查询")
class ElectronicDrawingMaterialMapperIntegrationTest extends BomMapperTestBase {
  @Autowired private MaterialMasterRawMapper mapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ElectronicDrawingProductLookup lookup;
  private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);

  @AfterEach
  void clean() {
    jdbc.update(
        "DELETE FROM lp_material_master_raw WHERE material_code LIKE ?", "EDX-" + suffix + "%");
  }

  @Test
  void returnsOnlyCurrentOrganizationExactIdentityCandidates() {
    insert("EDX-" + suffix + "-A", "COMMERCIAL", "DRAW-" + suffix, null, null, 1);
    insert("EDX-" + suffix + "-B", "COMMERCIAL", "DRAW-" + suffix, null, null, 1);
    insert("EDX-" + suffix + "-SPEC", "COMMERCIAL", null, "SPEC-" + suffix, null, 1);
    insert("EDX-" + suffix + "-OTHER", "PLATE", "DRAW-" + suffix, null, null, 1);
    insert("EDX-" + suffix + "-INACTIVE", "COMMERCIAL", "DRAW-" + suffix, null, null, 0);

    var rows =
        mapper.selectByDrawingNumbers(
            List.of(("DRAW-" + suffix).toUpperCase(), ("SPEC-" + suffix).toUpperCase()),
            "COMMERCIAL");

    assertThat(rows)
        .extracting(row -> row.getMaterialCode())
        .containsExactly("EDX-" + suffix + "-A", "EDX-" + suffix + "-B")
        .doesNotContain("EDX-" + suffix + "-OTHER", "EDX-" + suffix + "-INACTIVE");
  }

  @Test
  void productCandidatesUseExactSpecificationActiveRowsAndTheCurrentOrganization() {
    String drawing = "DRAW-" + suffix;
    String model = "MODEL-" + suffix;
    String code = "EDX-" + suffix + "-A";
    insert(code, "COMMERCIAL", drawing, "SPEC-A", model, 1);
    insert("EDX-" + suffix + "-B", "COMMERCIAL", "DRAW-B", "SPEC-B", model, 1);
    insert(code, "PLATE", "OTHER-DRAW", "SPEC-A", model, 1);
    insert("EDX-" + suffix + "-INACTIVE", "COMMERCIAL", drawing, "SPEC-A", model, 0);
    assertThat(mapper.selectDrawingProductCandidates(null, model, "SPEC-A", "COMMERCIAL"))
        .singleElement()
        .satisfies(row -> assertThat(row.getDrawingNo()).isEqualTo(drawing));
    assertThat(mapper.selectDrawingProductCandidates(code, "WRONG-MODEL", null, "COMMERCIAL"))
        .isEmpty();
    assertThat(mapper.selectDrawingProductCandidates(code, null, null, "COMMERCIAL"))
        .singleElement()
        .satisfies(row -> assertThat(row.getDrawingNo()).isEqualTo(drawing));
    assertThat(mapper.selectDrawingProductCandidates(null, model, null, "COMMERCIAL")).hasSize(2);
    assertThat(mapper.selectDrawingProductCandidates(null, model, "SPEC", "COMMERCIAL")).isEmpty();
    assertThat(mapper.selectDrawingProductCandidates(null, null, "SPEC-A", "COMMERCIAL")).isEmpty();
    assertThat(mapper.selectDrawingProductCandidates(null, null, null, "COMMERCIAL")).isEmpty();
  }

  @Test
  void actualLookupUsesMaterialCodeDespiteConflictingOtherQuoteFields() {
    String code = "EDX-" + suffix + "-A";
    insert(code, "COMMERCIAL", "ACTUAL-DRAW", "ACTUAL-SPEC", "ACTUAL-MODEL", 1);
    insert("EDX-" + suffix + "-B", "COMMERCIAL", "OTHER-DRAW", "QUOTE-SPEC", "QUOTE-MODEL", 1);
    var context = quote(code, "QUOTE-MODEL", "QUOTE-SPEC", "QUOTE-DRAW");
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("ACTUAL-DRAW-MX");
    assertThat(lookup.options(context))
        .singleElement()
        .satisfies(option -> assertThat(option.materialNo()).isEqualTo(code));
  }

  @Test
  void actualLookupUsesModelSpecificationEvenWhenTheSameDrawingHasOtherSpecifications() {
    String model = "MODEL-" + suffix;
    insert("EDX-" + suffix + "-A", "COMMERCIAL", "SHARED-DRAW", "SPEC-A", model, 1);
    insert("EDX-" + suffix + "-B", "COMMERCIAL", "SHARED-DRAW", "SPEC-B", model, 1);
    var context = quote(null, model, "SPEC-B", "OLD-DRAW");
    assertThat(lookup.options(context))
        .singleElement()
        .satisfies(option -> assertThat(option.materialNo()).endsWith("-B"));
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("SHARED-DRAW-MX");
  }

  @Test
  void actualLookupFallsBackToModelOnlyAfterNoExactSpecificationRows() {
    String code = "EDX-" + suffix + "-A";
    String model = "MODEL-" + suffix;
    insert(code, "COMMERCIAL", "FALLBACK-DRAW", "SPEC-A", model, 1);
    insert("EDX-" + suffix + "-OTHER", "PLATE", "OTHER-DRAW", "UNKNOWN", model, 1);
    insert("EDX-" + suffix + "-INACTIVE", "COMMERCIAL", "INACTIVE-DRAW", "UNKNOWN", model, 0);
    assertThat(lookup.requireDrawing(quote(null, model, "UNKNOWN", null), null))
        .isEqualTo("FALLBACK-DRAW-MX");
  }

  @Test
  void actualLookupAllowsMissingSpecificationAndKeepsAmbiguousModelCandidates() {
    String model = "MODEL-" + suffix;
    insert("EDX-" + suffix + "-A", "COMMERCIAL", "DRAW-A", "SPEC-A", model, 1);
    var context = quote(null, model, " ", null);
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("DRAW-A-MX");
    insert("EDX-" + suffix + "-B", "COMMERCIAL", "DRAW-B", "SPEC-B", model, 1);
    assertThat(lookup.options(context)).hasSize(2);
    assertThatThrownBy(() -> lookup.requireDrawing(context, null)).hasMessageContaining("唯一图号");
  }

  @Test
  void actualLookupUsesOneDrawingWhileRetainingBothMaterialCandidates() {
    String model = "MODEL-" + suffix;
    String drawing = "DRAW-" + suffix;
    insert("EDX-" + suffix + "-A", "COMMERCIAL", drawing, "SPEC-A", model, 1);
    insert("EDX-" + suffix + "-B", "COMMERCIAL", drawing, "SPEC-A", model, 1);
    insert("EDX-" + suffix + "-OTHER", "PLATE", "OTHER-DRAW", "SPEC-A", model, 1);
    insert("EDX-" + suffix + "-INACTIVE", "COMMERCIAL", "INACTIVE-DRAW", "SPEC-A", model, 0);
    var context = quote(null, model, "SPEC-A", null);
    assertThat(lookup.options(context)).hasSize(2);
    assertThat(lookup.drawingOptions(context))
        .singleElement()
        .satisfies(option -> {
          assertThat(option.drawingNo()).isEqualTo(drawing + "-MX");
          assertThat(option.materialNo()).isNull();
        });
    assertThat(lookup.requireDrawing(context, null)).isEqualTo(drawing + "-MX");
    assertThat(lookup.requireDrawing(context, drawing)).isEqualTo(drawing + "-MX");
  }

  @Test
  void actualLookupDoesNotBroadenAnAmbiguousExactSpecification() {
    String model = "MODEL-" + suffix;
    insert("EDX-" + suffix + "-A", "COMMERCIAL", "DRAW-A", "SPEC-A", model, 1);
    insert("EDX-" + suffix + "-B", "COMMERCIAL", "DRAW-B", "SPEC-A", model, 1);
    insert("EDX-" + suffix + "-C", "COMMERCIAL", "DRAW-C", "SPEC-C", model, 1);
    var context = quote(null, model, "SPEC-A", null);
    assertThat(lookup.options(context)).hasSize(2);
    assertThatThrownBy(() -> lookup.requireDrawing(context, null)).hasMessageContaining("唯一图号");
  }

  @Test
  void exactDrawingDoesNotMatchMaterialCodeSpecModelOrAccentFolding() {
    String drawing = "D-" + suffix;
    insert("EDX-" + suffix + "-SPEC", "COMMERCIAL", null, drawing, null, 1);
    insert("EDX-" + suffix + "-MODEL", "COMMERCIAL", null, null, drawing, 1);
    insert("EDX-" + suffix + "-ACCENT", "COMMERCIAL", "É-" + suffix, null, null, 1);
    assertThat(
            mapper.selectByDrawingNumbers(
                List.of(
                    drawing.toUpperCase(),
                    ("EDX-" + suffix + "-SPEC").toUpperCase(),
                    ("E-" + suffix).toUpperCase()),
                "COMMERCIAL"))
        .isEmpty();
  }

  private void insert(
      String code, String org, String drawing, String spec, String model, int active) {
    jdbc.update(
        """
        INSERT INTO lp_material_master_raw(
          material_code, organization_code, material_name, material_spec, material_model,
          drawing_no, shape_attr, unit, import_batch_id, source_type, active_flag)
        VALUES (?, ?, ?, ?, ?, ?, '采购件', '件', ?, 'U9_API', ?)
        """,
        code,
        org,
        "名称" + code,
        spec,
        model,
        drawing,
        "EDX-" + suffix,
        active);
  }

  private ElectronicDrawingWorkContext quote(
      String code, String model, String specification, String drawing) {
    String oaNo = "EDX-" + suffix;
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,'COMMERCIAL')", oaNo);
    Long formId = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, oaNo);
    jdbc.update(
        "INSERT INTO"
            + " oa_form_item(oa_form_id,material_no,sunl_model,spec,customer_drawing,business_unit_type)"
            + " VALUES(?,?,?,?,?,'COMMERCIAL')",
        formId,
        code,
        model,
        specification,
        drawing);
    Long itemId =
        jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, formId);
    return new ElectronicDrawingWorkContext(
        itemId,
        0,
        null,
        null,
        formId,
        itemId,
        "TEST",
        oaNo,
        code,
        null,
        null,
        specification,
        model,
        "NON_BARE",
        "FULL_BOM",
        "2026-10",
        "210",
        "COMMERCIAL",
        "COMMERCIAL",
        "210",
        true,
        true,
        "NEED_TECH",
        null,
        null,
        null,
        null);
  }
}
