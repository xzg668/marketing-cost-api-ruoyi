package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostPriceScenario;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCuMaterialDiffItem;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

@Tag("integration")
@DisplayName("FCQ-01 双场景 Mapper 集成契约")
class FinanceCuQuoteScenarioMapperIntegrationTest extends BomMapperTestBase {

  @Autowired private PricePrepareBatchMapper pricePrepareBatchMapper;
  @Autowired private PricePrepareItemMapper pricePrepareItemMapper;
  @Autowired private QuoteCostPriceScenarioMapper scenarioMapper;
  @Autowired private QuoteCuMaterialDiffItemMapper diffItemMapper;
  @Autowired private QuoteCostRunVersionMapper versionMapper;
  @Autowired private CostRunResultMapper resultMapper;

  private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

  @Test
  @DisplayName("价格准备批次和明细可写入场景字段及稳定结算键")
  void mapsPricePrepareScenarioFields() {
    String prepareNo = "PPR-FCQ01-" + suffix;
    PricePrepareBatch batch = new PricePrepareBatch();
    batch.setPrepareNo(prepareNo);
    batch.setOaNo("OA-FCQ01-" + suffix);
    batch.setOaFormItemId(910001L);
    batch.setTopProductCode("TOP-FCQ01");
    batch.setPeriodMonth("2026-07");
    batch.setBomPurpose("主制造");
    batch.setSourceType("U9");
    batch.setScenarioType("FINANCE_QUOTE_BASE");
    batch.setScenarioGroupNo("SCG-" + suffix);
    batch.setSourcePrepareNo("PPR-OA-" + suffix);
    batch.setStatus("SUCCESS");
    batch.setTotalCount(1);
    batch.setSuccessCount(1);
    batch.setWarningCount(0);
    batch.setGapCount(0);
    batch.setPriceAsOfTime(LocalDateTime.of(2026, 7, 15, 12, 0));
    batch.setPriceAsOfSource("REQUEST");
    batch.setBusinessUnitType("COMMERCIAL");
    assertThat(pricePrepareBatchMapper.insert(batch)).isEqualTo(1);

    PricePrepareBatch storedBatch = pricePrepareBatchMapper.selectById(batch.getId());
    assertThat(storedBatch.getScenarioType()).isEqualTo("FINANCE_QUOTE_BASE");
    assertThat(storedBatch.getScenarioGroupNo()).isEqualTo("SCG-" + suffix);
    assertThat(storedBatch.getSourcePrepareNo()).isEqualTo("PPR-OA-" + suffix);

    PricePrepareItem item = new PricePrepareItem();
    item.setPrepareNo(prepareNo);
    item.setPeriodMonth("2026-07");
    item.setOaNo(batch.getOaNo());
    item.setOaFormItemId(910001L);
    item.setTopProductCode("TOP-FCQ01");
    item.setBomRowId(920001L);
    item.setMaterialCode("MAT-FCQ01");
    item.setMaterialName("FCQ01材料");
    item.setItemType("NORMAL");
    item.setQuantity(new BigDecimal("2.00000000"));
    item.setUnitPrice(new BigDecimal("10.00000000"));
    item.setAmount(new BigDecimal("20.00000000"));
    item.setStatus("READY");
    item.setCurrentFlag(1);
    item.setSettlementKey("SETTLEMENT:TOP-FCQ01:MAT-FCQ01");
    item.setBusinessUnitType("COMMERCIAL");
    assertThat(pricePrepareItemMapper.insert(item)).isEqualTo(1);
    assertThat(pricePrepareItemMapper.selectById(item.getId()).getSettlementKey())
        .isEqualTo("SETTLEMENT:TOP-FCQ01:MAT-FCQ01");
  }

