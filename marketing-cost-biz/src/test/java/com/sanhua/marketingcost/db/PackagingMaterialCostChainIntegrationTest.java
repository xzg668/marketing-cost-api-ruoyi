package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotDetailMapper;
import com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService;
import com.sanhua.marketingcost.service.jjb.JjbReadService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** 实际价格准备、核算、保存、复用、见机表及 Excel 共用一次隔离核算。 */
@Tag("integration")
class PackagingMaterialCostChainIntegrationTest extends MonthlyRepriceCostingFixture {
  @Autowired PackageComponentSnapshotMapper packages;
  @Autowired PackageComponentSnapshotDetailMapper packageDetails;
  @Autowired JjbReadService sheets;
  @Autowired QuoteCostRunWorkbenchService exports;

  @Test void independentLabelAndPackageAreCountedOnceThroughSavedCostSheetAndExport() throws Exception {
    unit="COMMERCIAL"; priceOrg="210"; materialOrg="COMMERCIAL";
    String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    repriceProperties.setGoLiveMonths(Map.of(unit,month));
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    String product="PACK-CHAIN-"+UUID.randomUUID().toString().substring(0,8), label=product+"-LABEL";
    String parent=product+"-ZERO-PACK", child=product+"-ZERO-CHILD", normal=product+"-NORMAL";
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",product);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,product);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')",form,product,product);
    long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    ordinarySources(product,label,form,date);
    comparisonFixedPrice(label,"0.0020616",date); // 2.5 × 单价 = 0.005154 独立标签。
    jdbc.update("UPDATE lp_material_master_raw SET cost_element='主要材料-包装材料' WHERE material_code=?",label);
    comparisonAdditionalPart(product,label,normal,"1",date);
    comparisonFixedPrice(normal,"17.331695",date);
    comparisonFixedPrice(child,"0.3089",date);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET qty_per_parent=1,qty_per_top=1 WHERE top_product_code=? AND material_code IN (?,?)",product,parent,child);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET material_category_1='15155',material_category_2='包装组件' WHERE top_product_code=? AND material_code=?",product,parent);
    var snapshot=new PackageComponentSnapshot();
    snapshot.setPackageMaterialCode(parent); snapshot.setPackageMaterialName("包装组件");
    snapshot.setPeriodMonth(month); snapshot.setPriceOrgCode(priceOrg); snapshot.setBusinessUnitType(unit);
    snapshot.setSourceTopProductCode(product); snapshot.setSourceBomPurpose("主制造"); snapshot.setSourceBomSourceType("U9");
    snapshot.setStatus("NORMAL"); snapshot.setPackageParentBaseQty(BigDecimal.ONE); packages.insert(snapshot);
    var detail=new PackageComponentSnapshotDetail(); detail.setSnapshotId(snapshot.getId());
    detail.setPackageMaterialCode(parent); detail.setPeriodMonth(month); detail.setLineNo(1);
    detail.setChildMaterialCode(child); detail.setChildMaterialName("包装子件"); detail.setChildShapeAttr("采购件");
    detail.setQtyPerParent(BigDecimal.ONE); detail.setChildParentBaseQty(BigDecimal.ONE); detail.setBusinessUnitType(unit);
    packageDetails.insert(detail);
    var auth=new UsernamePasswordAuthenticationToken("pack-chain",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    auth.setDetails(Map.of("businessUnitType",unit,"userId",1L)); SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      buildOriginalFixture(form,item,product,month,date,label,Map.of());
      var request=new ProductCostingRequest(product,item,month,"pack-chain",false);
      var result=pipeline.execute(request);
      assertThat(result.getPipelineStatus()).as(result.getMessage()).isEqualTo("SUCCESS");
      long version=result.getCostVersionId();
      var view=sheets.read(product,item,version);
      var costs=new LinkedHashMap<String,com.sanhua.marketingcost.dto.CostRunCostItemDto>();
      view.costs().forEach(row->costs.put(row.getCostCode(),row));
      assertThat(view.parts()).extracting(row->row.getPartCode()).contains(parent,label,normal).doesNotContain(child);
      assertThat(view.parts().stream().filter(row->label.equals(row.getPartCode())).findFirst().orElseThrow().getAmount())
          .isEqualByComparingTo("0.005154");
      var material=new BigDecimal("17.661194"); // 17.331695 + 标签 0.005154 + 包装 0.3089 × 1.05。
      for(var row:view.costs()) if(row.getCostCode().startsWith("AUX_")
          || Set.of("OVERHAUL","TOOLING_REPAIR","WATER_POWER","DEPT_OTHER").contains(row.getCostCode()))
        if(row.getAmount()!=null) material=material.add(row.getAmount());
      assertThat(costs.get("MATERIAL").getAmount()).isEqualByComparingTo(material);
      assertThat(costs.get("OTHER_EXP_PACKAGE").getAmount()).isEqualByComparingTo("0.324345");
      var labor=costs.get("DIRECT_LABOR").getAmount().add(costs.get("INDIRECT_LABOR").getAmount());
      var loss=money(material.add(labor).multiply(costs.get("LOSS").getRate()));
      var manufacture=material.add(labor).add(loss).divide(BigDecimal.ONE.subtract(costs.get("MANUFACTURE").getRate()),6,RoundingMode.HALF_UP);
      var adjusted=money(manufacture.multiply(costs.get("ADJUSTED_MANUFACTURE_COST").getRate()));
      assertThat(costs.get("LOSS").getAmount()).isEqualByComparingTo(loss);
      assertThat(costs.get("MANUFACTURE_COST").getAmount()).isEqualByComparingTo(manufacture);
      assertThat(costs.get("MANUFACTURE").getAmount()).isEqualByComparingTo(money(manufacture.multiply(costs.get("MANUFACTURE").getRate())));
      var total=adjusted;
      for(String code:List.of("MGMT_EXP","SALES_EXP","FIN_EXP")) {
        var amount=money(adjusted.multiply(costs.get(code).getRate()));
        assertThat(costs.get(code).getAmount()).isEqualByComparingTo(amount); total=total.add(amount);
      }
      assertThat(costs.get("TOTAL").getAmount()).isEqualByComparingTo(total);
      assertThat(new BigDecimal(view.sheet().totals().totalCost())).isEqualByComparingTo(total);
      assertThat(new BigDecimal(view.sheet().totals().finalQuoteAmount())).isEqualByComparingTo(total);
      assertThat(jdbc.queryForObject("SELECT total_cost FROM lp_quote_cost_run_version WHERE id=?",BigDecimal.class,version)).isEqualByComparingTo(total);
      assertThat(view.sheet().rows().stream().filter(row->"MATERIAL".equals(row.code())).findFirst().orElseThrow().amount()).isEqualTo(material.toPlainString());
      assertThat(view.sheet().rows().stream().filter(row->Set.of(parent,"OTHER_EXP_PACKAGE","BOM_BUCKET_PACKAGE").contains(Objects.toString(row.code(),"")))
          .filter(row->row.amount()!=null)).hasSize(1);
      var workbook=new java.io.ByteArrayOutputStream(); exports.exportVersion(product,item,version,workbook);
      try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(workbook.toByteArray()))) {
        int packageAmounts=0; boolean foundTotal=false,foundMaterial=false;
        for(var sheet:book) for(var row:sheet) for(var cell:row) if(cell.getCellType()==org.apache.poi.ss.usermodel.CellType.NUMERIC) {
          var value=BigDecimal.valueOf(cell.getNumericCellValue());
          if(value.compareTo(new BigDecimal("0.324345"))==0) packageAmounts++;
          foundTotal|=value.compareTo(total)==0; foundMaterial|=value.compareTo(material)==0;
        }
        assertThat(packageAmounts).isEqualTo(1); assertThat(foundTotal).isTrue(); assertThat(foundMaterial).isTrue();
      }
      var before=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",version);
      var repeat=pipeline.execute(request);
      assertThat(repeat.isReusedSuccess()).isTrue(); assertThat(repeat.getCostVersionId()).isEqualTo(version);
      assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",version)).isEqualTo(before);
      String evidence=System.getProperty("packaging.test.evidence");
      if(evidence!=null) {
        var path=java.nio.file.Path.of(evidence);
        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValue(path.toFile(),view.sheet());
        java.nio.file.Files.write(path.resolveSibling("fresh-cost.xlsx"),workbook.toByteArray());
      }
    } finally { SecurityContextHolder.clearContext(); }
  }
  private static BigDecimal money(BigDecimal value) { return value.setScale(6,RoundingMode.HALF_UP); }
}
