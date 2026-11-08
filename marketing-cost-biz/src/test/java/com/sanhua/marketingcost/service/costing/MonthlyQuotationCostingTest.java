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
}
