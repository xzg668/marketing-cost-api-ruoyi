package com.sanhua.marketingcost.service.pricing;
import static org.assertj.core.api.Assertions.assertThat;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
class SupplierRatioPricePolicyTest {
  @ParameterizedTest
  @CsvSource(value={"1,NULL,A","0.65,NULL,A","0.4,NULL,NONE","NULL,NULL,NONE","0,NULL,NONE","0,0,B","0.5,0.5,B","0.6,0.4,A","NULL,1,B"})
  void nullIsNotZeroAndOnlyDecisiveRatiosChoose(String a,String b,String expected) {
    var candidates=List.of(new SupplierPriceCandidate("A","A","A",new BigDecimal("12"),null,null),new SupplierPriceCandidate("B","B","B",new BigDecimal("18"),null,null));
    var result=SupplierRatioPricePolicy.choose(candidates,List.of(ratio("A",a),ratio("B",b)));
    assertThat(result==null?"NONE":result.supplierCode()).isEqualTo(expected);
  }
  private SupplierSupplyRatio ratio(String code,String value) {var r=new SupplierSupplyRatio();r.setSupplierCode(code);r.setSupplyRatio("NULL".equals(value)?null:new BigDecimal(value));return r;}
}
