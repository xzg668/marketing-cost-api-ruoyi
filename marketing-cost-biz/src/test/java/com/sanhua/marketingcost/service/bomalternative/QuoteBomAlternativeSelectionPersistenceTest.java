package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("QBA-05 报价BOM选择真实持久化")
class QuoteBomAlternativeSelectionPersistenceTest extends BomMapperTestBase {

  @Autowired
  private QuoteBomAlternativeSelectionService service;

  @Autowired
  private ObjectMapper objectMapper;

  @BeforeEach
  void cleanSelectionRows() throws Exception {
    try (var connection = openConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM lp_quote_bom_alternative_selection");
    }
  }

  @Test
  @DisplayName("真实MySQL保存版本链和完整候选快照")
  void persistsVersionHistoryAndCandidateSnapshot() throws Exception {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    QuoteBomAlternativeSelectionScope scope = support.scope();
    BomAlternativeGroup group = support.group();

    service.ensureDefault(scope, group);
    service.save(support.command("ALT", 1), group);
    List<QuoteBomAlternativeSelectionResult> history =
        service.history(scope, QuoteBomAlternativeSelectionTestSupport.GROUP_KEY);

    assertThat(history).hasSize(2);
    assertThat(history.get(0).selectionStatus())
        .isEqualTo(QuoteBomAlternativeSelection.STATUS_SUPERSEDED);
    assertThat(history.get(1).selectionStatus())
        .isEqualTo(QuoteBomAlternativeSelection.STATUS_ACTIVE);
    try (var connection = openConnection();
        var statement =
            connection.prepareStatement(
                "SELECT candidate_snapshot_json FROM "
                    + "lp_quote_bom_alternative_selection "
                    + "WHERE selection_version=2")) {
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        JsonNode snapshot = objectMapper.readTree(result.getString(1));
        assertThat(snapshot.path("candidates")).hasSize(2);
        assertThat(snapshot.path("candidates").get(0).path("materialCode").asText())
            .isEqualTo("STD");
        assertThat(snapshot.path("candidates").get(1).path("materialCode").asText())
            .isEqualTo("ALT");
      }
    }
  }

  @Test
  @DisplayName("数据库唯一约束拒绝同一作用域同组两条当前选择")
  void databaseRejectsSecondCurrentSelection() throws Exception {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    service.ensureDefault(support.scope(), support.group());

    assertThatThrownBy(
            () -> insertDuplicateCurrent("DUPLICATE-CURRENT", "COMMERCIAL"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_quote_alt_selection_current");
  }

  @Test
  @DisplayName("数据库唯一约束包含业务单元，相同报价键在不同业务单元可隔离")
  void databaseAllowsSameQuoteKeyInDifferentBusinessUnits() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();

    service.ensureDefault(support.scope(), support.group());
    QuoteBomAlternativeSelectionResult household =
        service.ensureDefault(
            support.scope("OA-1", 10L, "2026-07", "HOUSEHOLD"),
            support.group());

    assertThat(household.selectionVersion()).isEqualTo(1);
  }

  @Test
  @DisplayName("V200可重复执行且不会改写已有选择版本")
  void v200MigrationIsIdempotentAndKeepsRows() throws Exception {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    service.ensureDefault(support.scope(), support.group());
    service.ensureDefault(
        support.scope("OA-1", 10L, "2026-07", "HOUSEHOLD"),
        support.group());
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource(
            "/db/V200__quote_bom_alternative_selection_scope_isolation.sql"),
        "/tmp/V200-rerun.sql");

    var result =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "mysql --default-character-set=utf8mb4 -uroot -p"
                + MYSQL.getPassword()
                + " "
                + MYSQL.getDatabaseName()
                + " < /tmp/V200-rerun.sql");

    assertThat(result.getExitCode())
        .withFailMessage(result.getStderr())
        .isZero();
    assertThat(service.history(
        support.scope(), QuoteBomAlternativeSelectionTestSupport.GROUP_KEY))
        .hasSize(1);
    assertThat(service.history(
        support.scope("OA-1", 10L, "2026-07", "HOUSEHOLD"),
        QuoteBomAlternativeSelectionTestSupport.GROUP_KEY))
        .hasSize(1);
  }

  @Test
  @DisplayName("新版本数据库写入失败时旧当前选择仍保持ACTIVE")
  void databaseFailureRollsBackSupersede() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    BomAlternativeGroup initial = support.group();
    service.ensureDefault(support.scope(), initial);
    String tooLongMaterialCode = "X".repeat(80);
    BomAlternativeGroup invalidForColumnLength =
        support.group(
            QuoteBomAlternativeSelectionTestSupport.GROUP_KEY,
            "STD",
            List.of(tooLongMaterialCode),
            "BUILD-1");
    QuoteBomAlternativeSelectionCommand command =
        new QuoteBomAlternativeSelectionCommand(
            support.scope(),
            QuoteBomAlternativeSelectionTestSupport.GROUP_KEY,
            tooLongMaterialCode,
            1,
            "BUILD-1",
            "quote-user",
            "模拟数据库写入失败");

    assertThatThrownBy(() -> service.save(command, invalidForColumnLength))
        .isInstanceOfAny(
            QuoteBomAlternativeSelectionException.class,
            DataIntegrityViolationException.class);

    QuoteBomAlternativeSelectionResult current =
        service.findCurrent(
            support.scope(), QuoteBomAlternativeSelectionTestSupport.GROUP_KEY);
    assertThat(current.selectedMaterialCode()).isEqualTo("STD");
    assertThat(current.selectionStatus())
        .isEqualTo(QuoteBomAlternativeSelection.STATUS_ACTIVE);
    assertThat(service.history(
        support.scope(), QuoteBomAlternativeSelectionTestSupport.GROUP_KEY))
        .hasSize(1);
  }

  private void insertDuplicateCurrent(
      String selectionNo, String businessUnitType) throws Exception {
    try (var connection = openConnection();
        var statement =
            connection.prepareStatement(
                "INSERT INTO lp_quote_bom_alternative_selection ("
                    + "selection_no,oa_no,oa_form_item_id,top_product_code,"
                    + "period_month,price_org_code,alternative_group_key,"
                    + "parent_material_code,standard_material_code,"
                    + "selected_material_code,selected_child_type,selection_source,"
                    + "selection_version,selection_status,current_slot,"
                    + "business_unit_type) VALUES "
                    + "(?,'OA-1',10,'TOP','2026-07','210',?,'PARENT','STD','ALT',"
                    + "'ALTERNATIVE','MANUAL_ALTERNATIVE',2,'ACTIVE',1,?)")) {
      statement.setString(1, selectionNo);
      statement.setString(
          2, QuoteBomAlternativeSelectionTestSupport.GROUP_KEY);
      statement.setString(3, businessUnitType);
      statement.executeUpdate();
    }
  }
}
