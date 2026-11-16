package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.*;
import static com.sanhua.marketingcost.service.effectivebom.SinteredBaseTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.service.materialshape.QuoteMaterialShapeInputs;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class QuotationOutsourceChildrenTest {
  private Map<String,EffectiveBomShapeDecision> choices(String selected) {
    var input=spy(new QuoteMaterialShapeInputs(mock(JdbcTemplate.class)));
    doReturn(new QuoteMaterialShapeInputs.Catalog(List.of(
        new QuoteMaterialShapeInputs.Source("210",BASE,"MAKE","MANUFACTURE",0),
        new QuoteMaterialShapeInputs.Source("210",BASE,"LINKED","OUTSOURCE",1)),
        List.of(new QuoteMaterialShapeInputs.Choice("210",BASE,selected,1))))
        .when(input).read(eq("COMMERCIAL"),eq("2026-11"),eq(160L),anyCollection());
    var original=new LinkedHashMap<String,EffectiveBomShapeDecision>();
    for(var d:decisions(supplierDecision(BASE,QuoteMaterialShape.MANUFACTURE,"INTERNAL",true,actionJson(GOLD))))
      original.put(d.materialCode(),d);
    original.put(BLANK,shape(BLANK,QuoteMaterialShape.MANUFACTURE));
    original.put("RAW",shape("RAW",QuoteMaterialShape.PURCHASE));
    return input.apply("COMMERCIAL","2026-11",160L,original,Map.of(BASE,"210"));
  }
  private EffectiveBomBuildResult build(String selected) {
    var nodes=new ArrayList<>(standardTree());
    nodes.get(2).setShapeAttr("制造件");
    nodes.add(node(6,"RAW",BLANK,3,"/P/"+BASE+"/"+BLANK+"/RAW/","2.5","采购件"));
    // The same gold material elsewhere must remain priced.
    nodes.add(node(7,GOLD,"P",1,"/P/"+GOLD+"/","0.001","采购件"));
    return builder().build(new EffectiveBomBuildRequest(nodes,List.of(),Map.of(),choices(selected),128));
  }
  @Test void manualOutsourceRetainsManufacturedBlankAndItsTreeButExcludesOnlyConfiguredGold() {
    var result=build("OUTSOURCE");
    assertThat(result.blocked()).isFalse();
    assertThat(materialCodes(result)).contains(BASE,BLANK,"RAW");
    assertThat(result.nodes()).filteredOn(n->n.materialCode().equals(BLANK)).singleElement()
        .satisfies(n->assertThat(n.effectiveMaterialShape()).isEqualTo(QuoteMaterialShape.MANUFACTURE));
    assertThat(result.nodes()).filteredOn(n->n.materialCode().equals(GOLD)).singleElement()
        .satisfies(n->assertThat(n.nodePath()).isEqualTo("/P/"+GOLD+"/"));
    assertThat(result.exclusions()).anySatisfy(x->assertThat(x.excludedRootMaterialCode()).isEqualTo(GOLD));
  }
  @Test void manufactureAndRestoringDefaultKeepGoldAndBlankWithoutMutatingTheSource() {
    for(String selected:Arrays.asList("MANUFACTURE",null)) {
      var result=build(selected);
      assertThat(result.blocked()).isFalse();
      assertThat(materialCodes(result)).contains(BASE,BLANK,"RAW");
      assertThat(result.nodes()).filteredOn(n->n.materialCode().equals(GOLD)).hasSize(2);
    }
  }
}
