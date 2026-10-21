package com.sanhua.marketingcost.service.platematerial;

import static org.assertj.core.api.Assertions.*;
import static com.sanhua.marketingcost.service.platematerial.PlateMaterialWorkbookParserTest.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.PlateMaterialMappingMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
class PlateMaterialMappingIntegrationTest extends BomMapperTestBase {
  @Autowired PlateMaterialMappingService mappings;
  @Autowired PlateMaterialImportService imports;
  @Autowired PlateMaterialMappingMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  private static final String UNIT = "COMMERCIAL";

  @BeforeEach void clearMappings() {
    jdbc.execute("DROP TRIGGER IF EXISTS pmm_fail_import");
    jdbc.update("DELETE FROM lp_plate_material_mapping");
  }

  @AfterEach void removeFailureFixture() {
    jdbc.execute("DROP TRIGGER IF EXISTS pmm_fail_import");
  }

  @Test void originalWorkbookImports2791ExactRowsWithoutAnyMaterialMasterRequirement() throws Exception {
    Path source = Path.of(System.getProperty("pmm.source-workbook"));
    byte[] bytes = Files.readAllBytes(source);
    assertThat(PlateMaterialWorkbookParser.sha256(bytes))
        .isEqualTo("5c1fa6e702982736026fc38e87f77788f6fd7511a3f59f649cecc39aa3e92c18");
    var expected = json.readTree(Files.readString(Path.of(System.getProperty("pmm.source-rows"))));
    long masterBefore = count("lp_material_master_raw");
    var preview = imports.preview(bytes, source.getFileName().toString(), UNIT);
    assertThat(preview.canConfirm()).isTrue();
    assertThat(preview.inserted()).isEqualTo(2791);
    assertThat(preview.errors()).isEmpty();
    assertThat(preview.duplicates()).isEmpty();
    assertThat(count("lp_plate_material_mapping")).isZero();
    var result = imports.confirm(bytes, source.getFileName().toString(), UNIT, preview.fingerprint(), 101);
    assertThat(result.inserted()).isEqualTo(2791);
    assertThat(result.updated()).isZero();
    var actual = jdbc.queryForList("SELECT * FROM lp_plate_material_mapping ORDER BY source_row_number");
    assertThat(actual).hasSize(2791);
    for (int i = 0; i < expected.size(); i++) {
      var row = actual.get(i);
      assertThat(row.get("normalized_model")).isEqualTo(expected.get(i).get("model").asText());
      assertThat(row.get("plate_material")).isEqualTo(expected.get(i).get("material").asText());
      assertThat(row.get("source_row_number")).isEqualTo(expected.get(i).get("excel_row").asInt());
      assertThat(row.get("source_file_sha256")).isEqualTo(preview.sha256());
      assertThat(row.get("source_sheet_name")).isEqualTo("提供财务");
      assertThat(row.get("created_by")).isEqualTo(101L);
      assertThat(row.get("row_version")).isEqualTo(0);
    }
    var totals = jdbc.queryForList("SELECT plate_material,COUNT(*) n FROM lp_plate_material_mapping GROUP BY plate_material");
    assertThat(totals).containsExactlyInAnyOrder(
        Map.of("plate_material", "水氟SUS304", "n", 277L),
        Map.of("plate_material", "水氟316L", "n", 1702L),
        Map.of("plate_material", "氟氟SUS304", "n", 618L),
        Map.of("plate_material", "氟氟316L", "n", 2L),
        Map.of("plate_material", "水水SUS304", "n", 106L),
        Map.of("plate_material", "水水316L", "n", 86L));
    var again = imports.preview(bytes, source.getFileName().toString(), UNIT);
    var repeated = imports.confirm(bytes, source.getFileName().toString(), UNIT, again.fingerprint(), 102);
    assertThat(repeated.inserted() + repeated.updated()).isZero();
    assertThat(repeated.unchanged()).isEqualTo(2791);
    assertThat(jdbc.queryForList("SELECT * FROM lp_plate_material_mapping ORDER BY source_row_number")).isEqualTo(actual);
    assertThat(count("lp_material_master_raw")).isEqualTo(masterBefore);
  }

