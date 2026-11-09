package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceSaveRequest;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataPriceApplicationService;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 实际核算检查与技术填写共用数据库；只在独立测试容器内执行。 */
@Tag("integration")
class MixedTechnicalPriceGapIntegrationTest extends PendingSupplementPublicImportIntegrationTest {
  @Autowired TechnicalDataPriceApplicationService technicalPrices;

  @Override protected void additionalSources() {
    String ratio=oaNo+"-RATIO", structure=oaNo+"-MAKE";
    addSibling(ratio,"采购件",5);
    addSibling(structure,"制造件",6);
    String raw=oaNo+"-RAW";
    addSibling(raw,"采购件",7);
    jdbc.update("UPDATE lp_material_master_raw SET unit='千克' WHERE material_code=?",raw);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET parent_code=?,level=2,path=? WHERE top_product_code=? AND material_code=?",
        structure,"/"+oaNo+"/"+structure+"/"+raw+"/",oaNo,raw);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET is_leaf=0 WHERE top_product_code=? AND material_code=?",oaNo,structure);
    jdbc.update("""
        INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,
          supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at)
        VALUES('210',?,?,'千克',10,0,'MIXED-RAW-S1',?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())
        """,raw,raw,YearMonth.parse(month).atDay(1));
    for(int index=1;index<=2;index++) jdbc.update("""
        INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,
          supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at)
        VALUES('210',?,?,'件',?,0,?,?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())
        """,ratio,ratio,BigDecimal.valueOf(index),oaNo+"-S"+index,YearMonth.parse(month).atDay(1));
  }

  @Test void mixedSourcesKeepOnlyTrueMissingPriceEditableThroughTheActualEntry() {
    String ratio=oaNo+"-RATIO", structure=oaNo+"-MAKE";
    importRate();
    var checked=pipeline.prepare(new ProductCostingRequest(oaNo,itemId,month,"mixed-gap-test",true));
    assertThat(checked.getTechnicalDataCheck()).as(checked.getMessage()).isNotNull();
    assertThat(checked.getTechnicalDataCheck().modules()).filteredOn(m->m.required())
        .extracting(m->m.moduleType()).containsExactly("PRICE");
    assertThat(checked.getMessage()).contains(ratio,material);
    var before=protectedRows();
    assertThat(tasks.workbench(1,20,null,month,null,oaNo,ADMIN).records()).hasSize(1);
    assertThat(protectedRows()).isEqualTo(before);
    var task=tasks.startAdminEntry(itemId,month,ADMIN);
    var product=task.products().getFirst();
    var input=technicalPrices.get(product.id(),null,ADMIN);
    assertThat(input.requirements().issues()).isEmpty();
    assertThat(input.requirements().items()).extracting(row->row.materialNo()).containsExactly(material);
    var request=new TechnicalDataPriceSaveRequest();request.setExpectedVersion(input.expectedVersion());
    request.setRequirementsFingerprint(input.requirements().fingerprint());
    var item=new TechnicalDataPriceSaveRequest.Item();item.setItemKey(input.requirements().items().getFirst().itemKey());
    item.setEntryMode("FIXED");item.setUnitPrice(new BigDecimal("2.5"));request.setItems(List.of(item));
    var saved=technicalPrices.save(product.id(),request,ADMIN);
    assertThat(saved.content().items()).extracting(row->row.materialNo()).containsExactly(material);
    assertThat(saved.content().items().getFirst().unitPrice()).isEqualByComparingTo("2.5");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class)).isEqualTo(before.get("lp_material_monthly_price"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version",Long.class)).isEqualTo(before.get("lp_quote_cost_run_version"));
  }

  private void addSibling(String code,String shape,int sort) {
    jdbc.update("""
        INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,
          active_flag,main_category_code,shape_attr) VALUES(?,?,'件','COMMERCIAL',?,1,'15',?)
        """,code,code,oaNo,shape);
    var original=hierarchy.selectList(Wrappers.lambdaQuery(BomRawHierarchy.class)
        .eq(BomRawHierarchy::getTopProductCode,oaNo).eq(BomRawHierarchy::getMaterialCode,material)).getFirst();
    original.setId(null);original.setMaterialCode(code);original.setMaterialName(code);original.setShapeAttr(shape);
    original.setPath("/"+oaNo+"/"+code+"/");original.setSortSeq(sort);original.setQtyPerParent(BigDecimal.ONE);
    original.setQtyPerTop(BigDecimal.ONE);original.setChildType("STANDARD");original.setSourceLineKey("MIXED|"+code);hierarchy.insert(original);
  }
}
