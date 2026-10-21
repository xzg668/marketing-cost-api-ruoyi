package com.sanhua.marketingcost.service.platematerial;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static com.sanhua.marketingcost.service.platematerial.PlateMaterialResolverTest.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.PlateMaterialMappingMapper;
import com.sanhua.marketingcost.mapper.PlateMaterialMasterQuery;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialResolver.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@Transactional
class PlateMaterialResolverIntegrationTest extends BomMapperTestBase {
  @Autowired PlateMaterialResolver resolver;
  @Autowired PlateMaterialMappingService mappings;
  @Autowired PlateMaterialMappingMapper mappingMapper;
  @Autowired PlateMaterialMasterQuery master;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

  @BeforeEach void isolateMappings() {
    // Rolled back with this test; other integration classes may retain their import fixtures.
    jdbc.update("DELETE FROM lp_plate_material_mapping");
  }

  @Test void configurationWinsForPresentAbsentAndOtherDivisionMasterRows() {
    for (String model : List.of("PMM-LOCK-PLATE", "PMM-LOCK-OTHER", "B12-8H-V20")) {
      mappings.create("COMMERCIAL", model, "水水316L", 101);
    }
    material("PMM-LOCK-PLATE", "PLATE", "板换事业部", 1);
    material("PMM-LOCK-OTHER", "PLATE", "电子事业部", 1);
    for (String model : List.of("PMM-LOCK-PLATE", "PMM-LOCK-OTHER", "B12-8H-V20")) {
      var result = resolver.resolve(context(model));
      assertThat(result.mode()).isEqualTo(PlateMaterialMode.CONFIG_LOCKED);
      assertThat(result.value()).isEqualTo(PlateMaterial.WATER_WATER_316L);
      assertThat(result.masterMatches()).isEmpty();
    }
    assertThat(resolver.resolve(new Context("HOUSEHOLD", "B12-8H-V20", "PLATE")).mode())
        .isEqualTo(PlateMaterialMode.HIDDEN);
  }

  @Test void activeCurrentOrganizationAndProductionDivisionAloneDetermineManualSelection() {
    material("PMM-MIX", "PLATE", "电子事业部", 1);
    Long plate = material("PMM-MIX", "PLATE", "板换事业部", 1);
    Long second = material("PMM-MIX", "PLATE", "板换事业部", 1);
    var mixed = resolver.resolve(context("PMM-MIX"));
    assertThat(mixed.mode()).isEqualTo(PlateMaterialMode.MANUAL_REQUIRED);
    assertThat(mixed.source().materialMasterIds()).containsExactly(plate, second);
    assertThat(mixed.masterMatches()).hasSize(3);
    assertThat(resolver.resolve(new Context("COMMERCIAL", "PMM-MIX", "COMMERCIAL")).mode())
        .isEqualTo(PlateMaterialMode.HIDDEN);
    material("PMM-INACTIVE", "PLATE", "板换事业部", 0);
    Long wrongDepartment = material("PMM-DEPARTMENT", "PLATE", "电子事业部", 1);
    jdbc.update("UPDATE lp_material_master_raw SET department_name='板换事业部' WHERE id=?", wrongDepartment);
    material("PMM-NULL", "PLATE", null, 1);
    material("PMM-EMPTY", "PLATE", "", 1);
    for (String model : List.of("PMM-INACTIVE", "PMM-DEPARTMENT", "PMM-NULL", "PMM-EMPTY", "PMM-UNKNOWN")) {
      assertThat(resolver.resolve(context(model)).mode()).as(model).isEqualTo(PlateMaterialMode.HIDDEN);
    }
  }

