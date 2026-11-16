package com.sanhua.marketingcost.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.service.rule.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProcessingFeeShapeBoundaryTest {
  private final BomSettlementRowBuildEngine engine = new BomSettlementRowBuildEngine(
      new BomSettlementRuleMatcher(new BomSettlementRuleConditionEvaluator(new ObjectMapper())),
      new BomByproductCostRuleMatcher(new BomByproductCostRuleConditionEvaluator(new ObjectMapper())));

  @Test void manufacturingBaseKeepsMaterialsWithoutInventingAnOutsourceFee() {
    var rows = build(List.of(node("P",null,"成品","制造件","1"),
        node("BASE","P","烧结基座","制造件","1"),
        node("BLANK","P/BASE","烧结基座毛坯","制造件","1"),
        node("RAW","P/BASE/BLANK","原材料","采购件","2")));
    assertThat(rows).extracting(BomCostingRow::getMaterialCode).containsExactly("RAW");
    assertThat(rows).noneMatch(r -> "OUTSOURCED_PROCESS_FEE".equals(r.getSettlementRowType()));
  }

  @Test void purchasedBaseKeepsItsWholePriceIdentityInsteadOfBecomingAProcessingFee() {
    var rows = build(List.of(node("P",null,"成品","制造件","1"),
        node("BASE","P","烧结基座","采购件","2.5")));
    assertThat(rows).singleElement().satisfies(r -> {
      assertThat(r.getMaterialName()).isEqualTo("烧结基座");
      assertThat(r.getShapeAttr()).isEqualTo("采购件");
      assertThat(r.getSettlementRowType()).isEqualTo("DEFAULT_LEAF");
      assertThat(r.getQtyPerTop()).isEqualByComparingTo("2.5");
    });
  }

  @Test void actualOutsourceStillChargesOneFeeAndKeepsOwnMaterialQuantity() {
    var rows = build(List.of(node("P",null,"成品","制造件","1"),
        node("BASE","P","烧结基座","委外加工件","2.5"),
        node("RAW","P/BASE","毛坯","采购件","5")));
    assertThat(rows).filteredOn(r -> "OUTSOURCED_PROCESS_FEE".equals(r.getSettlementRowType()))
        .singleElement().satisfies(r -> {
          assertThat(r.getMaterialCode()).isEqualTo("BASE");
          assertThat(r.getQtyPerTop()).isEqualByComparingTo("2.5");
        });
    assertThat(rows).filteredOn(r -> "RAW".equals(r.getMaterialCode())).singleElement()
        .satisfies(r -> assertThat(r.getQtyPerTop()).isEqualByComparingTo("5"));
  }

  @Test void sameCodeInDifferentOccurrencesUsesEachOccurrenceShape() {
    var rows = build(List.of(node("P",null,"成品","制造件","1"),
        node("A","P","组件A","制造件","1"),node("B","P","组件B","制造件","1"),
        node("BASE","P/A","烧结基座","制造件","3"),
        node("BASE","P/B","烧结基座","委外加工件","2"),
        node("RAW-A","P/A/BASE","材料A","采购件","3"),
        node("RAW-B","P/B/BASE","材料B","采购件","2")));
    assertThat(rows).filteredOn(r -> "OUTSOURCED_PROCESS_FEE".equals(r.getSettlementRowType()))
        .singleElement().satisfies(r -> {
          assertThat(r.getPath()).isEqualTo("/P/B/BASE/");
          assertThat(r.getQtyPerTop()).isEqualByComparingTo("2");
        });
  }

  @Test void manufacturingLeafReportsExistingMissingStructureInsteadOfPricingAsOutsource() {
    var request = request(List.of(node("P",null,"成品","制造件","1"),
        node("BASE","P","烧结基座","制造件","1")));
    var result = engine.build(request);
    assertThat(result.costingRows()).isEmpty();
  }

  private List<BomCostingRow> build(List<BomSettlementNode> nodes) {return engine.build(request(nodes)).costingRows();}
  private BomSettlementBuildRequest request(List<BomSettlementNode> nodes) {
    var rule = new BomSettlementRule();rule.setId(15L);rule.setRuleCode("SINTERED_BASE_PROCESS_FEE");
    rule.setRuleName("烧结基座委外加工费");rule.setRuleCategory("OUTSOURCED_PROCESS_FEE");
    rule.setSettlementAction("ADD_PROCESS_FEE");rule.setSettlementRowType("OUTSOURCED_PROCESS_FEE");
    rule.setEnabled(1);rule.setPriority(10);
    rule.setMatchConditionJson("{\"nodeConditions\":[{\"op\":\"LIKE\",\"field\":\"material_name\",\"value\":\"烧结基座\"},{\"op\":\"NOT_LIKE\",\"field\":\"material_name\",\"value\":\"毛坯\"}]}");
    return new BomSettlementBuildRequest("TEST","P",LocalDate.of(2026,11,1),"2026-11","TEST",LocalDateTime.of(2026,11,1,0,0),"COMMERCIAL","主制造",nodes,List.of(rule));
  }
  private BomSettlementNode node(String code,String parent,String name,String shape,String quantity) {
    String path="/"+(parent==null?"":parent+"/")+code+"/";
    return new BomSettlementNode(null,"P",parent==null?null:parent.substring(parent.lastIndexOf('/')+1),code,
        parent==null?0:parent.split("/").length,path,new BigDecimal(quantity),new BigDecimal(quantity),name,null,shape,null,null,"15","普通物料",null,"主制造","V1",1,"采购件".equals(shape)?1:0,LocalDate.of(2026,1,1),null,null,"210","COMMERCIAL","COMMERCIAL",null);
  }
}
