package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.service.pricing.inquiry.InquiryPriceService;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@SpringBootTest(properties={"srm.fixed-price.enabled=false","integration.oa-person-directory.enabled=false",
    "integration.oa-person-directory.run-at-startup=false","cms.sync-publish.enabled=false","cost.run.worker.enabled=false"})
class BomSpecificationInquiryIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired InquiryPriceService inquiries;
  @Autowired BomSpecificationPriceIdentity identity;
  @Autowired JdbcTemplate jdbc;

  @Test void firstUseFindsModelOnlyInquiryWithoutAnAdoptedOrCodedPrice() throws Exception {
    String model="MODEL-"+UUID.randomUUID();
    inquiries.publish("COMMERCIAL","2026-11","fixture",UUID.randomUUID().toString(),"型号询价.xlsx",quote(model));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE specification=?",Integer.class,model)).isZero();
    for(String org:List.of("210","220")) {
      var found=identity.resolve(context(),org,model);
      assertThat(found.materialCode()).isEmpty();
      assertThat(found.unit()).isEqualTo("件");
    }
    assertThatThrownBy(()->identity.resolve(context(),"210",model+"-missing")).isInstanceOf(MonthlyPriceProblem.class);
  }

  @Test void earlierCodedSourceStillIdentifiesTheMaterialAndConflictsRemainExplicit() throws Exception {
    String model="MODEL-"+UUID.randomUUID(),code="MAT-"+UUID.randomUUID();
    inquiries.publish("COMMERCIAL","2026-11","fixture",UUID.randomUUID().toString(),"型号询价.xlsx",quote(model));
    for(String suffix:List.of("A","B")) {
      jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,spec_model,unit,fixed_price,tax_included,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) VALUES('210',?,?,'件',5,0,'COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())",code+suffix,model);
      if(suffix.equals("A"))assertThat(identity.resolve(context(),"210",model).materialCode()).isEqualTo(code+suffix);
    }
    assertThatThrownBy(()->identity.resolve(context(),"210",model)).isInstanceOf(MonthlyPriceProblem.class).hasMessageContaining("多个价格料号");
  }

  static MonthlyMaterialPrices.Context context() {
    return new MonthlyMaterialPrices.Context("COMMERCIAL","TEST",1L,"TEST",YearMonth.of(2026,11),LocalDateTime.of(2026,11,24,0,0),"fixture","210");
  }
  static byte[] quote(String model) throws Exception {
    try(var book=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
      var sheet=book.createSheet("型号询价");
      var labels=List.of("料号","规格型号","供方名称","价格类型","批量价总价（元）","不含税价格","计量单位");
      var values=List.of("",model,"","固定价","3.5","3.5","件");
      var header=sheet.createRow(0);var line=sheet.createRow(1);
      for(int i=0;i<labels.size();i++){header.createCell(i).setCellValue(labels.get(i));line.createCell(i).setCellValue(values.get(i));}
      book.write(bytes);return bytes.toByteArray();
    }
  }
}