  @Test void changesHaveBeforeAfterValuesAndRetainUnmentionedRowsAndOriginalCreationAudit() {
    var retained = mappings.create(UNIT, "KEEP", "水水316L", 101);
    var old = mappings.create(UNIT, "A", "水水316L", 101);
    byte[] bytes = workbook(w -> {
      row(w, 3, "A", "水氟SUS304"); row(w, 4, "B", "水氟316L"); row(w, 5, "B", "水氟316L");
    });
    var before = imports.preview(bytes, "source.xlsx", UNIT);
    assertThat(before.updated()).isOne();
    assertThat(before.inserted()).isOne();
    assertThat(before.duplicates()).hasSize(1);
    assertThat(before.changes().getFirst().previousMaterial()).isEqualTo("水水316L");
    imports.confirm(bytes, "source.xlsx", UNIT, before.fingerprint(), 102);
    var updated = mapper.selectById(old.getId());
    assertThat(updated.getPlateMaterial()).isEqualTo("水氟SUS304");
    assertThat(updated.getRowVersion()).isOne();
    assertThat(updated.getCreatedBy()).isEqualTo(101);
    assertThat(updated.getCreatedAt()).isEqualTo(old.getCreatedAt());
    assertThat(updated.getUpdatedBy()).isEqualTo(102);
    assertThat(updated.getSourceType()).isEqualTo("EXCEL");
    assertThat(mapper.selectById(retained.getId())).usingRecursiveComparison().isEqualTo(retained);
    var manual = mappings.update(UNIT, updated.getId(), "A", "水水316L", 1, 103);
    assertThat(manual.getSourceType()).isEqualTo("MANUAL");
    assertThat(manual.getSourceFileSha256()).isNull();
    assertThat(manual.getSourceRowNumber()).isNull();
    assertThat(manual.getImportBatchId()).isNull();
    assertThat(manual.getRowVersion()).isEqualTo(2);
    assertThat(mappings.update(UNIT, manual.getId(), " A ", "水水316L", 2, 104))
        .usingRecursiveComparison().isEqualTo(manual);
  }

  @Test void invalidFilesAndStaleOrChangedPreviewNeverPartiallyWrite() {
    byte[] invalid = workbook(w -> { row(w, 3, "A", "水水316L"); row(w, 4, "A", "水氟316L"); });
    var failed = imports.preview(invalid, "source.xlsx", UNIT);
    assertThat(failed.canConfirm()).isFalse();
    assertThatThrownBy(() -> imports.confirm(invalid, "source.xlsx", UNIT, failed.fingerprint(), 101))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(count("lp_plate_material_mapping")).isZero();
    byte[] bytes = workbook(w -> { row(w, 3, "A", "水水316L"); row(w, 4, "B", "水氟316L"); });
    var before = imports.preview(bytes, "source.xlsx", UNIT);
    mappings.create(UNIT, "A", "水氟SUS304", 101);
    var protectedRows = jdbc.queryForList("SELECT * FROM lp_plate_material_mapping");
    assertThatThrownBy(() -> imports.confirm(bytes, "source.xlsx", UNIT, before.fingerprint(), 102))
        .isInstanceOf(ConcurrencyFailureException.class);
    assertThat(jdbc.queryForList("SELECT * FROM lp_plate_material_mapping")).isEqualTo(protectedRows);
    var fresh = imports.preview(bytes, "source.xlsx", UNIT);
    assertThatThrownBy(() -> imports.confirm(bytes, "renamed.xlsx", UNIT, fresh.fingerprint(), 102))
        .isInstanceOf(ConcurrencyFailureException.class);
    assertThatThrownBy(() -> imports.confirm(bytes, "source.xlsx", "HOUSEHOLD", fresh.fingerprint(), 102))
        .isInstanceOf(ConcurrencyFailureException.class);
    byte[] changed = workbook(w -> row(w, 3, "A", "氟氟316L"));
    assertThatThrownBy(() -> imports.confirm(changed, "source.xlsx", UNIT, fresh.fingerprint(), 102))
        .isInstanceOf(ConcurrencyFailureException.class);
    assertThat(jdbc.queryForList("SELECT * FROM lp_plate_material_mapping")).isEqualTo(protectedRows);
  }

