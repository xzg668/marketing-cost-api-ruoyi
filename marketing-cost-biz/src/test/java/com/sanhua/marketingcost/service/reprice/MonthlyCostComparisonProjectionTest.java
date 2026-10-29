package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyCostComparisonProjectionTest {
  final MonthlyCostComparisonProjection projection=new MonthlyCostComparisonProjection(new MonthlyCostComparisonSalary(new ObjectMapper()));
  MonthlyCostComparisonInputs.Loaded legacy(boolean savedPath) {
    var v=new QuoteCostRunVersion();v.setId(1L);v.setOaNo("OA");v.setOaFormItemId(1L);v.setProductCode("000A");v.setBusinessUnitType("COMMERCIAL");v.setPricingMonth("2026-10");v.setTrialFinishedAt(LocalDateTime.now());
    var p=new CostRunPartItem();p.setId(2L);p.setPartCode("000B");p.setPartName("B");p.setBomRowId(77L);p.setPricePrepareItemId(88L);p.setQty(BigDecimal.ONE);p.setAmount(new BigDecimal("12.30"));
    var bom=new BomCostingRow();bom.setId(77L);bom.setPath("000A/000B");bom.setUnit("件");
    return new MonthlyCostComparisonInputs.Loaded(new MonthlyCostVersionService.Stored(v,null,List.of(p),List.of()),null,
        savedPath?Map.of(77L,bom):Map.of(),List.of(),List.of(),Map.of());
  }
  @Test void missingHistoricalBomPathIsRejectedEvenWithAnExistingPreparationId(){
    assertThatThrownBy(()->projection.project(legacy(false))).hasMessageContaining("历史部品未保存计价路径");
  }
  @Test void savedHistoricalPathAndMaterialAmountArePreservedWithoutInventingProductFees(){
    var p=projection.project(legacy(true));assertThat(p.lines().getFirst().key().pricingPaths()).containsExactly("000A/000B");
    assertThat(p.lines().getFirst().amounts().get(MonthlyCostComparison.Metric.MATERIAL)).isEqualByComparingTo("12.30");
    assertThat(p.product().amounts().get(MonthlyCostComparison.Metric.DIRECT_LABOR)).isNull();
  }
}
