package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.mapper.TechnicalBomCandidateMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@DisplayName("QCBP-10 U9相似BOM真实MySQL查询")
class TechnicalBomCandidateMapperIntegrationTest extends BomMapperTestBase {
  @Autowired private TechnicalBomCandidateMapper mapper;
  @Autowired private JdbcTemplate jdbc;
  private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);

  @BeforeAll
  static void ensureSchemas() throws Exception {
    int index = 0;
    for (String resource : List.of(
        "/db/V142__quote_bom_preparation_schema.sql",
        "/db/V206__quote_bom_price_collaboration_schema.sql",
        "/db/V235__optimize_technical_bom_candidate_search.sql")) {
      String target = "/tmp/QCBP10-" + (++index) + ".sql";
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource(resource), target);
      ExecResult result = MYSQL.execInContainer(
          "sh", "-c",
          "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
              + " " + MYSQL.getDatabaseName() + " < " + target);
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(
            resource + " 执行失败：" + result.getStderr() + result.getStdout());
      }
    }
  }

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM lp_bom_raw_hierarchy WHERE top_product_code LIKE ?", "Q10-" + suffix + "%");
    jdbc.update("DELETE FROM lp_material_master_raw WHERE material_code LIKE ?", "Q10-" + suffix + "%");
  }

  @Test
  @DisplayName("只返回任务组织当前有效且有子节点的BOM，并按规格型号精确度排序")
  void filtersOrganizationEffectiveWindowAndRanksExactMatches() {
    String exact = "Q10-" + suffix + "-EXACT";
    String similar = "Q10-" + suffix + "-SIMILAR";
    String otherOrg = "Q10-" + suffix + "-OTHER";
    String expired = "Q10-" + suffix + "-EXPIRED";
    insertMaterial(exact, "COMMERCIAL", "S-952", "M-952");
    insertMaterial(similar, "COMMERCIAL", "S-952", "M-OTHER");
    insertMaterial(otherOrg, "PLATE", "S-952", "M-952");
    insertMaterial(expired, "COMMERCIAL", "S-952", "M-952");
    insertTree(exact, "210", LocalDate.of(2026, 1, 1), null);
    insertTree(similar, "210", LocalDate.of(2026, 1, 1), null);
    insertTree(otherOrg, "220", LocalDate.of(2026, 1, 1), null);
    insertTree(expired, "210", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));

    var rows = mapper.selectCandidates("210", "COMMERCIAL", LocalDate.of(2026, 8, 13),
        null, "S-952", "M-952", null, null, 30);

    assertThat(rows).extracting(row -> row.getProductCode())
        .containsExactly(exact, similar)
        .doesNotContain(otherOrg, expired);
    assertThat(rows.get(0).getMatchScore()).isGreaterThan(rows.get(1).getMatchScore());
    assertThat(rows.get(0).getBomNodeCount()).isEqualTo(2);
  }

  private void insertMaterial(String code, String org, String spec, String model) {
    jdbc.update("""
        INSERT INTO lp_material_master_raw(
          material_code, organization_code, material_name, material_spec, material_model,
          production_category, unit, import_batch_id, source_type, active_flag)
        VALUES (?, ?, ?, ?, ?, '制造件', '件', ?, 'U9_API', 1)
        """, code, org, "产品" + code, spec, model, "Q10-" + suffix);
  }

  private void insertTree(String top, String org, LocalDate from, LocalDate to) {
    insertNode(top, top, top, org, 0, "/" + top + "/", from, to, 1);
    insertNode(top, top, top + "-C", org, 1, "/" + top + "/" + top + "-C/", from, to, 2);
  }

  private void insertNode(
      String top, String parent, String material, String org, int level, String path,
      LocalDate from, LocalDate to, int seq) {
    jdbc.update("""
        INSERT INTO lp_bom_raw_hierarchy(
          price_org_code, top_product_code, parent_code, material_code, level, path, sort_seq,
          source_line_key, qty_per_parent, qty_per_top, material_name, material_spec,
          source_category, bom_purpose, bom_version, bom_status, is_leaf,
          effective_from, effective_to, source_type, source_import_batch_id,
          build_batch_id, built_at, business_unit_type)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 1, ?, 'S-952', '制造件', '主制造',
                'V1', '已核准', ?, ?, ?, 'U9', ?, ?, NOW(), 'COMMERCIAL')
        """, org, top, parent, material, level, path, seq,
        "Q10|" + suffix + "|" + org + "|" + top + "|" + material,
        material, level == 0 ? 0 : 1, from, to,
        "Q10-" + suffix, "Q10-BUILD-" + suffix);
  }
}
