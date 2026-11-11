package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.security.JwtUtils;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Disposable sources only. Chrome creates/edits/deletes rules and performs all saved costs. */
@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","app.cors.allowed-origins=http://127.0.0.1:15246",
    "integration.oa-person-directory.enabled=false","integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false","srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false"})
class ProductPartCostBrowserSession extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtUtils jwt;
  @Autowired BomRawHierarchyMapper rawHierarchy;

  @org.springframework.test.context.DynamicPropertySource
  static void pricingPeriod(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void browserSession() throws Exception {
    Path state=Path.of(System.getProperty("part.cost.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    // The broad schema fixture omits the menu-only V192 repair; reproduce its existing parent.
    jdbc.update("INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,icon,create_by,create_time,update_by,update_time) VALUES(40475,'规则配置',0,50,'rules',1,'0','M','0','0','setting','PART_TEST',NOW(),'PART_TEST',NOW()) ON DUPLICATE KEY UPDATE menu_name='规则配置',parent_id=0,order_num=50,path='rules',component=NULL,menu_type='M',visible='0',status='0',perms=NULL,business_unit_type=NULL");
    try(var connection=jdbc.getDataSource().getConnection()) {
      ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/V374__product_part_cost_rule_menu.sql"));
    }
    String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    String product="PART-"+UUID.randomUUID().toString().substring(0,8), a=product+"-A", b=product+"-B";
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",product);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,product);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')",form,product,"指定零件成本验收产品");
    long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    var sources=new OrdinaryCostingTestSources(jdbc,rawHierarchy);
    sources.ordinarySources(product,a,form,date);sources.comparisonFixedPrice(a,"2",date);sources.comparisonFixedPrice(b,"4",date);
    jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",product);
    jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
    jdbc.update("UPDATE lp_material_master_raw SET material_model='SAME-MODEL' WHERE material_code=?",a);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,material_model,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr) VALUES(?,?,'SAME-MODEL','件','COMMERCIAL',?,1,'15','采购件')",b,b,product);
    var raw=new com.sanhua.marketingcost.entity.BomRawHierarchy();
    raw.setTopProductCode(product);raw.setParentCode(product);raw.setMaterialCode(b);raw.setMaterialName(b);
    raw.setPath("/"+product+"/"+b+"/");raw.setLevel(1);raw.setSortSeq(4);raw.setQtyPerParent(new BigDecimal("3"));raw.setQtyPerTop(new BigDecimal("3"));
    raw.setShapeAttr("采购件");raw.setBomPurpose("主制造");raw.setBomVersion("MPP12");raw.setEffectiveFrom(date.toLocalDate());raw.setEffectiveTo(LocalDate.of(2099,1,1));raw.setBomStatus("已核准");raw.setIsLeaf(1);raw.setU9IsCostFlag(1);raw.setSourceType("U9");raw.setSourceImportBatchId(product);raw.setBuildBatchId(product);raw.setBuiltAt(date);raw.setPriceOrgCode("210");raw.setBusinessUnitType("COMMERCIAL");rawHierarchy.insert(raw);
    jdbc.update("""
        INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,
          parent_material_no,child_material_no,child_material_name,stock_unit,bom_purpose,bom_version,
          child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type)
        SELECT source_import_batch_id,'U9',NOW(),price_org_code,parent_code,material_code,material_name,
          '件',bom_purpose,bom_version,sort_seq,qty_per_parent,1,effective_from,effective_to,'NORMAL'
        FROM lp_bom_raw_hierarchy WHERE top_product_code=? AND level>0
        """,product);
    String makeRaw=b+"-RAW",scrap=b+"-SCRAP";
    jdbc.update("UPDATE lp_material_master_raw SET global_seg_3_theoretical_net_weight='30' WHERE material_code=?",b);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr) VALUES(?,?,'KG','COMMERCIAL',?,1,'15','采购件')",makeRaw,makeRaw,product);
    jdbc.update("INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,parent_material_no,child_material_no,child_material_name,stock_unit,bom_purpose,bom_version,child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type) VALUES(?,'U9',NOW(),'210',?,?,?,'KG','主制造','MPP12',1,0.04,1,?,'2099-01-01','NORMAL')",product,b,makeRaw,makeRaw,date.toLocalDate());
    sources.comparisonFixedPrice(makeRaw,"100",date);jdbc.update("UPDATE lp_price_fixed_item SET unit='KG' WHERE material_code=?",makeRaw);
    jdbc.update("INSERT INTO lp_material_scrap_ref(material_code,scrap_code,scrap_unit,ratio,business_unit_type) VALUES(?,?,'KG',1,'COMMERCIAL')",makeRaw,scrap);
    jdbc.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,created_by,created_at,updated_by,updated_at) VALUES('COMMERCIAL',?,?,'废料','FIXED',5,'{}','IMPORT',?,?,'PART_TEST',NOW(),'PART_TEST',NOW())",month,scrap,month,"a".repeat(64));
    String password="PartCost-Local-Test-2026";
    var actors=new LinkedHashMap<String,Object>();
    for(String role:List.of("admin","bu_director","staff")) {
      String actor="part_"+role+"_"+UUID.randomUUID().toString().substring(0,6);
      jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,?,?,'COMMERCIAL','0','0','PART_TEST',NOW(),'PART_TEST',NOW())",actor,role,new BCryptPasswordEncoder().encode(password));
      long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,actor);
      var roles=jdbc.queryForList("SELECT role_id FROM sys_role WHERE role_key=?",Long.class,role);
      if(roles.isEmpty()) {
        jdbc.update("INSERT INTO sys_role(role_name,role_key,role_sort,status,del_flag,create_time,update_time) VALUES(?,?,99,'0','0',NOW(),NOW())",role,role);
        roles=jdbc.queryForList("SELECT role_id FROM sys_role WHERE role_key=?",Long.class,role);
      }
      jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",user,roles.getFirst());
      if(role.equals("bu_director"))jdbc.update("INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT ?,menu_id FROM sys_menu WHERE menu_id IN (40475,40560,40561,40562,40563,40564)",roles.getFirst());
      actors.put(role,Map.of("username",actor,"password",password,"token",jwt.generateToken(actor,"COMMERCIAL")));
    }
    var json=new ObjectMapper().findAndRegisterModules();
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    var fixture=new LinkedHashMap<String,Object>(Map.of("apiPort",port,"actors",actors,"oaNo",product,"product",product,"itemId",item,"a",a,"b",b,"month",month));
    fixture.put("jdbcUrl",MYSQL.getJdbcUrl()+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");fixture.put("jdbcUser",MYSQL.getUsername());fixture.put("jdbcPassword",MYSQL.getPassword());
    String importDir=System.getProperty("part.cost.import.fixtures");
    if(importDir!=null) {
      Path dir=Path.of(importDir);Files.createDirectories(dir);
      var excel=new com.sanhua.marketingcost.service.ProductPartCostRuleExcel();
      try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(excel.template()))) {
        String[][] values={{product,"验收成品","验收规格","验收型号",a,"零件A","CODE-MODEL","采购件","20%"},
            {product,"验收成品","验收规格","验收型号","","零件B","SAME-MODEL","采购件","40%"},
            {"000001","前导零成品","规格","型号","000002","前导零零件","","采购件","0%"}};
        for(int r=0;r<values.length;r++) {var row=book.getSheetAt(0).createRow(r+1);for(int c=0;c<9;c++)row.createCell(c).setCellValue(values[r][c]);}
        try(var output=Files.newOutputStream(dir.resolve("valid-import.xlsx"))){book.write(output);}
        book.getSheetAt(0).getRow(2).getCell(8).setCellValue("100%");
        try(var output=Files.newOutputStream(dir.resolve("invalid-rate.xlsx"))){book.write(output);}
        book.getSheetAt(0).getRow(2).getCell(8).setCellValue("40%");
        book.getSheetAt(0).getRow(0).getCell(4).setCellValue("错误表头");
        try(var output=Files.newOutputStream(dir.resolve("invalid-header.xlsx"))){book.write(output);}
      }
    }
    Files.writeString(state,json.writeValueAsString(fixture));
    Path release=Path.of(state+".release");long deadline=System.nanoTime()+Duration.ofMinutes(40).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline)Thread.sleep(250);
    assertThat(Files.exists(release)).isTrue();
    var accepted=json.readTree(Files.readString(release));assertThat(accepted.path("passed").asBoolean()).isTrue();
    var versions=new ArrayList<Map<String,Object>>();
    for(var expected:accepted.path("versions")) {
      var version=jdbc.queryForMap("SELECT id,total_cost,final_quote_amount FROM lp_quote_cost_run_version WHERE id=? AND oa_form_item_id=?",expected.path("id").asLong(),item);
      assertThat((BigDecimal)version.get("total_cost")).isEqualByComparingTo(expected.path("total").asText());
      assertThat((BigDecimal)version.get("final_quote_amount")).isEqualByComparingTo(expected.path("total").asText());
      versions.add(version);
    }
    assertThat(versions).hasSize(5);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_product_part_cost_rule WHERE product_code=?",Integer.class,product)).isZero();
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of("passed",true,"versions",versions,"allSavedAmountsPreserved",true)));
  }
}