  @Test void midBatchDatabaseFailureRollsBackEarlierInsertAndUpdate() {
    mappings.create(UNIT, "A", "水水316L", 101);
    var before = jdbc.queryForList("SELECT * FROM lp_plate_material_mapping");
    byte[] bytes = workbook(w -> {
      row(w, 3, "A", "水氟SUS304"); row(w, 4, "B", "水氟316L"); row(w, 5, "Z-FAIL", "水水316L");
    });
    jdbc.execute("""
        CREATE TRIGGER pmm_fail_import BEFORE INSERT ON lp_plate_material_mapping FOR EACH ROW
        BEGIN IF NEW.normalized_model='Z-FAIL' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PMM_INJECTED_FAILURE'; END IF; END
        """);
    var preview = imports.preview(bytes, "source.xlsx", UNIT);
    assertThatThrownBy(() -> imports.confirm(bytes, "source.xlsx", UNIT, preview.fingerprint(), 102))
        .hasMessageContaining("PMM_INJECTED_FAILURE");
    assertThat(jdbc.queryForList("SELECT * FROM lp_plate_material_mapping")).isEqualTo(before);
  }

  @Test void scopesPaginationAndExactIdentityAreEnforcedForAllMaintenanceOperations() {
    for (String model : List.of("A", "a", "A-b", "A b", "A-L", "A%")) mappings.create(UNIT, model, "水水316L", 101);
    var foreign = mappings.create("HOUSEHOLD", "A", "水氟316L", 101);
    assertThat(mappings.list(UNIT, null, null, 1, 2).total()).isEqualTo(6);
    assertThat(mappings.list(UNIT, null, null, 1, 2).records()).hasSize(2);
    assertThat(mappings.list(UNIT, " A ", null, 1, 20).records()).singleElement()
        .satisfies(r -> assertThat(r.getPlateMaterial()).isEqualTo("水水316L"));
    assertThat(mappings.list(UNIT, "A%", null, 1, 20).total()).isOne();
    assertThat(mappings.list(UNIT, null, "水氟316L", 1, 20).total()).isZero();
    assertThatThrownBy(() -> mappings.update(UNIT, foreign.getId(), "A", "水水316L", 0, 101))
        .hasMessageContaining("未找到");
    assertThatThrownBy(() -> mappings.create(UNIT, " A ", "水氟316L", 101)).isInstanceOf(DuplicateKeyException.class);
    assertThatThrownBy(() -> mappings.list(null, null, null, 1, 20)).hasMessageContaining("业务单元");
    assertThatThrownBy(() -> mappings.list(UNIT, null, null, 1, 201)).hasMessageContaining("200");
    assertThatThrownBy(() -> mappings.create(UNIT, "X", "水水316", 101)).hasMessageContaining("六种");
    assertThatThrownBy(() -> mappings.create(UNIT, "X", "水水316L", 0)).hasMessageContaining("登录");
  }

  @Test void concurrentUpdatesAndDuplicateCreatesHaveExactlyOneWinner() throws Exception {
    var row = mappings.create(UNIT, "A", "水水316L", 101);
    race(
        () -> mappings.update(UNIT, row.getId(), "A", "水氟SUS304", 0, 102),
        () -> mappings.update(UNIT, row.getId(), "A", "水氟316L", 0, 103));
    assertThat(mapper.selectById(row.getId()).getRowVersion()).isOne();
    race(() -> mappings.create(UNIT, "B", "水氟316L", 102),
        () -> mappings.create(UNIT, "B", "水水316L", 103));
    assertThat(count("lp_plate_material_mapping")).isEqualTo(2);
  }

  @Test void concurrentConfirmationsCannotOverwriteAnotherImport() throws Exception {
    byte[] bytes = workbook(w -> { row(w, 3, "A", "水水316L"); row(w, 4, "B", "水氟316L"); });
    var preview = imports.preview(bytes, "source.xlsx", UNIT);
    race(() -> imports.confirm(bytes, "source.xlsx", UNIT, preview.fingerprint(), 102),
        () -> imports.confirm(bytes, "source.xlsx", UNIT, preview.fingerprint(), 103));
    assertThat(count("lp_plate_material_mapping")).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT import_batch_id) FROM lp_plate_material_mapping", Integer.class)).isOne();
    assertThat(jdbc.queryForObject("SELECT SUM(row_version) FROM lp_plate_material_mapping", Integer.class)).isZero();
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  private void race(Callable<?> first, Callable<?> second) throws Exception {
    var start = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = List.of(first, second).stream().map(action -> pool.submit(() -> {
        start.await(10, TimeUnit.SECONDS);
        try { action.call(); return "SUCCESS"; }
        catch (ConcurrencyFailureException | DuplicateKeyException expected) { return "CONFLICT"; }
      })).toList();
      assertThat(List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
    }
  }
}
