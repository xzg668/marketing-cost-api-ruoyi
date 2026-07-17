package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.entity.QuoteCostPriceScenario;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCuMaterialDiffItem;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@DisplayName("FCQ-09 试算版本失效真实数据库契约")
class QuoteCostRunVersionInvalidationIntegrationTest extends BomMapperTestBase {

  @Autowired private QuoteCostRunVersionInvalidationService invalidationService;
  @Autowired private QuoteCostRunVersionMapper versionMapper;
  @Autowired private CostRunResultMapper resultMapper;
  @Autowired private QuoteCostPriceScenarioMapper scenarioMapper;
  @Autowired private QuoteCuMaterialDiffItemMapper diffItemMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  private final String oaNo = "OA-FCQ09-" + suffix;

  @AfterEach
  void cleanRows() {
    jdbcTemplate.update(
        "DELETE FROM lp_quote_cu_material_diff_item WHERE cost_run_no LIKE ?", "%" + suffix);
    jdbcTemplate.update(
        "DELETE FROM lp_quote_cost_price_scenario WHERE cost_run_no LIKE ?", "%" + suffix);
    jdbcTemplate.update("DELETE FROM lp_cost_run_result WHERE oa_no = ?", oaNo);
    jdbcTemplate.update("DELETE FROM lp_quote_cost_run_version WHERE oa_no = ?", oaNo);
  }

