package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.SupplierPriceDecisionMapper;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SingleSupplierPricePrecedenceTest {
  private final SupplierSupplyRatioMapper ratios=mock(SupplierSupplyRatioMapper.class);
  private final SupplierPriceDecisionMapper decisions=mock(SupplierPriceDecisionMapper.class);
  private final SupplierPriceSelectionService service=new SupplierPriceSelectionService(ratios,decisions);
  private final SupplierPriceScope scope=new SupplierPriceScope("COMMERCIAL","TEST",12L,"2026-11",
      LocalDate.of(2026,11,11),"210","301110039","LINKED");
  private final SupplierPriceCandidate keyu=price("4240","S000052","浙江科宇金属材料有限公司","58.384778761061946903");

  @Test void onlyQuotedSupplierWinsEvenWhenAnotherSupplierHasOneHundredPercent() {
    var chosen=service.selectPrepared(scope,List.of(keyu),List.of(ratio("S001315","1")),null);
    assertThat(chosen.candidate()).isSameAs(keyu);
    assertThat(chosen.rule()).isEqualTo("SINGLE");
    assertThat(chosen.ratio()).isNull();
    assertThat(chosen.review()).isNull();
    verifyNoInteractions(ratios,decisions);
  }

  @Test void sourcePrefilterCannotRemoveTheOnlyQuotedSupplier() {
    assertThat(SupplierRatioPricePolicy.forPrimarySupplier(List.of(keyu),List.of(ratio("S001315","1")),
        SupplierPriceCandidate::supplierCode,SupplierPriceCandidate::supplierName)).containsExactly(keyu);
  }

  @Test void multipleRowsOfOneSupplierRemainOneSupplier() {
    var lower=price("146","S000052",keyu.supplierName(),"55");
    var chosen=service.selectPrepared(scope,List.of(lower,keyu),List.of(ratio("S001315","1")),null);
    assertThat(chosen.candidate()).isSameAs(keyu);
    assertThat(chosen.rule()).isEqualTo("SAME_SUPPLIER_HIGH");
    assertThat(chosen.ratio()).isNull();
  }

  @ParameterizedTest @CsvSource({"0","1"})
  void soleSupplierDoesNotDependOnItsOwnRatio(String value) {
    var chosen=service.selectPrepared(scope,List.of(keyu),List.of(ratio("S000052",value)),null);
    assertThat(chosen.candidate()).isSameAs(keyu);
    assertThat(chosen.rule()).isEqualTo("SINGLE");
    assertThat(chosen.ratio()).isNull();
  }

  @Test void preferredSourceDoesNotReadRatiosForTheOnlySupplier() {
    var resolver=mock(SupplierSupplyRatioResolveService.class);
    var selected=new SupplierPreferredPriceSelector(resolver).select(List.of(keyu),"COMMERCIAL","301110039",
        "圆形黄铜棒","C3604BDS Φ11",scope.pricingDate(),SupplierPriceCandidate::supplierName,SupplierPriceCandidate::supplierCode);
    assertThat(selected.row()).isSameAs(keyu);
    assertThat(selected.supplyRatioRecordId()).isNull();
    assertThat(selected.matchMode()).isEqualTo("SINGLE_SUPPLIER");
    verifyNoInteractions(resolver);
  }

  @Test void twoSuppliersStillUseRatiosRatherThanTakingFirstOrHighest() {
    var other=price("B","S001315","丰山（连云港）新材料有限公司","50");
    var chosen=service.selectPrepared(scope,List.of(keyu,other),List.of(ratio("S001315","1")),null);
    assertThat(chosen.candidate()).isSameAs(other);
    assertThat(chosen.rule()).isEqualTo("RATIO_MAX");
    assertThat(chosen.ratio().getSupplierCode()).isEqualTo("S001315");
    assertThat(service.selectPrepared(scope,List.of(keyu,other),List.of(),null).review()).isNotNull();
  }

  @Test void multipleSuppliersDoNotFallBackWhenConfiguredPrimaryHasNoQuote() {
    var other=price("B","B","乙供应商","50");
    var chosen=service.selectPrepared(scope,List.of(keyu,other),List.of(ratio("S001315","1")),null);
    assertThat(chosen.candidate()).isNull();
    assertThat(chosen.rule()).isEqualTo(SupplierPriceSelectionService.NO_PRIMARY_SUPPLIER_PRICE);
  }

  private static SupplierPriceCandidate price(String key,String code,String name,String amount) {
    return new SupplierPriceCandidate(key,code,name,new BigDecimal(amount),null,null);
  }
  private static SupplierSupplyRatio ratio(String code,String value) {
    var row=new SupplierSupplyRatio();row.setSupplierCode(code);row.setSupplierName(code);
    row.setSupplyRatio(new BigDecimal(value));return row;
  }
}
