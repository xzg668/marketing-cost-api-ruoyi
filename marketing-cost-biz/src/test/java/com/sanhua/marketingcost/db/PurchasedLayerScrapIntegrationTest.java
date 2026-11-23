package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reuses the actual HTTP shape/costing tests; all writes stay in disposable MySQL. */
class PurchasedLayerScrapIntegrationTest extends MaterialShapeSwitchIntegrationTest {
  @Test void purchaseDropsOnlyThePassThroughLayersScrapAndRestoresItForRealProcessing() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    String layer=addLayer(jdbc,hierarchy,s),recovery=layer+"-SCRAP";
    assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    long original=latest(s);assertThat(part(original,recovery)).isEqualByComparingTo("-0.152");
    classify(s);choose(s,"PURCHASE");
    assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    long purchased=latest(s);assertThat(part(purchased,s.parent())).isEqualByComparingTo("50");
    assertThat(part(purchased,recovery)).isNull();
    assertThat(part(purchased,s.scrap())).isEqualByComparingTo("-0.188");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_bom_costing_row WHERE oa_form_item_id=? AND parent_code=? AND settlement_row_type='BYPRODUCT_EXTRA'",Integer.class,s.itemId(),layer)).isZero();
    for(int i=0;i<3;i++) {assertThat(calculate(s).path("reusedSuccess").asBoolean()).isTrue();assertThat(latest(s)).isEqualTo(purchased);}
    choose(s,"OUTSOURCE");assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    long outsourced=latest(s);assertThat(part(outsourced,recovery)).isEqualByComparingTo("-0.152");
    assertThat(part(outsourced,s.parent())).isEqualByComparingTo("7.5");
    assertThat(part(outsourced,s.raw())).isEqualByComparingTo("25");
    assertThat(part(outsourced,s.scrap())).isEqualByComparingTo("-2.688");
    var stored = call(org.springframework.http.HttpMethod.GET, "/api/v1/cost-run/detail?oaNo=" + s.oaNo()
        + "&productCode=" + s.product() + "&oaFormItemId=" + s.itemId() + "&versionId=" + outsourced, null);
    var recovered = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    for (var row : stored.path("sheet").path("rows")) {
      if (s.scrap().equals(row.path("code").asText())) recovered.add(row);
    }
    assertThat(recovered).hasSize(2);
    assertThat(recovered.stream().map(row -> new BigDecimal(row.path("quantity").asText())))
        .containsExactlyInAnyOrder(new BigDecimal("-0.0094"), new BigDecimal("-0.125"));
    assertThat(recovered.stream().map(row -> new BigDecimal(row.path("amount").asText()))
        .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("-2.688");
    choose(s,"PURCHASE");assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    assertThat(part(latest(s),recovery)).isNull();assertThat(part(latest(s),s.scrap())).isEqualByComparingTo("-0.188");
    assertThat(part(original,recovery)).isEqualByComparingTo("-0.152");
    assertThat(part(outsourced,recovery)).isEqualByComparingTo("-0.152");
  }

  static String addLayer(JdbcTemplate j,BomRawHierarchyMapper hierarchy,MaterialShapeSwitchTestData.Sample s) {
    // Production has enabled BSR-06; the base schema intentionally seeds it disabled.
    j.update("UPDATE lp_bom_byproduct_cost_rule SET enabled=1 WHERE rule_code='BYPRODUCT_EXTRA_WHEN_NO_SCRAP_REF'");
    String wrapper=s.product()+"-LAYER",scrap=wrapper+"-SCRAP";
    var a=hierarchy.selectOne(Wrappers.<BomRawHierarchy>lambdaQuery().eq(BomRawHierarchy::getTopProductCode,s.product()).eq(BomRawHierarchy::getMaterialCode,s.parent()));
    var w=new BomRawHierarchy();org.springframework.beans.BeanUtils.copyProperties(a,w);
    j.update("UPDATE lp_bom_raw_hierarchy SET path=CONCAT(?,SUBSTRING(path,?)),level=level+1 WHERE top_product_code=? AND path LIKE ?",
        "/"+s.product()+"/"+wrapper+"/",s.product().length()+3,s.product(),"/"+s.product()+"/"+s.parent()+"/%");
    j.update("UPDATE lp_bom_raw_hierarchy SET parent_code=? WHERE top_product_code=? AND material_code=?",wrapper,s.product(),s.parent());
    w.setId(null);w.setMaterialCode(wrapper);w.setMaterialName("上层阀座");w.setMaterialSpec("WRAPPER-SPEC");w.setParentCode(s.product());
    w.setPath("/"+s.product()+"/"+wrapper+"/");w.setLevel(1);w.setQtyPerParent(BigDecimal.ONE);w.setQtyPerTop(BigDecimal.ONE);
    w.setSourceLineKey("PURCHASE-LAYER");w.setIsLeaf(0);hierarchy.insert(w);
    j.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr) VALUES(?,'上层阀座','件','COMMERCIAL',?,1,'12','制造件')",wrapper,s.product());
    j.update("UPDATE lp_bom_u9_source SET parent_material_no=? WHERE import_batch_id=? AND parent_material_no=? AND child_material_no=?",wrapper,s.product(),s.product(),s.parent());
    j.update("""
        INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,parent_material_no,child_material_no,
          child_material_name,stock_unit,bom_purpose,bom_version,child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type)
        SELECT import_batch_id,source_type,imported_at,price_org_code,?,?,'上层阀座','件',bom_purpose,bom_version,1,1,1,effective_from,effective_to,child_type
        FROM lp_bom_u9_source WHERE import_batch_id=? AND parent_material_no=? AND child_material_no=?
        """,s.product(),wrapper,s.product(),wrapper,s.parent());
    j.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,created_by,created_at,updated_by,updated_at) VALUES('COMMERCIAL',?,?,'上层加工废料','FIXED',20,'{}','IMPORT',?,?,'TEST',NOW(),'TEST',NOW())",s.month(),scrap,s.month(),"e".repeat(64));
    for(var row:List.of(new String[]{wrapper,scrap,".0076"},new String[]{s.product(),s.scrap(),".0094"}))
      j.update("INSERT INTO lp_u9_bom_byproduct_master(price_org_code,parent_material_no,bom_purpose,version_no,output_type,byproduct_material_no,output_qty,unit,status,effective_from,effective_to) VALUES('210',?,'主制造','MPP12','副产品',?,?,'kg','已核准',?,'2099-01-01')",row[0],row[1],new BigDecimal(row[2]),java.time.YearMonth.parse(s.month()).atDay(1));
    return wrapper;
  }
}
