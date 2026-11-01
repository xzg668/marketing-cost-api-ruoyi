package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"srm.fixed-price.enabled=false", "oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false", "monthly-reprice.enabled=false", "cms.sync.publish.enabled=false"})
class CostingMaterialLookupIndexesIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired MaterialMasterRawMapper materials;

  @BeforeAll void indexes() throws Exception {
    try (Connection connection = jdbc.getDataSource().getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/V358__costing_material_lookup_indexes.sql"));
    }
  }

  @Test @Transactional void exactModelKeepsCollationAndScopeWithoutScanningTheOrganization() throws Exception {
    var rows = new ArrayList<Object[]>();
    for (int i = 0; i < 5000; i++) rows.add(new Object[]{"LOOKUP-" + i, "COMMERCIAL", 1, "10", "OTHER-" + i});
    rows.add(new Object[]{"LOOKUP-EXACT", "COMMERCIAL", 1, "10", "  Lookúp-Model  "});
    rows.add(new Object[]{"LOOKUP-PREFIX-COLLISION", "COMMERCIAL", 1, "10", "lookup-model-other"});
    rows.add(new Object[]{"LOOKUP-INACTIVE", "COMMERCIAL", 0, "10", "lookup-model"});
    rows.add(new Object[]{"LOOKUP-COMPONENT", "COMMERCIAL", 1, "20", "lookup-model"});
    rows.add(new Object[]{"LOOKUP-PLATE", "PLATE", 1, "11", "lookup-model"});
    jdbc.batchUpdate("INSERT INTO lp_material_master_raw(material_code,organization_code,active_flag,main_category_code,material_model,import_batch_id) VALUES(?,?,?,?,?,'LOOKUP-PERFORMANCE')", rows);
    assertThat(materials.selectNetLossProducts("MODEL", "lookup-model", true, "COMMERCIAL", 2))
        .extracting(row -> row.getMaterialCode()).containsExactly("LOOKUP-EXACT");
    assertThat(materials.selectNetLossProducts("MODEL", "NOT-FOUND", true, "COMMERCIAL", 2)).isEmpty();
    String sql = "SELECT * FROM lp_material_master_raw WHERE active_flag=1 AND organization_code='COMMERCIAL' "
        + "AND (main_category_code LIKE '10%' OR main_category_code LIKE '11%') "
        + "AND LEFT(TRIM(material_model),8)=LEFT('NOT-FOUND',8) AND TRIM(material_model)='NOT-FOUND' ORDER BY material_code LIMIT 2";
    assertUsesIndex(sql, "idx_raw_active_model_prefix");
    assertUsesIndex("SELECT * FROM lp_material_master_raw WHERE material_code IN ('LOOKUP-EXACT','LOOKUP-PLATE') AND active_flag=1 ORDER BY id",
        "idx_raw_material_active");
    assertThat(jdbc.queryForList("SELECT material_code FROM lp_material_master_raw WHERE material_code IN ('LOOKUP-EXACT','LOOKUP-PLATE') AND active_flag=1 ORDER BY id", String.class))
        .containsExactly("LOOKUP-EXACT", "LOOKUP-PLATE");
  }

  private void assertUsesIndex(String sql, String index) throws Exception {
    var plan = new ObjectMapper().readTree(jdbc.queryForObject("EXPLAIN FORMAT=JSON " + sql, String.class));
    assertThat(plan.findValuesAsText("key")).contains(index);
    assertThat(plan.findValues("rows_examined_per_scan")).allSatisfy(rows -> assertThat(rows.asInt()).isLessThan(10));
  }
}
