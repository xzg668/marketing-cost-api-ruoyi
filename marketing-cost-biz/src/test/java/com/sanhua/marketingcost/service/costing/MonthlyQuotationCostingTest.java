package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import java.math.*;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyQuotationCostingTest {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(json);
  final PricePrepareItemMapper rows = mock(PricePrepareItemMapper.class);
  final MonthlyQuotationCosting costing = new MonthlyQuotationCosting(rows, mock(MonthlyCostVersionService.class), codec, json);
  final MonthlyPriceRequest request = new MonthlyPriceRequest(
      new MonthlyPriceFamilyKey(YearMonth.of(2026,11),"COMMERCIAL","H",null,"件","CNY",TaxBasis.EXCLUDED,null),
      "210",NonCuConditions.empty(),BigDecimal.ONE);
  final Selection selected = new Selection(7L,
      new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any()),
      PriceFact.household(new BigDecimal("18.9343"),new BigDecimal(".085"),SourceEvidence.ofJson("{\"household\":true}")));

  PricePrepareItem row(long id,String path,String customerPrice,boolean compact) {
    var plan=new MonthlyAdoptionPlan(Map.of(request,selected),List.of(new Use(path,request,new BigDecimal("1.25"))),Set.of());
    var value=new QuotationPriceSession.Value(request,selected,selected.fact().price(),new BigDecimal(customerPrice));
    var captured=MonthlyPreparationPrice.of(value,path,new BigDecimal("1.25"))
        .withInputs(MonthlyPreparationInputs.captureDirect(plan,path));
    var row=new PricePrepareItem();row.setId(id);row.setStatus("READY");row.setSettlementKey(path);
    row.setMaterialCode("H");row.setPriceOrgCode("210");row.setResultRefType(MonthlyPreparationPrice.KIND);
    row.setQuantity(new BigDecimal("1.25"));row.setUnitPrice(new BigDecimal(customerPrice).setScale(8,RoundingMode.HALF_UP));
    row.setAmount(row.getQuantity().multiply(new BigDecimal(customerPrice)).setScale(8,RoundingMode.HALF_UP));
    row.setInputSnapshotJson(compact?codec.compact(codec.encode(captured)):codec.encode(captured));return row;
  }
  QuoteCostRunVersion version() {
    var result=new QuoteCostRunVersion();result.setOaPricePrepareNo("OA");result.setFinancePricePrepareNo("FIN");return result;
  }

  @Test void householdWUsesFullCapturedPriceAcrossOccurrencesAndCompressedPreparations() {
    String price="20.463109690363685593";
    when(rows.selectList(any())).thenReturn(List.of(row(1,"root/A",price,false),row(2,"root/B",price,true)),
        List.of(row(3,"root/A",price,true),row(4,"root/B",price,false)));
    var prepared=costing.materials(version());
    assertThat(prepared.materials()).isEmpty();assertThat(prepared.plan().uses()).hasSize(2);
    assertThat(prepared.householdCustomerPrices().get(7L)).isEqualByComparingTo(price);
    assertThat(prepared.householdCustomerPrices().get(7L)).isNotEqualByComparingTo(new BigDecimal(price).setScale(8,RoundingMode.HALF_UP));
    assertThat(prepared.bindings()).hasSize(2);
    assertThatThrownBy(()->prepared.householdCustomerPrices().put(8L,BigDecimal.ONE)).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test void onePublicHouseholdFactCannotHideDifferentCustomerWValues() {
    when(rows.selectList(any())).thenReturn(List.of(row(1,"root/A","20.463109690363685593",false),
        row(2,"root/B","20.463109690363685594",true)),
        List.of(row(3,"root/A","20.463109690363685593",false),row(4,"root/B","20.463109690363685594",false)));
    assertThatThrownBy(()->costing.materials(version())).hasMessageContaining("不同客户单价");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"MAKE_PART_PRICE,MAKE_PART_V1,make-0/raw", "PACKAGE_PRICE,PACKAGE_PRICE_V1,pack-0"})
  void aggregateOwnConsumptionAndIndependentDescendantMayShareOneLockedUnitPrice(
      String type,String calculator,String suffix) throws Exception {
    var raw=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,11),"COMMERCIAL","RAW",null,"件","CNY",TaxBasis.EXCLUDED,null),
        "210",NonCuConditions.empty(),BigDecimal.ONE);
    var price=new Selection(7L,new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any()),
        PriceFact.normal(new BigDecimal("3"),SourceEvidence.ofJson("{\"source\":\"fixed\"}"),Set.of()));
    String root="root/MAKE",child=root+"/RAW";
    var plan=new MonthlyAdoptionPlan(Map.of(raw,price),List.of(new Use(root+"/"+suffix,raw,BigDecimal.ONE),
        new Use(child,raw,new BigDecimal("2"))),Set.of());
    var parent=new PricePrepareItem();parent.setId(11L);parent.setStatus("READY");parent.setSettlementKey(root);
    parent.setMaterialCode("MAKE");parent.setResultRefType(type);parent.setPriceOrgCode("210");
    parent.setQuantity(BigDecimal.ONE);parent.setUnitPrice(new BigDecimal("3"));parent.setAmount(new BigDecimal("3"));
    parent.setInputSnapshotJson(json.writeValueAsString(Map.of("calculatorVersion",calculator)));
    var evidence=new MonthlyPreparationEvidence(codec);
    parent.setInputSnapshotJson(evidence.attach(parent,evidence.capture(parent,plan,root)));
    var value=new QuotationPriceSession.Value(raw,price,new BigDecimal("3"),new BigDecimal("3"));
    var childRow=new PricePrepareItem();childRow.setId(12L);childRow.setStatus("READY");childRow.setSettlementKey(child);
    childRow.setMaterialCode("RAW");childRow.setResultRefType(MonthlyPreparationPrice.KIND);childRow.setPriceOrgCode("210");
    childRow.setQuantity(new BigDecimal("2"));childRow.setUnitPrice(new BigDecimal("3"));childRow.setAmount(new BigDecimal("6"));
    childRow.setInputSnapshotJson(codec.encode(MonthlyPreparationPrice.of(value,child,new BigDecimal("2"))
        .withInputs(MonthlyPreparationInputs.captureDirect(plan,child))));
    when(rows.selectList(any())).thenReturn(List.of(parent,childRow),List.of(parent,childRow));
    var actual=costing.materials(version());
    assertThat(actual.plan().selections()).hasSize(1);
    assertThat(actual.plan().uses()).hasSize(2);
    var z=BigDecimal.ZERO;
    var result=MonthlyProductCost.calculate(actual.plan(),new MonthlyProductCost.Ordinary(actual.materials(),z,z,z,z,z,
        BigDecimal.ONE,z,z,z,z),null);
    assertThat(result.financeMaterialCost()).isEqualByComparingTo("9");
    // The saved JSON must retain the exact occurrence ownership, including shared monthly IDs.
    var restored=json.readValue(json.writeValueAsString(actual.materials()),
        new com.fasterxml.jackson.core.type.TypeReference<List<MonthlyProductCost.MaterialLine>>() {});
    assertThat(restored).isEqualTo(actual.materials());
    assertThat(MonthlyProductCost.calculate(actual.plan(),new MonthlyProductCost.Ordinary(restored,z,z,z,z,z,
        BigDecimal.ONE,z,z,z,z),null).financeMaterialCost()).isEqualByComparingTo("9");
  }
}
