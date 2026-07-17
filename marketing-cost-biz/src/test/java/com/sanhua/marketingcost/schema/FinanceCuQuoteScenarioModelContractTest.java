package com.sanhua.marketingcost.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostPriceScenario;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCuMaterialDiffItem;
import com.sanhua.marketingcost.mapper.QuoteCostPriceScenarioMapper;
import com.sanhua.marketingcost.mapper.QuoteCuMaterialDiffItemMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FCQ-01 双场景实体与 Mapper 契约")
class FinanceCuQuoteScenarioModelContractTest {

  @Test
  @DisplayName("价格准备实体映射场景字段和稳定结算键")
  void pricePrepareModelsExposeScenarioFields() {
    PricePrepareBatch batch = new PricePrepareBatch();
    batch.setScenarioType("FINANCE_QUOTE_BASE");
    batch.setScenarioGroupNo("SCG-1");
    batch.setSourcePrepareNo("PPR-OA-1");
    PricePrepareItem item = new PricePrepareItem();
    item.setSettlementKey("SETTLEMENT:1");

    assertThat(batch.getScenarioType()).isEqualTo("FINANCE_QUOTE_BASE");
    assertThat(batch.getScenarioGroupNo()).isEqualTo("SCG-1");
    assertThat(batch.getSourcePrepareNo()).isEqualTo("PPR-OA-1");
    assertThat(item.getSettlementKey()).isEqualTo("SETTLEMENT:1");
  }

  @Test
  @DisplayName("场景汇总和差额明细实体映射到 V187 新表")
  void newEntitiesMapToScenarioTables() {
    assertThat(QuoteCostPriceScenario.class.getAnnotation(TableName.class).value())
        .isEqualTo("lp_quote_cost_price_scenario");
    assertThat(QuoteCuMaterialDiffItem.class.getAnnotation(TableName.class).value())
        .isEqualTo("lp_quote_cu_material_diff_item");
    assertThat(BaseMapper.class).isAssignableFrom(QuoteCostPriceScenarioMapper.class);
    assertThat(BaseMapper.class).isAssignableFrom(QuoteCuMaterialDiffItemMapper.class);

    QuoteCuMaterialDiffItem item = new QuoteCuMaterialDiffItem();
    item.setSettlementKey("RAW_COMPONENT:1");
    item.setDiffAmount(new BigDecimal("-1.25000000"));
    item.setTraceJson("{\"financeCu\":90.0,\"oaCu\":80.0}");
    assertThat(item.getDiffAmount()).isNegative();
    assertThat(item.getTraceJson()).contains("financeCu");
  }

  @Test
  @DisplayName("成本版本和结果实体暴露双场景金额快照")
  void costModelsExposeScenarioSummaryFields() {
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setOaPricePrepareNo("PPR-OA-1");
    version.setFinancePricePrepareNo("PPR-FIN-1");
    version.setFinanceCuPrice(new BigDecimal("90.00000000"));
    version.setOaCuPrice(new BigDecimal("102.03900000"));
    version.setFinanceBasePriceId(99L);
    version.setCuMaterialAdjustment(new BigDecimal("2.50000000"));
    version.setFinalQuoteAmount(new BigDecimal("102.50000000"));

    CostRunResult result = new CostRunResult();
    result.setFinanceMaterialCost(new BigDecimal("40.00000000"));
    result.setOaMaterialCost(new BigDecimal("42.50000000"));
    result.setCuMaterialAdjustment(new BigDecimal("2.50000000"));
    result.setFinalQuoteAmount(new BigDecimal("102.50000000"));

    assertThat(version.getFinanceBasePriceId()).isEqualTo(99L);
    assertThat(version.getFinalQuoteAmount()).isEqualByComparingTo("102.50000000");
    assertThat(result.getFinanceMaterialCost()).isEqualByComparingTo("40.00000000");
    assertThat(result.getFinalQuoteAmount()).isEqualByComparingTo("102.50000000");
  }
}
