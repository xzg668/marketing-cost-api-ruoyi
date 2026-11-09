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

  @Test void monthlyManufacturedPartDisplaysItsAlreadySavedWeightsAndPricesWithoutLegacyTraces() {
    var part=new CostRunPartItem();part.setId(20L);part.setPartCode("7218501041211");
    part.setPricePrepareItemId(2151L);part.setQty(BigDecimal.ONE);part.setAmount(new BigDecimal("0.582814"));
    var prepared=new PricePrepareItem();prepared.setId(2151L);prepared.setMaterialCode(part.getPartCode());
    prepared.setResultRefType("MAKE_PART_PRICE");
    prepared.setInputSnapshotJson("{\"calculatorVersion\":\"MAKE_PART_V1\",\"materialCode\":\"7218501041211\",\"rows\":[{\"parentMaterialNo\":\"7218501041211\",\"childMaterialNo\":\"201850068\",\"grossWeightG\":24.8,\"netWeightG\":14,\"rawUnitPrice\":0.66070796460177,\"scrapUnitPrice\":7.21238938053097}]}");
    var model=projection.project(new Stored(version(),null,List.of(part),List.of()),new OaForm(),new OaFormItem(),List.of(),List.of(prepared));
    var row=model.rows().getFirst();
    assertThat(row.grossWeight()).isEqualTo("24.8");assertThat(row.netWeight()).isEqualTo("14");
    assertThat(row.rawUnitPrice()).isEqualTo("0.660708");assertThat(row.scrapUnitPrice()).isEqualTo("7.212389");
    assertThat(row.amount()).isEqualTo("0.582814");
  }

  @Test void multipleManufacturingRowsShowTheirOwnWeightsWithoutAddingAmountsAgain() {
    var part=new CostRunPartItem();part.setId(21L);part.setPartCode("MAKE");part.setPricePrepareItemId(31L);
    part.setQty(BigDecimal.ONE);part.setAmount(new BigDecimal("5"));
    var prepared=new PricePrepareItem();prepared.setId(31L);prepared.setMaterialCode("MAKE");prepared.setResultRefType("MAKE_PART_PRICE");
    prepared.setInputSnapshotJson("{\"calculatorVersion\":\"MAKE_PART_V1\",\"materialCode\":\"MAKE\",\"rows\":[{\"childMaterialNo\":\"RAW-A\",\"grossWeightG\":0,\"netWeightG\":0,\"rawUnitPrice\":0},{\"childMaterialNo\":\"RAW-B\",\"grossWeightG\":50,\"netWeightG\":40,\"rawUnitPrice\":100,\"scrapUnitPrice\":10}]}");
    var model=projection.project(new Stored(version(),null,List.of(part),List.of()),new OaForm(),new OaFormItem(),List.of(),List.of(prepared));
    var rows=model.rows().stream().filter(row->row.partItemIds().contains(21L)).toList();
    assertThat(rows).hasSize(3);assertThat(rows.getFirst().amount()).isEqualTo("5.000000");
    assertThat(rows.get(1).grossWeight()).isEqualTo("0");assertThat(rows.get(1).rawUnitPrice()).isEqualTo("0.000000");
    assertThat(rows.get(1).scrapUnitPrice()).isNull();
    assertThat(rows.subList(1,3)).allMatch(row->row.amount()==null && row.kind().equals("EXPLANATION"));
    assertThat(rows.get(2).grossWeight()).isEqualTo("50");assertThat(rows.get(2).netWeight()).isEqualTo("40");
  }

  @Test void sheetQuoterUsesSavedCostOperatorInsteadOfRequestApplicant() {
    var v=version();v.setConfirmedBy("罗思雅");var form=new OaForm();form.setApplicantName("需求申请人");
    var sheet=projection.project(new Stored(v,null,List.of(),List.of()),form,new OaFormItem(),List.of());
    assertThat(sheet.header().quotation()).containsEntry("quoterName","罗思雅").containsEntry("applicantName","需求申请人");
  }

  @Test void packagingHasOneAmountWhilePreservingComponentEvidenceAndOriginalTotals() throws Exception {
    var part=new CostRunPartItem();part.setId(652L);part.setPartCode("9830000025711");part.setPartName("包装组件");
    part.setPricePrepareItemId(3154L);part.setQty(BigDecimal.ONE);part.setUnitPrice(new BigDecimal("0.308900"));
    part.setAmount(new BigDecimal("0.308900"));
    var prepared=new PricePrepareItem();prepared.setId(3154L);prepared.setMaterialCode(part.getPartCode());
    prepared.setInputSnapshotJson("{\"calculatorVersion\":\"PACKAGE_PRICE_V1\"}");
    var v=version();v.setTotalCost(new BigDecimal("35.369639"));v.setCuMaterialAdjustment(new BigDecimal("0.797707"));
    v.setFinalQuoteAmount(new BigDecimal("36.167346"));
    var costs=List.of(cost(1,"OTHER_EXP_PACKAGE","包装费","0.324345"),
        cost(2,"BOM_BUCKET_PACKAGE","包装","0.324345"),cost(3,"MATERIAL","材料费","20.420454"),
        cost(4,"INDIRECT_LABOR","辅助人工工资","0.091700"));
    var model=projection.project(new Stored(v,null,List.of(part),costs),new OaForm(),new OaFormItem(),List.of(),List.of(prepared));
    var packaging=model.rows().stream().filter(r->r.code()!=null && Set.of("9830000025711","OTHER_EXP_PACKAGE","BOM_BUCKET_PACKAGE").contains(r.code())).toList();
    assertThat(packaging).filteredOn(r->r.amount()!=null).singleElement().satisfies(r->{
      assertThat(r.amount()).isEqualTo("0.324345");assertThat(r.costItemIds()).containsExactly(1L,2L);
    });
    assertThat(packaging).singleElement().satisfies(r->{
      assertThat(r.name()).isEqualTo("包装费用");
      assertThat(r.partItemIds()).containsExactly(652L);
      assertThat(r.sources()).anyMatch(ref->"lp_price_prepare_item".equals(ref.table()) && ref.id()==3154L);
    });
    assertThat(model.rows()).noneMatch(r->"9830000025711".equals(r.code()));
    assertThat(model.rows()).filteredOn(r->"MATERIAL".equals(r.code())).singleElement()
        .satisfies(r->assertThat(r.remark()).contains("辅助人工另计"));
    assertThat(model.totals().totalCost()).isEqualTo("35.369639");
    assertThat(model.totals().finalQuoteAmount()).isEqualTo("36.167346");
    var output=new java.io.ByteArrayOutputStream();new JjbExcelRenderer().write(model,output);
    try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(output.toByteArray()))) {
      var sheet=workbook.getSheetAt(0);int amounts=0;
      for(var row:sheet) {
        var cell=row.getCell(6);
        if(cell!=null && cell.getCellType()==org.apache.poi.ss.usermodel.CellType.NUMERIC
            && Math.abs(cell.getNumericCellValue()-0.324345)<0.0000001) amounts++;
      }
      assertThat(amounts).isEqualTo(1);
    }
    assertThat(part.getAmount()).isEqualByComparingTo("0.308900");
  }
  @Test void weldingSubtotalDoesNotDuplicatePartsInPageOrExcelAmounts() throws Exception {
    var parts = new ArrayList<CostRunPartItem>();
    String[] codes = {"337106891", "311990114", "301010244"};
    String[] amounts = {"0.053740", "0.024230", "0.522847"};
    for (int i = 0; i < codes.length; i++) {
      var part = new CostRunPartItem(); part.setId(20L + i); part.setPartCode(codes[i]);
      part.setPartName(codes[i]); part.setAmount(new BigDecimal(amounts[i])); parts.add(part);
    }
    var bucket = cost(1, "BOM_BUCKET_WELD", "焊料", "0.600817");
    var material = cost(2, "MATERIAL", "材料费", "0.600817");
    var model = projection.project(new Stored(version(), null, parts, List.of(bucket, material)),
        new OaForm(), new OaFormItem(), List.of());
    assertThat(model.rows()).noneMatch(r -> "BOM_BUCKET_WELD".equals(r.code()));
    for (String code : codes) assertThat(model.rows()).filteredOn(r -> code.equals(r.code())).hasSize(1);
    assertThat(model.rows().stream().filter(r -> Set.of("ORDINARY_PARTS", "MATERIAL_DETAIL").contains(r.section()))
        .map(JjbReadModel.Row::amount).filter(Objects::nonNull).map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("0.600817");
    var output = new java.io.ByteArrayOutputStream(); new JjbExcelRenderer().write(model, output);
    try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(output.toByteArray()))) {
      BigDecimal sum = BigDecimal.ZERO; var exportedCodes = new ArrayList<String>();
      for (var row : workbook.getSheetAt(0)) {
        var label = row.getCell(1); var amount = row.getCell(6);
        if (label != null && "材料费".equals(label.toString())) break;
        if (amount != null && amount.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC)
          sum = sum.add(BigDecimal.valueOf(amount.getNumericCellValue()));
        if (label != null) assertThat(label.toString()).doesNotContain("焊料汇总").isNotEqualTo("焊料");
        if (row.getCell(0) != null) exportedCodes.add(row.getCell(0).toString());
      }
      for (String code : codes) assertThat(Collections.frequency(exportedCodes, code)).isEqualTo(1);
      assertThat(sum).isEqualByComparingTo("0.600817");
    }
    assertThat(bucket.getAmount()).isEqualByComparingTo("0.600817");
    assertThat(material.getAmount()).isEqualByComparingTo("0.600817");
  }

  @Test void oldPackagingOmissionIsIdentifiedWithoutRewritingHistoricalAmounts() {
    var parent=new CostRunPartItem();parent.setId(1L);parent.setPartCode("PACK");
    parent.setPricePrepareItemId(7L);parent.setAmount(new BigDecimal("0.308900"));
    var label=new CostRunPartItem();label.setId(2L);label.setPartCode("LABEL");label.setAmount(new BigDecimal("0.005154"));
    var prepared=new PricePrepareItem();prepared.setId(7L);
    prepared.setInputSnapshotJson("{\"calculatorVersion\":\"PACKAGE_PRICE_V1\"}");
    var fee=cost(3,"OTHER_EXP_PACKAGE","包装费","0.324345");fee.setBaseAmount(new BigDecimal("0.314054"));
    var bucket=cost(4,"BOM_BUCKET_PACKAGE","包装","0.324345");
    var material=cost(5,"MATERIAL","材料费","21.118831");
    var v=version();v.setTotalCost(new BigDecimal("36.379367"));v.setCuMaterialAdjustment(new BigDecimal("0.602968"));
    v.setFinalQuoteAmount(new BigDecimal("36.982335"));
    var old=projection.project(new Stored(v,null,List.of(parent,label),List.of(fee,bucket,material)),new OaForm(),new OaFormItem(),List.of(),List.of(prepared));
    assertThat(old.warnings()).anyMatch(message->message.contains("独立包装材料") && message.contains("重新整单核算"));
    assertThat(old.totals().totalCost()).isEqualTo("36.379367");
    assertThat(old.rows().stream().filter(row->"MATERIAL".equals(row.code())).findFirst().orElseThrow().amount()).isEqualTo("21.118831");
    fee.setBaseAmount(new BigDecimal("0.308900"));
    var corrected=projection.project(new Stored(v,null,List.of(parent,label),List.of(fee,bucket,material)),new OaForm(),new OaFormItem(),List.of(),List.of(prepared));
    assertThat(corrected.warnings()).noneMatch(message->message.contains("独立包装材料"));
  }

}
