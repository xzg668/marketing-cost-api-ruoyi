package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

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
  private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM lp_material_master_raw WHERE material_code LIKE ?", "EDX-" + suffix + "%");
  }

  @Test
  void returnsOnlyCurrentOrganizationExactIdentityCandidates() {
    insert("EDX-" + suffix + "-A", "COMMERCIAL", "DRAW-" + suffix, null, null, 1);
    insert("EDX-" + suffix + "-B", "COMMERCIAL", "DRAW-" + suffix, null, null, 1);
    insert("EDX-" + suffix + "-SPEC", "COMMERCIAL", null, "SPEC-" + suffix, null, 1);
    insert("EDX-" + suffix + "-OTHER", "PLATE", "DRAW-" + suffix, null, null, 1);
    insert("EDX-" + suffix + "-INACTIVE", "COMMERCIAL", "DRAW-" + suffix, null, null, 0);

    var rows = mapper.selectByDrawingIdentities(
        List.of(("DRAW-" + suffix).toUpperCase(), ("SPEC-" + suffix).toUpperCase()),
        null, "COMMERCIAL", 50);

    assertThat(rows).extracting(row -> row.getMaterialCode())
        .containsExactly("EDX-" + suffix + "-A", "EDX-" + suffix + "-B", "EDX-" + suffix + "-SPEC")
        .doesNotContain("EDX-" + suffix + "-OTHER", "EDX-" + suffix + "-INACTIVE");
  }

  private void insert(
      String code, String org, String drawing, String spec, String model, int active) {
    jdbc.update("""
        INSERT INTO lp_material_master_raw(
          material_code, organization_code, material_name, material_spec, material_model,
          drawing_no, shape_attr, unit, import_batch_id, source_type, active_flag)
        VALUES (?, ?, ?, ?, ?, ?, '采购件', '件', ?, 'U9_API', ?)
        """, code, org, "名称" + code, spec, model, drawing, "EDX-" + suffix, active);
  }
}