  @Test
  @DisplayName("场景和差额表按成本版本及场景/结算键防重")
  void enforcesScenarioAndSettlementUniqueness() {
    long versionId = 930000L + Integer.parseInt(suffix.substring(0, 4), 16);
    QuoteCostPriceScenario oa = scenario(versionId, "OA_LOCKED", "OA");
    QuoteCostPriceScenario finance = scenario(versionId, "FINANCE_QUOTE_BASE", "FIN");
    assertThat(scenarioMapper.insert(oa)).isEqualTo(1);
    assertThat(scenarioMapper.insert(finance)).isEqualTo(1);
    assertThat(scenarioMapper.selectById(oa.getId()).getStatus()).isEqualTo("SUCCESS");
    assertThatThrownBy(() -> scenarioMapper.insert(scenario(versionId, "OA_LOCKED", "DUP")))
        .isInstanceOf(DuplicateKeyException.class);

    QuoteCuMaterialDiffItem diff = diffItem(versionId, "SETTLEMENT:1");
    assertThat(diffItemMapper.insert(diff)).isEqualTo(1);
    QuoteCuMaterialDiffItem stored = diffItemMapper.selectById(diff.getId());
    assertThat(stored.getDiffAmount()).isEqualByComparingTo("-1.25000000");
    assertThat(stored.getTraceJson()).contains("financeCu");
    assertThatThrownBy(() -> diffItemMapper.insert(diffItem(versionId, "SETTLEMENT:1")))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  @DisplayName("成本版本和结果表可持久化双场景金额快照")
  void mapsCostVersionAndResultSummary() {
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setCostRunNo("RUN-FCQ01-" + suffix);
    version.setOaNo("OA-FCQ01-" + suffix);
    version.setOaFormItemId(940001L);
    version.setProductCode("TOP-FCQ01");
    version.setPricingMonth("2026-07");
    version.setResultPeriod("2026-07");
    version.setOaPricePrepareNo("PPR-OA-" + suffix);
    version.setFinancePricePrepareNo("PPR-FIN-" + suffix);
    version.setFinanceCuPrice(new BigDecimal("90.00000000"));
    version.setOaCuPrice(new BigDecimal("102.03900000"));
    version.setFinanceBasePriceId(950001L);
    version.setTotalCost(new BigDecimal("100.00000000"));
    version.setFinanceMaterialCost(new BigDecimal("40.00000000"));
    version.setOaMaterialCost(new BigDecimal("42.50000000"));
    version.setCuMaterialAdjustment(new BigDecimal("2.50000000"));
    version.setFinalQuoteAmount(new BigDecimal("102.50000000"));
    version.setPartItemCount(0);
    version.setCostItemCount(0);
    version.setStatus("TRIAL");
    version.setBusinessUnitType("COMMERCIAL");
    assertThat(versionMapper.insert(version)).isEqualTo(1);
    assertThat(versionMapper.selectById(version.getId()).getFinalQuoteAmount())
        .isEqualByComparingTo("102.50000000");

    CostRunResult result = new CostRunResult();
    result.setOaNo(version.getOaNo());
    result.setOaFormItemId(version.getOaFormItemId());
    result.setCostRunVersionId(version.getId());
    result.setCostRunNo(version.getCostRunNo());
    result.setProductCode(version.getProductCode());
    result.setPeriod("2026-07");
    result.setPricingMonth("2026-07");
    result.setTotalCost(new BigDecimal("100.00000000"));
    result.setFinanceMaterialCost(new BigDecimal("40.00000000"));
    result.setOaMaterialCost(new BigDecimal("42.50000000"));
    result.setCuMaterialAdjustment(new BigDecimal("2.50000000"));
    result.setFinalQuoteAmount(new BigDecimal("102.50000000"));
    result.setCalcStatus("已核算");
    result.setResultStatus("TRIAL");
    result.setBusinessUnitType("COMMERCIAL");
    assertThat(resultMapper.insert(result)).isEqualTo(1);
    assertThat(resultMapper.selectById(result.getId()).getCuMaterialAdjustment())
        .isEqualByComparingTo("2.50000000");
  }

  private QuoteCostPriceScenario scenario(long versionId, String type, String marker) {
    QuoteCostPriceScenario row = new QuoteCostPriceScenario();
    row.setScenarioNo("SCN-" + marker + "-" + suffix + "-" + UUID.randomUUID());
    row.setCostRunVersionId(versionId);
    row.setCostRunNo("RUN-FCQ01-" + suffix);
    row.setScenarioType(type);
    row.setPricePrepareNo("PPR-" + marker + "-" + suffix);
    row.setPricingMonth("2026-07");
    row.setCuPrice(new BigDecimal("90.00000000"));
    row.setMaterialCost(new BigDecimal("40.00000000"));
    row.setBusinessUnitType("COMMERCIAL");
    return row;
  }

  private QuoteCuMaterialDiffItem diffItem(long versionId, String settlementKey) {
    QuoteCuMaterialDiffItem row = new QuoteCuMaterialDiffItem();
    row.setCostRunVersionId(versionId);
    row.setCostRunNo("RUN-FCQ01-" + suffix);
    row.setLineNo(1);
    row.setSettlementKey(settlementKey);
    row.setDetailLevel("SETTLEMENT");
    row.setContributesToAdjustment(1);
    row.setTopProductCode("TOP-FCQ01");
    row.setMaterialCode("MAT-FCQ01");
    row.setQuantity(new BigDecimal("1.00000000"));
    row.setFinanceAmount(new BigDecimal("10.00000000"));
    row.setOaAmount(new BigDecimal("8.75000000"));
    row.setDiffAmount(new BigDecimal("-1.25000000"));
    row.setCuAffected(1);
    row.setTraceJson("{\"financeCu\":90.0,\"oaCu\":80.0}");
    row.setBusinessUnitType("COMMERCIAL");
    return row;
  }
}
