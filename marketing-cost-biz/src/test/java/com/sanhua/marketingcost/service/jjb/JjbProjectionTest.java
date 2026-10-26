package com.sanhua.marketingcost.service.jjb;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService.Stored;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class JjbProjectionTest {
  final JjbProjection projection=new JjbProjection(new ObjectMapper());
  QuoteCostRunVersion version() {var v=new QuoteCostRunVersion();v.setId(8L);v.setOaNo("OA");v.setOaFormItemId(3L);v.setCostRunNo("RUN");v.setProductCode("000000001");v.setStatus("SUCCESS");v.setAlgorithmVersion("COST_V3");v.setBusinessUnitType("COMMERCIAL");return v;}
  CostRunCostItem cost(long id,String code,String name,String amount) {var c=new CostRunCostItem();c.setId(id);c.setCostCode(code);c.setCostName(name);c.setAmount(amount==null?null:new BigDecimal(amount));c.setSourceTable("historical_source");c.setSourceId(id+100);return c;}
  JjbReadModel read(QuoteCostRunVersion v,CostRunCostItem... costs) {return projection.project(new Stored(v,null,List.of(),List.of(costs)),new OaForm(),new OaFormItem(),List.of());}
  @Test void missingLegacyTotalsAndFeesStayMissingAndHouseholdIsAbsent() {
    var model=read(version());assertThat(model.totals().totalCost()).isNull();assertThat(model.totals().cuMaterialAdjustment()).isNull();assertThat(model.totals().finalQuoteAmount()).isNull();
    assertThat(model.rows().stream().filter(r->r.kind().equals("FEE"))).hasSize(4).allMatch(r->r.amount()==null);
    assertThat(model.rows()).noneMatch(r->r.section().equals("HOUSEHOLD"));assertThat(model.monthlyPrices()).isEmpty();
    assertThat(model.warnings()).anyMatch(w->w.contains("不补算"));
  }
  @Test void quotationHeaderUsesEveryAvailableRequestFieldAndLeavesAbsentMetalsBlank() {
    var form=new OaForm();form.setApplicantName("张三");form.setApplicantDept("销售部");form.setApplicantOffice("一处");
    form.setOverseasSalesMode("是");form.setPriceLinkMode("联动");form.setSourceBusinessDivision("阀事业部");
    form.setCopperPrice(new BigDecimal("100"));form.setSus304Price(new BigDecimal("21000"));
    form.setAluminumPrice(BigDecimal.ZERO);form.setGoldPrice(new BigDecimal("700.123456"));
    var item=new OaFormItem();item.setProductName("阀");item.setSunlModel("ABC");item.setSpec("DN20");item.setBusinessType("新品");
    var v=version();v.setFinanceCuPrice(new BigDecimal("90"));v.setOaCuPrice(new BigDecimal("100"));
    var model=projection.project(new Stored(v,null,List.of(),List.of()),form,item,List.of());
    assertThat(model.header().quotation()).containsEntry("applicantName","张三").containsEntry("applicantDept","销售部")
        .containsEntry("applicantOffice","一处").containsEntry("division","阀事业部").containsEntry("spec","DN20")
        .containsEntry("businessType","新品").containsEntry("overseasSalesMode","是").containsEntry("priceLinkMode","联动")
        .containsEntry("copperPrice","100.000000").containsEntry("customerCuPrice","0.100000").containsEntry("sus304Price","21000.000000")
        .containsEntry("aluminumPrice","0.000000").containsEntry("goldPrice","700.123456")
        .doesNotContainKeys("sus316lPrice","brassPrice","brassScrapPrice");
    assertThat(model.header().financeCuPrice()).isEqualTo("90.000000");
    assertThat(model.header().productName()).isEqualTo("阀");
  }
  @Test void oldSavedZerosAreLegacyAndDoNotBecomeExplicitNoFee() {
    var m=read(version(),cost(1,"OTHER_EXP_TECH_MOULD","模具费","0"));
    var fee=m.rows().stream().filter(r->r.kind().equals("FEE")).findFirst().orElseThrow();
    assertThat(fee.amount()).isEqualTo("0.000000");assertThat(fee.feeState()).isEqualTo("LEGACY");
  }
  @Test void optionalFeePolicyUsesItsSavedEncodingAndNeverCurrentProfile() {
    var v=version();v.setFeePolicyVersion("FEE_PROFILE_OPTIONAL_V2");v.setTechDataInputJson("{\"productFees\":{\"encodingVersion\":2}}");
    var m=read(v,cost(1,"OTHER_EXP_TECH_MOULD","模具费","0"));
    assertThat(m.rows().stream().filter(r->r.code()!=null&&r.code().equals("OTHER_EXP_TECH_MOULD")).findFirst().orElseThrow().feeState()).isEqualTo("EXPLICIT_NONE");
    assertThat(m.rows().stream().filter(r->r.kind().equals("FEE")&&r.amount()==null)).hasSize(3);
  }
  @Test void multipleKnownHistoricalFeesSumExactlyAndRetainEverySource() {
    var m=read(version(),cost(1,"OTHER_EXP_1"," 模具费 ","0.123456"),cost(2,"OTHER_EXP_2","模具费","0.654321"),cost(3,"OTHER_EXP_3","模具维修费","9"));
    var fee=m.rows().stream().filter(r->"OTHER_EXP_TECH_MOULD".equals(r.code())).findFirst().orElseThrow();
    assertThat(fee.amount()).isEqualTo("0.777777");assertThat(fee.costItemIds()).containsExactly(1L,2L);assertThat(fee.sources()).hasSize(2);
    assertThat(m.rows()).anyMatch(r->"模具维修费".equals(r.name())&&r.amount().equals("9.000000"));
  }
  @Test void incompleteHistoricalFeeGroupStaysBlankAndOriginalsRemainVisible() {
    var m=read(version(),cost(1,"OTHER_EXP_1","模具费",null),cost(2,"OTHER_EXP_2","模具费","2"));
    assertThat(m.rows().stream().filter(r->r.kind().equals("FEE")).findFirst().orElseThrow().amount()).isNull();
    assertThat(m.rows().stream().filter(r->r.section().equals("HISTORICAL_DETAIL"))).hasSize(2);
    assertThat(m.warnings()).anyMatch(w->w.contains("未将其当作零"));
  }
  @Test void historicalDisagreementRemainsVisibleWithoutAdjustingEitherTotal() {
    var v=version();v.setTotalCost(new BigDecimal("12"));v.setCuMaterialAdjustment(new BigDecimal("-2"));v.setFinalQuoteAmount(new BigDecimal("11"));
    var m=read(v,cost(1,"TOTAL","总额","13"));
    assertThat(m.totals()).isEqualTo(new JjbReadModel.Totals("12.000000","-2.000000","11.000000"));
    assertThat(m.rows()).anyMatch(r->r.name().contains("原保存值")&&r.amount().equals("13.000000"));assertThat(m.warnings()).hasSize(2);
  }
  @Test void largeAmountsSerializeAsStringsWithAllSixPlaces() throws Exception {
    var v=version();v.setTotalCost(new BigDecimal("999999999973.140060"));v.setCuMaterialAdjustment(BigDecimal.ZERO);v.setFinalQuoteAmount(v.getTotalCost());
    var m=read(v,cost(1,"TOTAL","总额","999999999973.140060"),cost(2,"OTHER_EXP_TECH_DEPRECIATION","折旧费","999999999900.999999"));
    var tree=new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(m));assertThat(tree.path("totals").path("totalCost").isTextual()).isTrue();
    assertThat(tree.path("totals").path("totalCost").asText()).isEqualTo("999999999973.140060");assertThat(tree.path("totals").path("cuMaterialAdjustment").asText()).isEqualTo("0.000000");
  }
  @Test void threeFeeSubtotalOnlyAddsSavedAmountsAndNeverReappliesK() {
    var v=version();var adjustment=cost(1,"ADJUSTED_MANUFACTURE_COST","调整制造","1.296225");adjustment.setRate(new BigDecimal("1.05"));
    var m=read(v,adjustment,cost(2,"MGMT_EXP","管理","0.129623"),cost(3,"SALES_EXP","营业","0.324056"),cost(4,"FIN_EXP","财务","0.025925"));
    assertThat(m.rows()).anyMatch(r->r.key().equals("THREE_SUBTOTAL")&&r.amount().equals("0.479604"));
    assertThat(m.header().coefficient()).isEqualTo("1.05");assertThat(m.totals().totalCost()).isNull();
  }
  @Test void historicalRollupChildrenUseOnlyFrozenEvidenceAndKeepTheParentAmountOnce() {
    var p=new CostRunPartItem();p.setId(20L);p.setPartCode("PARENT");p.setPartName("母件");p.setQty(new BigDecimal("2"));p.setAmount(new BigDecimal("6"));
    var trace=new CostRunTraceSnapshot();trace.setId(30L);trace.setPartItemId(20L);
    trace.setSourceSnapshotJson("{\"rollupDisplayComponents\":[{\"childMaterialCode\":\"CHILD\",\"childMaterialName\":\"子件\",\"childMaterialSpec\":\"OLD-DRAW\",\"childUnitCost\":3,\"childQtyPerTop\":2}]}");
    var model=projection.project(new Stored(version(),null,List.of(p),List.of()),new OaForm(),new OaFormItem(),List.of(trace));
    assertThat(model.rows().get(0).amount()).isEqualTo("6.000000");
    var explanation=model.rows().get(1);assertThat(explanation.kind()).isEqualTo("EXPLANATION");assertThat(explanation.code()).isEqualTo("PARENT\n【CHILD】");
    assertThat(explanation.drawingNo()).isEqualTo("OLD-DRAW");assertThat(explanation.amount()).isNull();assertThat(explanation.partItemIds()).containsExactly(20L);
  }
}
