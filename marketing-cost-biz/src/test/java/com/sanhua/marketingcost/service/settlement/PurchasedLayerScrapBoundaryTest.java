package com.sanhua.marketingcost.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.service.rule.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PurchasedLayerScrapBoundaryTest {
  private final BomSettlementRowBuildEngine engine = new BomSettlementRowBuildEngine(
      new BomSettlementRuleMatcher(new BomSettlementRuleConditionEvaluator(new ObjectMapper())),
      new BomByproductCostRuleMatcher(new BomByproductCostRuleConditionEvaluator(new ObjectMapper())));

  @Test void soleExplicitPurchasedPartDoesNotSubtractItsPassThroughParentsScrap() {
    var rows = build(List.of(make("P",null),make("201201063","P"),buy("1001000300230","P/201201063")),
        List.of(scrap("201201063","301990236",".0076")),Set.of("P/201201063/1001000300230"));
    assertThat(rows).extracting(BomCostingRow::getMaterialCode).containsExactly("1001000300230");
    assertThat(rows.getFirst().getQtyPerTop()).isEqualByComparingTo("1");
  }

  @Test void ordinaryPurchasedRawMaterialStillUsesOriginalManufacturingByproductRule() {
    var rows = build(List.of(make("P",null),make("MAKE","P"),buy("RAW","P/MAKE")),
        List.of(scrap("MAKE","SCRAP",".0076")),Set.of());
    assertThat(rows).extracting(BomCostingRow::getMaterialCode).containsExactly("SCRAP","RAW");
  }

  @Test void separateManufacturingAncestorAndOtherBranchKeepTheirOwnSameCodeScrap() {
    var rows = build(List.of(make("P",null),make("UPPER","P"),make("MAKE","P/UPPER"),
        buy("PART","P/UPPER/MAKE"),make("OTHER","P"),buy("RAW","P/OTHER")),
        List.of(scrap("UPPER","SAME",".0094"),scrap("MAKE","SAME",".0076"),scrap("OTHER","SAME",".0008")),
        Set.of("P/UPPER/MAKE/PART"));
    assertThat(rows).filteredOn(r -> "BYPRODUCT_EXTRA".equals(r.getSettlementRowType()))
        .extracting(BomCostingRow::getParentCode).containsExactlyInAnyOrder("UPPER","OTHER");
  }

  @Test void virtualWrapperDoesNotInventAnotherProcessingLayer() {
    var rows = build(List.of(make("P",null),make("MAKE","P"),node("VIRTUAL","P/MAKE","虚拟件",false),buy("PART","P/MAKE/VIRTUAL")),
        List.of(scrap("MAKE","SCRAP",".0076")),Set.of("P/MAKE/VIRTUAL/PART"));
    assertThat(rows).extracting(BomCostingRow::getMaterialCode).containsExactly("PART");
  }

  @Test void additionalActualInputPreventsTreatingManufacturingParentAsSinglePurchasedPart() {
    var rows = build(List.of(make("P",null),make("MAKE","P"),buy("PART","P/MAKE"),buy("RAW","P/MAKE")),
        List.of(scrap("MAKE","SCRAP",".0076")),Set.of("P/MAKE/PART"));
    assertThat(rows).extracting(BomCostingRow::getMaterialCode).contains("SCRAP","PART","RAW");
  }

  @Test void selectedOutsourceRawPricePathDoesNotCancelTheManufacturingAncestorsRecovery() {
    var rows = build(List.of(make("P",null),make("MAKE","P"),node("OUT","P/MAKE","委外加工件",false),buy("RAW","P/MAKE/OUT")),
        List.of(scrap("MAKE","SCRAP",".0076")),Set.of("P/MAKE/OUT","P/MAKE/OUT/RAW"));
    assertThat(rows).extracting(BomCostingRow::getMaterialCode).contains("SCRAP","OUT","RAW");
    assertThat(rows).filteredOn(r -> "OUTSOURCED_PROCESS_FEE".equals(r.getSettlementRowType())).hasSize(1);
  }

  @Test void sameMaterialCodeInAnotherOccurrenceDoesNotInheritTheSelectedPricePath() {
    var rows = build(List.of(make("P",null),make("MAKE","P/A"),buy("PART","P/A/MAKE"),
        make("MAKE","P/B"),buy("PART","P/B/MAKE"),make("A","P"),make("B","P")),
        List.of(scrap("MAKE","SCRAP",".0076")),Set.of("P/A/MAKE/PART"));
    assertThat(rows).filteredOn(r -> "BYPRODUCT_EXTRA".equals(r.getSettlementRowType()))
        .singleElement().satisfies(r -> assertThat(r.getPath()).startsWith("/P/B/MAKE/"));
  }

  private List<BomCostingRow> build(List<BomSettlementNode> nodes,List<BomSettlementByproduct> scraps,Set<String> chosen) {
    var rule=new BomByproductCostRule();rule.setId(90L);rule.setRuleCode("BYPRODUCT_EXTRA");rule.setRuleCategory("BYPRODUCT_EXTRA");
    rule.setAddConditionType("NO_SCRAP_REF_MATCH");rule.setSettlementRowType("BYPRODUCT_EXTRA");rule.setEnabled(1);rule.setPriority(90);
    rule.setMatchConditionJson("{\"byproductConditions\":[{\"field\":\"shape_attr\",\"op\":\"EQ\",\"value\":\"制造件\"}]}");
    var request=new BomSettlementBuildRequest("TEST","P",LocalDate.of(2026,11,1),"2026-11","TEST",LocalDateTime.of(2026,11,1,0,0),
        "COMMERCIAL","主制造",nodes,List.of(),scraps,List.of(),List.of(rule));
    return engine.build(request,Set.of(),chosen).costingRows();
  }
  private BomSettlementNode make(String code,String parent) {return node(code,parent,"制造件",false);}
  private BomSettlementNode buy(String code,String parent) {return node(code,parent,"采购件",true);}
  private BomSettlementNode node(String code,String parent,String shape,boolean leaf) {
    return new BomSettlementNode(null,"P",parent==null?null:parent.substring(parent.lastIndexOf('/')+1),code,parent==null?0:parent.split("/").length,
        "/"+(parent==null?"":parent+"/")+code+"/",BigDecimal.ONE,BigDecimal.ONE,code,"SPEC",shape,shape,null,"15","普通物料",null,"主制造","V1",1,leaf?1:0,
        LocalDate.of(2026,1,1),null,null,"210","COMMERCIAL","COMMERCIAL",null);
  }
  private BomSettlementByproduct scrap(String parent,String code,String qty) {
    return new BomSettlementByproduct(1L,parent,code,"废料",null,new BigDecimal(qty),"KG","主制造","V1",LocalDate.of(2026,1,1),null,"COMMERCIAL");
  }
}