  @Test void binaryTrimmedModelMatchingDoesNotMergeCaseInternalSpacesHyphensSuffixesOrWidth() {
    Long id = material("  PMM-Ab  ", "PLATE", "板换事业部", 1);
    assertThat(resolver.resolve(context(" PMM-Ab ")).source().materialMasterIds()).containsExactly(id);
    for (String other : List.of("PMM-ab", "PMM-A b", "PMMAb", "PMM-Ab-L", "ＰＭＭ-Ab")) {
      assertThat(resolver.resolve(context(other)).mode()).as(other).isEqualTo(PlateMaterialMode.HIDDEN);
    }
    mappings.create("COMMERCIAL", "PMM-Case", "水氟316L", 101);
    assertThat(resolver.resolve(context(" PMM-Case ")).mode()).isEqualTo(PlateMaterialMode.CONFIG_LOCKED);
    assertThat(resolver.resolve(context("PMM-case")).mode()).isEqualTo(PlateMaterialMode.HIDDEN);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void readsDoNotMutateRowsAndRevisionDetectsMasterOrConfigurationChanges() {
    // Separate committed requests, as in GET -> master sync -> next GET; no test-wide MyBatis cache.
    Long id = material("PMM-CHANGE", "PLATE", "板换事业部", 1);
    try {
      var before = jdbc.queryForMap("SELECT * FROM lp_material_master_raw WHERE id=?", id);
      var manual = resolver.resolve(context("PMM-CHANGE"));
      assertThat(resolver.resolve(context("PMM-CHANGE"))).isEqualTo(manual);
      assertThat(jdbc.queryForMap("SELECT * FROM lp_material_master_raw WHERE id=?", id)).isEqualTo(before);
      jdbc.update("UPDATE lp_material_master_raw SET production_division='电子事业部' WHERE id=?", id);
      var hidden = resolver.resolve(context("PMM-CHANGE"));
      assertThat(hidden.mode()).isEqualTo(PlateMaterialMode.HIDDEN);
      assertThat(hidden.revision()).isNotEqualTo(manual.revision());
      var mapping = mappings.create("COMMERCIAL", "PMM-CHANGE", "水氟316L", 101);
      var locked = resolver.resolve(context("PMM-CHANGE"));
      assertThat(locked.revision()).isNotEqualTo(hidden.revision());
      mappings.update("COMMERCIAL", mapping.getId(), "PMM-CHANGE", "水水316L", 0, 101);
      var updated = resolver.resolve(context("PMM-CHANGE"));
      assertThat(updated.value()).isEqualTo(PlateMaterial.WATER_WATER_316L);
      assertThat(updated.revision()).isNotEqualTo(locked.revision());
      assertThat(manual.mode()).isEqualTo(PlateMaterialMode.MANUAL_REQUIRED);
      assertThat(locked.value()).isEqualTo(PlateMaterial.WATER_FLUORINE_316L);
    } finally {
      jdbc.update("DELETE FROM lp_plate_material_mapping WHERE business_unit_type='COMMERCIAL' AND normalized_model='PMM-CHANGE'");
      jdbc.update("DELETE FROM lp_material_master_raw WHERE id=?", id);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void sameTransactionRechecksExternallyCommittedConfigurationAndMasterChanges() throws Exception {
    String model = "PMM-FRESH-" + UUID.randomUUID();
    Long id = material(model, "PLATE", "板换事业部", 1);
    var transaction = new TransactionTemplate(transactions);
    transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    try {
      transaction.executeWithoutResult(status -> {
        var before = resolver.resolve(context(model));
        assertThat(before.mode()).isEqualTo(PlateMaterialMode.MANUAL_REQUIRED);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
          executor.submit(() -> jdbc.update("UPDATE lp_material_master_raw SET active_flag=0 WHERE id=?", id))
              .get(10, java.util.concurrent.TimeUnit.SECONDS);
          var hidden = resolver.resolve(context(model));
          assertThat(hidden.mode()).isEqualTo(PlateMaterialMode.HIDDEN);
          assertThat(hidden.revision()).isNotEqualTo(before.revision());
          var mapping = executor.submit(() -> mappings.create("COMMERCIAL", model, "水水316L", 101))
              .get(10, java.util.concurrent.TimeUnit.SECONDS);
          assertThat(resolver.resolve(context(model)).value()).isEqualTo(PlateMaterial.WATER_WATER_316L);
          executor.submit(() -> mappings.update("COMMERCIAL", mapping.getId(), model, "水氟316L", 0, 101))
              .get(10, java.util.concurrent.TimeUnit.SECONDS);
          assertThat(resolver.resolve(context(model)).value()).isEqualTo(PlateMaterial.WATER_FLUORINE_316L);
        } catch (Exception failure) { throw new AssertionError(failure); }
      });
    } finally {
      jdbc.update("DELETE FROM lp_plate_material_mapping WHERE normalized_model=?", model);
      jdbc.update("DELETE FROM lp_material_master_raw WHERE id=?", id);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void representativeBatchUsesIndexedLookupAndThreeQueriesForThreeHundredProductContexts() throws Exception {
    try {
      List<Object[]> fixtures = new ArrayList<>();
      for (int i = 0; i < 12000; i++) {
        String model = "PMM-PERF-" + String.format("%05d", i);
        fixtures.add(new Object[] {"PLATE", model, model, "板换事业部"});
        fixtures.add(new Object[] {"COMMERCIAL", model, model, "电子事业部"});
      }
      new TransactionTemplate(transactions).executeWithoutResult(status -> jdbc.batchUpdate("""
          INSERT INTO lp_material_master_raw(organization_code,material_code,material_model,production_division,
            active_flag,import_batch_id) VALUES (?,?,?,?,1,'PMM-PERF')
          """, fixtures));
      // Cost estimates must describe the committed representative dataset, not the initially empty table.
      jdbc.execute("ANALYZE TABLE lp_material_master_raw");
      var mappingsSpy = mock(PlateMaterialMappingMapper.class, delegatesTo(mappingMapper));
      var masterSpy = mock(PlateMaterialMasterQuery.class, delegatesTo(master));
      var measured = new PlateMaterialResolver(mappingsSpy, masterSpy, json);
      List<Context> contexts = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        String model = "PMM-PERF-" + String.format("%05d", i);
        contexts.add(context(model));
        contexts.add(context(model));
        contexts.add(new Context("COMMERCIAL", model, "COMMERCIAL"));
      }
      long start = System.nanoTime();
      var results = measured.resolveAll(contexts);
      long elapsed = (System.nanoTime() - start) / 1_000_000;
      assertThat(results).hasSize(200);
      assertThat(results.values().stream().filter(d -> d.mode() == PlateMaterialMode.MANUAL_REQUIRED).count()).isEqualTo(100);
      assertThat(results.values().stream().filter(d -> d.mode() == PlateMaterialMode.HIDDEN).count()).isEqualTo(100);
      verify(mappingsSpy, times(1)).findModels(eq("COMMERCIAL"), any(), eq(false));
      verify(masterSpy, times(1)).findActiveModels(eq("PLATE"), any());
      verify(masterSpy, times(1)).findActiveModels(eq("COMMERCIAL"), any());
      String sql = """
          SELECT id, TRIM(material_model) COLLATE utf8mb4_0900_bin AS model, production_division
          FROM lp_material_master_raw WHERE organization_code='PLATE' AND active_flag=1
          AND CAST(UNHEX(SHA2(TRIM(material_model),256)) AS BINARY(32)) IN
            (UNHEX(SHA2('PMM-PERF-00001',256)),UNHEX(SHA2('PMM-PERF-00002',256)))
          AND (TRIM(material_model) COLLATE utf8mb4_0900_bin) IN ('PMM-PERF-00001','PMM-PERF-00002')
          """;
      String plan = jdbc.queryForObject("EXPLAIN FORMAT=JSON " + sql, String.class);
      System.out.println("PMM_RESOLVER_EXPLAIN " + plan);
      var table = json.readTree(plan).path("query_block").path("table");
      assertThat(table.path("key").asText()).isEqualTo("idx_raw_plate_material_model");
      assertThat(table.path("access_type").asText()).isIn("range", "ref");
      assertThat(table.path("rows_examined_per_scan").asLong()).isLessThanOrEqualTo(10);
      String actual = jdbc.queryForObject("EXPLAIN ANALYZE " + sql, String.class);
      System.out.println("PMM_RESOLVER_PERFORMANCE " + json.writeValueAsString(Map.of(
          "contexts", 300, "masterFixtureRows", 24000, "queries", 3, "elapsedMs", elapsed,
          "plan", json.readTree(plan), "analyze", actual)));
    } finally {
      jdbc.update("DELETE FROM lp_material_master_raw WHERE import_batch_id='PMM-PERF'");
    }
  }

  @Test void indexMigrationCanRepeatWithoutChangingRowsColumnsOrExistingIndexes() {
    Long id = material("PMM-INDEX", "PLATE", "板换事业部", 1);
    var row = jdbc.queryForMap("SELECT * FROM lp_material_master_raw WHERE id=?", id);
    var columns = jdbc.queryForList("SHOW COLUMNS FROM lp_material_master_raw");
    var indexes = jdbc.queryForList("SELECT INDEX_NAME,SEQ_IN_INDEX,COLUMN_NAME,EXPRESSION FROM information_schema.statistics "
        + "WHERE table_schema=DATABASE() AND table_name='lp_material_master_raw' ORDER BY INDEX_NAME,SEQ_IN_INDEX");
    jdbc.execute((ConnectionCallback<Void>) connection -> {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/V336__plate_material_master_lookup_index.sql"));
      return null;
    });
    assertThat(jdbc.queryForMap("SELECT * FROM lp_material_master_raw WHERE id=?", id)).isEqualTo(row);
    assertThat(jdbc.queryForList("SHOW COLUMNS FROM lp_material_master_raw")).isEqualTo(columns);
    assertThat(jdbc.queryForList("SELECT INDEX_NAME,SEQ_IN_INDEX,COLUMN_NAME,EXPRESSION FROM information_schema.statistics "
        + "WHERE table_schema=DATABASE() AND table_name='lp_material_master_raw' ORDER BY INDEX_NAME,SEQ_IN_INDEX")).isEqualTo(indexes);
  }

  private Long material(String model, String org, String division, int active) {
    String code = "PMM-" + UUID.randomUUID();
    jdbc.update("""
        INSERT INTO lp_material_master_raw(organization_code,material_code,material_model,production_division,
          department_name,active_flag,import_batch_id) VALUES (?,?,?,?,'加工车间',?,'PMM-03')
        """, org, code, model, division, active);
    return jdbc.queryForObject("SELECT id FROM lp_material_master_raw WHERE organization_code=? AND material_code=?", Long.class, org, code);
  }
}
