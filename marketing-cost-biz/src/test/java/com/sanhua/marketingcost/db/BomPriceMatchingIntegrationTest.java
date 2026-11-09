package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Real quote preparation, monthly adoption, saved cost, repeated calculation and workbook. */
@Tag("integration")
@TestPropertySource(properties={"srm.fixed-price.enabled=false","oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false","cms.sync.publish.enabled=false","quote.drawing-verification.enabled=false"})
class BomPriceMatchingIntegrationTest extends MonthlyRepriceCostingFixture {
  @Autowired com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService exports;

  @ParameterizedTest @ValueSource(booleans={false,true})
  void quoteUsesCodeWhenPresentAndSpecificationOnlyWhenAbsent(boolean withoutCode) throws Exception {
    unit="COMMERCIAL";priceOrg="210";materialOrg="COMMERCIAL";
    String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    repriceProperties.setGoLiveMonths(Map.of(unit,month));
    var date=java.time.YearMonth.parse(month).atDay(1).atTime(12,0);
    String product="MATCH-"+UUID.randomUUID().toString().substring(0,8), material=product+"-RAW";
    String sourceSpec=product+" Φ23×1.8", bomCode=withoutCode?"":material;
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer) VALUES(?,'COMMERCIAL',80000,'MATCH-TEST')",product);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,product);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')",form,product,product);
    long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    ordinarySources(product,material,form,date);
    comparisonFixedPrice(material,"12.345678901234",date);
    jdbc.update("UPDATE lp_price_fixed_item SET spec_model=?,unit='只' WHERE material_code=?",sourceSpec,material);
    var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("matching-test",null,
        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    auth.setDetails(Map.of("businessUnitType",unit,"userId",1L));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      jdbc.update("UPDATE lp_bom_raw_hierarchy SET material_code=?,material_spec=? WHERE build_batch_id=? AND material_code=?",
          bomCode,withoutCode?sourceSpec:"BOM里的另一规格",product,material);
      buildOriginalFixture(form,item,product,month,date,bomCode,Map.of(bomCode,"支"));
      var request=new ProductCostingRequest(product,item,null,"matching-test",true);
      var result=pipeline.execute(request);
      assertThat(result.getPipelineStatus()).as(result.getMessage()).isEqualTo("SUCCESS");
      var parts=jdbc.queryForList("SELECT id,price_prepare_item_id,monthly_price_id,part_code,unit_price,amount FROM lp_cost_run_part_item WHERE cost_run_version_id=?",result.getCostVersionId());
      assertThat(parts).singleElement().satisfies(row->{
        assertThat(Objects.toString(row.get("part_code"),"")).isEqualTo(bomCode);
        assertThat(((Number) row.get("monthly_price_id")).longValue()).isPositive();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
            "UPDATE lp_price_prepare_item SET material_code='WRONG-CODE' WHERE id=?", row.get("price_prepare_item_id")))
            .hasMessageContaining("MPP_PRICE_LINK_SCOPE");
        if (withoutCode) org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
            "UPDATE lp_price_prepare_item SET bom_row_id=-1 WHERE id=?", row.get("price_prepare_item_id")))
            .hasMessageContaining("MPP_PRICE_LINK_SCOPE");
        assertThat((BigDecimal)row.get("unit_price")).isCloseTo(new BigDecimal("12.345678901234"),org.assertj.core.data.Offset.offset(new BigDecimal("0.000001")));
        assertThat((BigDecimal)row.get("amount")).isCloseTo(new BigDecimal("30.864197253085"),org.assertj.core.data.Offset.offset(new BigDecimal("0.000001")));
      });
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=? AND specification=?",Integer.class,material,withoutCode?sourceSpec:"BOM里的另一规格")).isEqualTo(1);
      var saved=jdbc.queryForMap("SELECT id,total_cost,jianji_snapshot_json FROM lp_quote_cost_run_version WHERE id=?",result.getCostVersionId());
      var repeated=pipeline.execute(new ProductCostingRequest(product,item,null,"matching-test",false));
      assertThat(repeated.getPipelineStatus()).as(repeated.getMessage()).isEqualTo("SUCCESS");
      assertThat(repeated.isReusedSuccess()).isTrue();
      assertThat(repeated.getCostVersionId()).isEqualTo(result.getCostVersionId());
      assertThat(jdbc.queryForMap("SELECT id,total_cost,jianji_snapshot_json FROM lp_quote_cost_run_version WHERE id=?",result.getCostVersionId())).isEqualTo(saved);
      var bytes=new java.io.ByteArrayOutputStream();exports.exportVersion(product,item,result.getCostVersionId(),bytes);
      try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
        assertThat(book.getNumberOfSheets()).isGreaterThan(0);
        var text=new StringBuilder();var format=new org.apache.poi.ss.usermodel.DataFormatter();
        for(var sheet:book)for(var row:sheet)for(var cell:row)text.append(format.formatCellValue(cell)).append('\n');
        assertThat(text.toString()).contains(product,"12.345");
      }
    } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
  }
}
