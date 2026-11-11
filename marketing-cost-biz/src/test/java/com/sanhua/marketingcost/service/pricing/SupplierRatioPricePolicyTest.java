package com.sanhua.marketingcost.service.pricing;
import static org.assertj.core.api.Assertions.assertThat;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
class SupplierRatioPricePolicyTest {
  @org.junit.jupiter.api.Test void nameOnlyOriginalMatchesCodedPrimarySupplier() {
    var primary=ratio("S000332","1");primary.setSupplierName("杭州华光焊接新材料股份有限公司");
    var original=new SupplierPriceCandidate("8144","","杭州华光焊接新材料股份有限公司",new BigDecimal("3365.873080"),null,null);
    assertThat(SupplierRatioPricePolicy.forPrimarySupplier(List.of(original),List.of(primary),
        SupplierPriceCandidate::supplierCode,SupplierPriceCandidate::supplierName)).containsExactly(original);
    assertThat(SupplierRatioPricePolicy.match(SupplierRatioPricePolicy.index(List.of(primary)),original)).isSameAs(primary);
  }
  @org.junit.jupiter.api.Test void missingCodeUsesUniqueBestNameWhileExplicitCodeKeepsPriority() {
    var full=ratio("S000332","0.8");full.setSupplierName("杭州华光焊接新材料股份有限公司");
    var shorter=ratio("OTHER","0.2");shorter.setSupplierName("杭州华光有限公司");
    var rows=List.of(full,shorter);
    var original=new SupplierPriceCandidate("P",null,"杭州华光焊接新材料",BigDecimal.TEN,null,null);
    assertThat(SupplierRatioPricePolicy.match(SupplierRatioPricePolicy.index(rows),original)).isSameAs(full);
    var explicit=new SupplierPriceCandidate("P","OTHER",original.supplierName(),BigDecimal.TEN,null,null);
    assertThat(SupplierRatioPricePolicy.match(SupplierRatioPricePolicy.index(rows),explicit)).isSameAs(shorter);
    var conflicting=new SupplierPriceCandidate("P","UNKNOWN",full.getSupplierName(),BigDecimal.TEN,null,null);
    assertThat(SupplierRatioPricePolicy.match(SupplierRatioPricePolicy.index(rows),conflicting)).isNull();
  }
  @org.junit.jupiter.api.Test void equalNameMatchesAcrossDifferentCodesRequireAnExplicitCode() {
    var a=ratio("A","0.8");a.setSupplierName("杭州华光焊接新材料股份有限公司");
    var b=ratio("B","0.2");b.setSupplierName(a.getSupplierName());
    var candidate=new SupplierPriceCandidate("P",null,a.getSupplierName(),BigDecimal.TEN,null,null);
    org.assertj.core.api.Assertions.assertThatThrownBy(()->SupplierRatioPricePolicy.match(SupplierRatioPricePolicy.index(List.of(a,b)),candidate))
        .hasMessageContaining("供应商").hasMessageContaining("A").hasMessageContaining("B");
  }
  @org.junit.jupiter.api.Test void selectionRetainsNameMatchedEvidenceAndCanRequestReviewWithMissingCodes() {
    var service=new SupplierPriceSelectionService(org.mockito.Mockito.mock(com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper.class),
        org.mockito.Mockito.mock(com.sanhua.marketingcost.mapper.SupplierPriceDecisionMapper.class));
    var scope=new SupplierPriceScope("COMMERCIAL","TEST",1L,"2026-10",java.time.LocalDate.of(2026,10,1),"210","M","FIXED");
    var a=ratio(null,"0.85");a.setSupplierName("甲供应商");
    var b=ratio(null,"0.15");b.setSupplierName("乙供应商");
    var ca=new SupplierPriceCandidate("A","A","甲供应商",new BigDecimal("12"),null,null);
    var cb=new SupplierPriceCandidate("B","B","乙供应商",new BigDecimal("18"),null,null);
    var chosen=service.selectPrepared(scope,List.of(ca,cb),List.of(a,b),null);
    assertThat(chosen.candidate()).isEqualTo(ca);assertThat(chosen.ratio()).isSameAs(a);
    a.setSupplyRatio(null);b.setSupplyRatio(null);
    var missing=service.selectPrepared(scope,List.of(ca,cb),List.of(a,b),null);
    assertThat(missing.candidate()).isNull();assertThat(missing.review()).isNotNull();
  }
  @org.junit.jupiter.api.Test void missingCodesMatchByNameAndZeroWithdrawsOnlyThatSupplier() {
    var a=ratio(null,"0.85");a.setSupplierName("甲供应商");
    var b=ratio(null,"0.15");b.setSupplierName("乙供应商");
    var ca=new SupplierPriceCandidate("A","A","甲供应商",new BigDecimal("12"),null,null);
    var cb=new SupplierPriceCandidate("B","B","乙供应商",new BigDecimal("18"),null,null);
    assertThat(SupplierRatioPricePolicy.choose(List.of(ca,cb),List.of(a,b))).isEqualTo(ca);
    a.setSupplyRatio(BigDecimal.ZERO);
    assertThat(SupplierRatioPricePolicy.choose(List.of(ca,cb),List.of(a,b))).isEqualTo(cb);
    a.setSupplierCode("OTHER");a.setSupplyRatio(BigDecimal.ONE);
    assertThat(SupplierRatioPricePolicy.match(SupplierRatioPricePolicy.index(List.of(a,b)),ca)).isNull();
  }
  @ParameterizedTest
  @CsvSource(value={"1,NULL,A","0.65,NULL,A","0.4,NULL,NONE","NULL,NULL,NONE","0,NULL,B","0,0,NONE","0.5,0.5,B","0.6,0.4,A","NULL,1,B"})
  void nullIsNotZeroAndOnlyDecisiveRatiosChoose(String a,String b,String expected) {
    var candidates=List.of(new SupplierPriceCandidate("A","A","A",new BigDecimal("12"),null,null),new SupplierPriceCandidate("B","B","B",new BigDecimal("18"),null,null));
    var result=SupplierRatioPricePolicy.choose(candidates,List.of(ratio("A",a),ratio("B",b)));
    assertThat(result==null?"NONE":result.supplierCode()).isEqualTo(expected);
  }
  private SupplierSupplyRatio ratio(String code,String value) {var r=new SupplierSupplyRatio();r.setSupplierCode(code);r.setSupplyRatio("NULL".equals(value)?null:new BigDecimal(value));return r;}
}
