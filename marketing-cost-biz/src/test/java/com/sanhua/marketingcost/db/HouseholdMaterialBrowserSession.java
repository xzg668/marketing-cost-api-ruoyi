package com.sanhua.marketingcost.db;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.service.pricing.household.*;
import java.math.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","app.cors.allowed-origins=http://127.0.0.1:15196",
    "integration.oa-person-directory.enabled=false","integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false","srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false"})
class HouseholdMaterialBrowserSession extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper rawHierarchy;
  @Autowired HouseholdInquiryService inquiries;
  static final Path ARCHIVE=archive();
  static Path archive(){try{return Files.createTempDirectory("household-browser-source-");}catch(Exception e){throw new IllegalStateException(e);}}
  @org.springframework.test.context.DynamicPropertySource
  static void period(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
    registry.add("price.household.workbooks-root",ARCHIVE::toString);
    registry.add("app.cors.allowed-origins",()->System.getProperty("final.browser.frontend-origin","http://127.0.0.1:15196"));
  }
  @Test void browserSession() throws Exception {
    Path state=Path.of(System.getProperty("household.browser.state"));assertThat(Files.exists(state)).isFalse();
    // The real frontend uses database routes, including the household inquiry menu.
    try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ClassPathResource("db/V343__household_inquiry_menu.sql"));
    }
    String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);finance(month,"210");
    var sources=new OrdinaryCostingTestSources(jdbc,rawHierarchy);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    var samples=new ArrayList<Map<String,Object>>();
    record Case(String name,String copper,String zinc,boolean board){}
    for(var sample:List.of(new Case("BOTH","120000","28000",false),new Case("NONE",null,null,false),
        new Case("CU_ONLY","120000",null,false),new Case("NEGATIVE","80000",null,false),
        new Case("BOARD","120000","28000",true),new Case("CARRY","120000","28000",false))) {
      String product="HH-"+UUID.randomUUID().toString().substring(0,8),material=product+"-PART";
      jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,zinc_price) VALUES(?,'COMMERCIAL',?,?)",product,decimal(sample.copper()),decimal(sample.zinc()));
      long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,product);
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')",form,product,"家用差异验收 "+sample.name());
      long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
      sources.ordinarySources(product,material,form,date);
      jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",product);
      jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
      if(sample.board()) {jdbc.update("UPDATE oa_form SET process_code='FI-SC-020' WHERE id=?",form);jdbc.update("UPDATE lp_material_master_raw SET organization_code='PLATE' WHERE import_batch_id=?",product);jdbc.update("UPDATE lp_bom_raw_hierarchy SET price_org_code='220' WHERE top_product_code=?",product);}
      String sourceMonth=sample.name().equals("CARRY")?YearMonth.parse(month).minusMonths(1).toString():month;
      long inquiry=inquiry(material,sourceMonth,"18.9343");
      BigDecimal cu=sample.copper()==null?new BigDecimal("90"):decimal(sample.copper()).movePointLeft(3);
      BigDecimal zn=sample.zinc()==null?new BigDecimal("22"):decimal(sample.zinc()).movePointLeft(3);
      BigDecimal w=new BigDecimal("18.9343").add(cu.subtract(new BigDecimal("90")).multiply(new BigDecimal(".0574")).add(zn.subtract(new BigDecimal("22")).multiply(new BigDecimal(".0008"))).divide(new BigDecimal("1.13"),18,RoundingMode.HALF_UP));
      BigDecimal difference=amount(w).subtract(amount(new BigDecimal("18.9343")));
      var value=new LinkedHashMap<String,Object>();value.put("scenario",sample.name());value.put("oaNo",product);value.put("product",product);value.put("material",material);value.put("itemId",item);value.put("base","18.9343");value.put("quote",w.toPlainString());value.put("difference",difference);value.put("inquiryId",inquiry);value.put("sourceMonth",sourceMonth);samples.add(value);
    }
    String queryMaterial="HH-MONTH-"+UUID.randomUUID().toString().substring(0,8);String nextMonth=YearMonth.parse(month).plusMonths(1).toString();
    inquiry(queryMaterial,month,"18.9343");inquiry(queryMaterial,nextMonth,"21");inquiry(queryMaterial,month,"17");
    var before=jdbc.queryForList("SELECT * FROM lp_household_inquiry_price ORDER BY id");
    String actor="household_"+UUID.randomUUID().toString().substring(0,8),password="HouseholdBrowser-test-2026";
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'家用验收',?,'COMMERCIAL','0','0','HH_TEST',NOW(),'HH_TEST',NOW())",actor,new BCryptPasswordEncoder().encode(password));
    long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,actor);jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",user);
    var json=new ObjectMapper().findAndRegisterModules();Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(Map.of("apiPort",port,"username",actor,"password",password,"month",month,"nextMonth",nextMonth,"queryMaterial",queryMaterial,"samples",samples)));
    Path release=Path.of(state+".release");long deadline=System.nanoTime()+Duration.ofMinutes(35).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline)Thread.sleep(250);
    assertThat(Files.exists(release)).isTrue();assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    var checked=new ArrayList<Map<String,Object>>();
    for(var sample:samples) {
      var version=jdbc.queryForMap("SELECT id,cu_material_adjustment,total_cost,final_quote_amount,jianji_snapshot_json FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status='SUCCESS'",sample.get("itemId"));
      assertThat((BigDecimal)version.get("cu_material_adjustment")).isEqualByComparingTo((BigDecimal)sample.get("difference"));
      assertThat((BigDecimal)version.get("final_quote_amount")).isEqualByComparingTo(((BigDecimal)version.get("total_cost")).add((BigDecimal)sample.get("difference")));
      assertThat(version.get("jianji_snapshot_json").toString()).contains("householdCalculation","calculatedW","originalW");
      checked.add(Map.of("scenario",sample.get("scenario"),"versionId",version.get("id"),"difference",version.get("cu_material_adjustment")));
    }
    assertThat(jdbc.queryForList("SELECT * FROM lp_household_inquiry_price ORDER BY id")).isEqualTo(before);
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of("passed",true,"originalInquiriesUnchanged",true,"cases",checked)));
  }
  static BigDecimal amount(BigDecimal p){return p.multiply(new BigDecimal("1.285")).multiply(new BigDecimal("2.5")).divide(new BigDecimal("1.085"),6,RoundingMode.HALF_UP);}
  long inquiry(String material,String month,String value)throws Exception{
    try(var stream=getClass().getResourceAsStream("/household-inquiry/real-20261107.xlsx");var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(stream);var bytes=new java.io.ByteArrayOutputStream()){
      var sheet=book.getSheetAt(0);for(int i=sheet.getLastRowNum();i>2;i--)if(sheet.getRow(i)!=null)sheet.removeRow(sheet.getRow(i));
      sheet.getRow(2).getCell(0).setCellValue(material);sheet.getRow(2).getCell(4).setCellValue(value);book.write(bytes);
      var preview=inquiries.preview("COMMERCIAL","HH-TEST",bytes.toByteArray(),"controlled-one-row.xlsx",month);assertThat(preview.content().workbook().errors()).isEmpty();
      return inquiries.publish("COMMERCIAL","HH-TEST",new HouseholdInquiryModels.PublishRequest(preview.previewId(),preview.publishRequestId(),preview.previewFingerprint())).rows().getFirst().versionId();
    }
  }
  private static BigDecimal decimal(String value){return value==null?null:new BigDecimal(value);}
  private static final Map<String,String> FACTORS=Map.of("cu","AVG|1#CU","zn","AVG|1#ZN","ag","AVG|1#AG","sn","AVG|1#SN","s304","AVG|304/2B/1219","s316","AVG|316L/2B");
  long finance(String month,String org) {
    String batch="MAT-"+UUID.randomUUID();
    jdbc.update("INSERT INTO lp_factor_upload_batch(batch_no,import_type,price_month,business_unit_type,file_name,file_sha256,content_hash,uploaded_by,status,price_org_code,publication_revision,default_scheme_code,published_at) VALUES(?,'FINANCE_BASE',?,'COMMERCIAL','material-test.xlsx',?,?,'material-test','PUBLISHED',?,999,'DEFAULT',NOW())",batch,month,"a".repeat(64),"b".repeat(64),org);
    long id=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE batch_no=?",Long.class,batch);
    var values=Map.of("cu","90","zn","22","ag","14000","sn","390","s304","20","s316","32.5");
    int index=0;
    for(String name:org.equals("220")?List.of("cu","zn","ag","sn","s304","s316"):List.of("cu","zn")) {
      String factor=FACTORS.get(name),seq=UUID.randomUUID().toString();int row=++index+2;
      jdbc.update("INSERT INTO lp_factor_identity(business_unit_type,factor_seq_no,factor_name,short_name,price_source,canonical_factor_key) VALUES('COMMERCIAL',?,?,?,'AVG',?)",seq,factor,factor,factor);
      long identity=jdbc.queryForObject("SELECT id FROM lp_factor_identity WHERE factor_seq_no=?",Long.class,seq);
      for(String condition:org.equals("220")?List.of("NON_TENDER","TENDER"):List.of("GENERAL")) {
        jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,tax_included,source_upload_batch_id,status,price_org_code,scheme_code,condition_code,revision_no,unit,published_at) VALUES(?,?,?,1,?,'PUBLISHED',?,'DEFAULT',?,999,'CNY/KG',NOW())",identity,month,decimal(values.get(name)),id,org,condition);
        long price=jdbc.queryForObject("SELECT id FROM lp_factor_monthly_price WHERE factor_identity_id=? AND condition_code=?",Long.class,identity,condition);
        jdbc.update("INSERT INTO lp_factor_row_ref(factor_upload_batch_id,source_sheet_name,source_row_number,factor_identity_id,factor_monthly_price_id,source_column_number,source_cell_ref,scheme_code,condition_code,source_value_text,source_unit,source_number_format,conversion_basis) VALUES(?,'财务基价',?,?,?,4,?,'DEFAULT',?,?,'CNY/KG','0.000000','IDENTITY')",id,row,identity,price,"D"+row,condition,values.get(name));
      }
    }return id;
  }
}
