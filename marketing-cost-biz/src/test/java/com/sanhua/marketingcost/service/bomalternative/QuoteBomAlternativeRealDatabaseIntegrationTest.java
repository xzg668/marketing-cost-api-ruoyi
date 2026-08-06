package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("integration")
@DisplayName("QBA-12 1145900000302隔离MySQL选择版本链")
class QuoteBomAlternativeRealDatabaseIntegrationTest
    extends BomMapperTestBase {

  @Autowired
  private QuoteBomAlternativeSelectionService selectionService;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private BomCostingRowMapper costingRowMapper;

  private QuoteBomAlternativeRealDataTestSupport support;

  @BeforeEach
  void setUp() throws Exception {
    support = new QuoteBomAlternativeRealDataTestSupport();
    try (var connection = openConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "DELETE FROM lp_quote_bom_alternative_selection "
              + "WHERE oa_no='FI-SC-006-20260106-082' "
              + "AND oa_form_item_id=326 "
              + "AND period_month='2026-07'");
      statement.execute(
          "DELETE FROM lp_bom_costing_row "
              + "WHERE oa_no='FI-SC-006-20260106-082' "
              + "AND oa_form_item_id=326 "
              + "AND period_month='2026-07'");
    }
  }

  @Test
  @DisplayName("真实作用域持久化AUTO_STANDARD到替代再恢复标准三版")
  void persistsDefaultAlternativeAndRestoreVersions() throws Exception {
    var scope = support.scope();
    var group = support.group();

    var first = selectionService.ensureDefault(scope, group);
    var second =
        selectionService.save(
            support.command(
                QuoteBomAlternativeRealDataTestSupport.ALTERNATIVE, 1),
            group);
    var third =
        selectionService.save(
            support.command(
                QuoteBomAlternativeRealDataTestSupport.STANDARD, 2),
            group);

    assertThat(first.selectionSource())
        .isEqualTo(QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD);
    assertThat(second.selectionSource())
        .isEqualTo(QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE);
    assertThat(third.selectionSource())
        .isEqualTo(QuoteBomAlternativeSelection.SOURCE_MANUAL_STANDARD);
    assertThat(selectionService.history(scope, group.alternativeGroupKey()))
        .extracting(
            QuoteBomAlternativeSelectionResult::selectionVersion,
            QuoteBomAlternativeSelectionResult::selectedMaterialCode,
            QuoteBomAlternativeSelectionResult::selectionStatus)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                1,
                QuoteBomAlternativeRealDataTestSupport.STANDARD,
                QuoteBomAlternativeSelection.STATUS_SUPERSEDED),
            org.assertj.core.groups.Tuple.tuple(
                2,
                QuoteBomAlternativeRealDataTestSupport.ALTERNATIVE,
                QuoteBomAlternativeSelection.STATUS_SUPERSEDED),
            org.assertj.core.groups.Tuple.tuple(
                3,
                QuoteBomAlternativeRealDataTestSupport.STANDARD,
                QuoteBomAlternativeSelection.STATUS_ACTIVE));

    try (var connection = openConnection();
        var statement =
            connection.prepareStatement(
                "SELECT selection_version,selection_source,current_slot "
                    + "FROM lp_quote_bom_alternative_selection "
                    + "WHERE oa_no=? AND oa_form_item_id=? AND period_month=? "
                    + "ORDER BY selection_version")) {
      statement.setString(1, QuoteBomAlternativeRealDataTestSupport.OA_NO);
      statement.setLong(2, QuoteBomAlternativeRealDataTestSupport.ITEM_ID);
      statement.setString(3, QuoteBomAlternativeRealDataTestSupport.PERIOD);
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getInt("selection_version")).isEqualTo(1);
        assertThat(result.getObject("current_slot")).isNull();
        assertThat(result.next()).isTrue();
        assertThat(result.getInt("selection_version")).isEqualTo(2);
        assertThat(result.getObject("current_slot")).isNull();
        assertThat(result.next()).isTrue();
        assertThat(result.getInt("selection_version")).isEqualTo(3);
        assertThat(result.getInt("current_slot")).isEqualTo(1);
        assertThat(result.next()).isFalse();
      }
    }
  }

  @Test
  @DisplayName("每版候选快照都保留真实标准和替代料号")
  void persistsBothRealCandidatesInEverySnapshot() throws Exception {
    var group = support.group();
    selectionService.ensureDefault(support.scope(), group);
    selectionService.save(
        support.command(
            QuoteBomAlternativeRealDataTestSupport.ALTERNATIVE, 1),
        group);
    selectionService.save(
        support.command(
            QuoteBomAlternativeRealDataTestSupport.STANDARD, 2),
        group);

    try (var connection = openConnection();
        var statement =
            connection.prepareStatement(
                "SELECT candidate_snapshot_json "
                    + "FROM lp_quote_bom_alternative_selection "
                    + "WHERE oa_no=? AND oa_form_item_id=? AND period_month=? "
                    + "ORDER BY selection_version")) {
      statement.setString(1, QuoteBomAlternativeRealDataTestSupport.OA_NO);
      statement.setLong(2, QuoteBomAlternativeRealDataTestSupport.ITEM_ID);
      statement.setString(3, QuoteBomAlternativeRealDataTestSupport.PERIOD);
      try (var result = statement.executeQuery()) {
        int versions = 0;
        while (result.next()) {
          versions++;
          var candidates =
              objectMapper
                  .readTree(result.getString(1))
                  .path("candidates");
          assertThat(candidates).hasSize(2);
          assertThat(candidates.toString())
              .contains(
                  QuoteBomAlternativeRealDataTestSupport.STANDARD,
                  QuoteBomAlternativeRealDataTestSupport.ALTERNATIVE);
        }
        assertThat(versions).isEqualTo(3);
      }
    }
  }

  @Test
  @DisplayName("隔离库三轮结算快照始终只有一个分支且各34行")
  void persistsOnlyTheSelectedCostingBranchInEveryRound()
      throws Exception {
    var first = support.defaultStandard();
    persistCostingRows(first);
    assertCostingBranch(
        QuoteBomAlternativeRealDataTestSupport.STANDARD,
        QuoteBomAlternativeRealDataTestSupport.ALTERNATIVE,
        "201850547");

    clearCostingRows();
    var second = support.selectAlternative();
    persistCostingRows(second);
    assertCostingBranch(
        QuoteBomAlternativeRealDataTestSupport.ALTERNATIVE,
        QuoteBomAlternativeRealDataTestSupport.STANDARD,
        "201850347");

    clearCostingRows();
    var third = support.restoreStandard();
    persistCostingRows(third);
    assertCostingBranch(
        QuoteBomAlternativeRealDataTestSupport.STANDARD,
        QuoteBomAlternativeRealDataTestSupport.ALTERNATIVE,
        "201850547");
  }

  private void persistCostingRows(
      QuoteBomAlternativeRealDataTestSupport.RoundSnapshot
          round) {
    for (var row : round.costingRows()) {
      row.setId(null);
      assertThat(costingRowMapper.insert(row)).isEqualTo(1);
    }
  }

  private void clearCostingRows() throws Exception {
    try (var connection = openConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "DELETE FROM lp_bom_costing_row "
              + "WHERE oa_no='FI-SC-006-20260106-082' "
              + "AND oa_form_item_id=326 "
              + "AND period_month='2026-07'");
    }
  }

  private void assertCostingBranch(
      String selected,
      String excluded,
      String uniqueMaterial)
      throws Exception {
    try (var connection = openConnection();
        var statement =
            connection.prepareStatement(
                "SELECT COUNT(*) row_count,"
                    + "SUM(path LIKE ?) selected_count,"
                    + "SUM(path LIKE ?) excluded_count,"
                    + "SUM(material_code=?) unique_count "
                    + "FROM lp_bom_costing_row "
                    + "WHERE oa_no=? AND oa_form_item_id=? "
                    + "AND period_month=?")) {
      statement.setString(
          1, "%/" + selected + "@10@010/%");
      statement.setString(
          2, "%/" + excluded + "@10@010/%");
      statement.setString(3, uniqueMaterial);
      statement.setString(4, QuoteBomAlternativeRealDataTestSupport.OA_NO);
      statement.setLong(5, QuoteBomAlternativeRealDataTestSupport.ITEM_ID);
      statement.setString(6, QuoteBomAlternativeRealDataTestSupport.PERIOD);
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getInt("row_count")).isEqualTo(34);
        assertThat(result.getInt("selected_count")).isEqualTo(15);
        assertThat(result.getInt("excluded_count")).isZero();
        assertThat(result.getInt("unique_count")).isEqualTo(1);
      }
    }
  }
}