  @Test
  @DisplayName("只失效同月同BU未确认版本，确认快照冻结且可新增重试版本")
  void financeScopeInvalidatesOnlyMatchingTrialAndKeepsHistoricalAmounts() {
    Snapshot matchingTrial = insert("MATCH-TRIAL", "2026-09", "COMMERCIAL", "TRIAL",
        "24.07800000", "261.12800000");
    Snapshot matchingConfirmed = insert("MATCH-CONFIRMED", "2026-09", "COMMERCIAL",
        "CONFIRMED", "18.50000000", "255.50000000");
    FrozenDetails frozenDetails = insertFrozenDetails(matchingConfirmed.versionId());
    Snapshot otherBusinessUnit = insert("OTHER-BU", "2026-09", "HOUSEHOLD", "TRIAL",
        "7.00000000", "207.00000000");
    Snapshot otherMonth = insert("OTHER-MONTH", "2026-10", "COMMERCIAL", "TRIAL",
        "8.00000000", "208.00000000");

    int affected = invalidationService.invalidateByFinanceCu("2026-09", "COMMERCIAL");

    assertThat(affected).isEqualTo(1);
    assertSnapshot(matchingTrial, "STALE");
    assertSnapshot(matchingConfirmed, "CONFIRMED");
    assertFrozenDetails(frozenDetails);
    assertSnapshot(otherBusinessUnit, "TRIAL");
    assertSnapshot(otherMonth, "TRIAL");

    Snapshot retrial = insert("RETRIAL", "2026-09", "COMMERCIAL", "TRIAL",
        "25.00000000", "262.05000000");
    assertSnapshot(matchingTrial, "STALE");
    assertSnapshot(retrial, "TRIAL");
    assertFrozenDetails(frozenDetails);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_cost_run_version "
            + "WHERE oa_no=? AND pricing_month='2026-09' AND business_unit_type='COMMERCIAL'",
        Integer.class,
        oaNo)).isEqualTo(3);
  }

  private Snapshot insert(
      String marker,
      String pricingMonth,
      String businessUnitType,
      String status,
      String adjustment,
      String finalAmount) {
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setCostRunNo("RUN-FCQ09-" + marker + "-" + suffix);
    version.setVersionNo("CONFIRMED".equals(status) ? "VER-FCQ09-" + suffix : null);
    version.setOaNo(oaNo);
    version.setOaFormItemId(990001L);
    version.setProductCode("TOP-FCQ09");
    version.setPricingMonth(pricingMonth);
    version.setResultPeriod(pricingMonth);
    version.setStatus(status);
    version.setTotalCost(new BigDecimal("237.05000000"));
    version.setFinanceMaterialCost(new BigDecimal("120.00000000"));
    version.setOaMaterialCost(new BigDecimal("144.07800000"));
    version.setCuMaterialAdjustment(new BigDecimal(adjustment));
    version.setFinalQuoteAmount(new BigDecimal(finalAmount));
    version.setPartItemCount(0);
    version.setCostItemCount(0);
    version.setBusinessUnitType(businessUnitType);
    assertThat(versionMapper.insert(version)).isEqualTo(1);

    CostRunResult result = new CostRunResult();
    result.setOaNo(oaNo);
    result.setOaFormItemId(990001L);
    result.setCostRunVersionId(version.getId());
    result.setCostRunNo(version.getCostRunNo());
    result.setProductCode("TOP-FCQ09");
    result.setPeriod(pricingMonth);
    result.setPricingMonth(pricingMonth);
    result.setTotalCost(version.getTotalCost());
    result.setFinanceMaterialCost(version.getFinanceMaterialCost());
    result.setOaMaterialCost(version.getOaMaterialCost());
    result.setCuMaterialAdjustment(version.getCuMaterialAdjustment());
    result.setFinalQuoteAmount(version.getFinalQuoteAmount());
    result.setCalcStatus("已核算");
    result.setResultStatus(status);
    result.setBusinessUnitType(businessUnitType);
    assertThat(resultMapper.insert(result)).isEqualTo(1);
    return new Snapshot(
        version.getId(),
        result.getId(),
        version.getTotalCost(),
        version.getCuMaterialAdjustment(),
        version.getFinalQuoteAmount());
  }

  private void assertSnapshot(Snapshot expected, String expectedStatus) {
    QuoteCostRunVersion version = versionMapper.selectById(expected.versionId());
    CostRunResult result = resultMapper.selectById(expected.resultId());
    assertThat(version.getStatus()).isEqualTo(expectedStatus);
    assertThat(result.getResultStatus()).isEqualTo(expectedStatus);
    assertThat(version.getTotalCost()).isEqualByComparingTo(expected.totalCost());
    assertThat(version.getCuMaterialAdjustment()).isEqualByComparingTo(expected.adjustment());
    assertThat(version.getFinalQuoteAmount()).isEqualByComparingTo(expected.finalAmount());
    assertThat(result.getTotalCost()).isEqualByComparingTo(expected.totalCost());
    assertThat(result.getCuMaterialAdjustment()).isEqualByComparingTo(expected.adjustment());
    assertThat(result.getFinalQuoteAmount()).isEqualByComparingTo(expected.finalAmount());
  }

  private FrozenDetails insertFrozenDetails(Long versionId) {
    QuoteCostRunVersion version = versionMapper.selectById(versionId);
    QuoteCostPriceScenario scenario = new QuoteCostPriceScenario();
    scenario.setScenarioNo("SCN-FCQ09-FIN-" + suffix);
    scenario.setCostRunVersionId(versionId);
    scenario.setCostRunNo(version.getCostRunNo());
    scenario.setScenarioType("FINANCE_QUOTE_BASE");
    scenario.setPricePrepareNo("PPR-FCQ09-FIN-" + suffix);
    scenario.setPricingMonth("2026-09");
    scenario.setCuPrice(new BigDecimal("90.00000000"));
    scenario.setMaterialCost(new BigDecimal("120.00000000"));
    scenario.setTotalCost(new BigDecimal("237.05000000"));
    scenario.setInputSnapshotHash("HASH-FCQ09-" + suffix);
    scenario.setStatus("SUCCESS");
    scenario.setBusinessUnitType("COMMERCIAL");
    assertThat(scenarioMapper.insert(scenario)).isEqualTo(1);

    QuoteCuMaterialDiffItem diff = new QuoteCuMaterialDiffItem();
    diff.setCostRunVersionId(versionId);
    diff.setCostRunNo(version.getCostRunNo());
    diff.setLineNo(1);
    diff.setSettlementKey("SET:FCQ09:" + suffix);
    diff.setDetailLevel("SETTLEMENT");
    diff.setContributesToAdjustment(1);
    diff.setTopProductCode("TOP-FCQ09");
    diff.setMaterialCode("MAT-CU-FCQ09");
    diff.setQuantity(new BigDecimal("2.00000000"));
    diff.setFinanceAmount(new BigDecimal("120.00000000"));
    diff.setOaAmount(new BigDecimal("138.50000000"));
    diff.setDiffAmount(new BigDecimal("18.50000000"));
    diff.setCuAffected(1);
    diff.setTraceJson("{\"financeCu\":90.00000000,\"oaCu\":102.03900000}");
    diff.setBusinessUnitType("COMMERCIAL");
    assertThat(diffItemMapper.insert(diff)).isEqualTo(1);
    QuoteCostPriceScenario storedScenario = scenarioMapper.selectById(scenario.getId());
    QuoteCuMaterialDiffItem storedDiff = diffItemMapper.selectById(diff.getId());
    return new FrozenDetails(
        scenario.getId(),
        storedScenario.getCuPrice(),
        storedScenario.getMaterialCost(),
        storedScenario.getInputSnapshotHash(),
        diff.getId(),
        storedDiff.getDiffAmount(),
        storedDiff.getTraceJson());
  }

  private void assertFrozenDetails(FrozenDetails expected) {
    QuoteCostPriceScenario scenario = scenarioMapper.selectById(expected.scenarioId());
    QuoteCuMaterialDiffItem diff = diffItemMapper.selectById(expected.diffId());
    assertThat(scenario.getCuPrice()).isEqualByComparingTo(expected.cuPrice());
    assertThat(scenario.getMaterialCost()).isEqualByComparingTo(expected.materialCost());
    assertThat(scenario.getInputSnapshotHash()).isEqualTo(expected.inputSnapshotHash());
    assertThat(scenario.getStatus()).isEqualTo("SUCCESS");
    assertThat(diff.getDiffAmount()).isEqualByComparingTo(expected.diffAmount());
    assertThat(diff.getTraceJson()).isEqualTo(expected.traceJson());
  }

  private record Snapshot(
      Long versionId,
      Long resultId,
      BigDecimal totalCost,
      BigDecimal adjustment,
      BigDecimal finalAmount) {}

  private record FrozenDetails(
      Long scenarioId,
      BigDecimal cuPrice,
      BigDecimal materialCost,
      String inputSnapshotHash,
      Long diffId,
      BigDecimal diffAmount,
      String traceJson) {}
}
