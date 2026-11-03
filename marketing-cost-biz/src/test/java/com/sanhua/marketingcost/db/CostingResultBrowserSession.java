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

/** Fresh disposable ordinary-cost sources; browser executes both costs through the actual API. */
@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","app.cors.allowed-origins=http://127.0.0.1:15192",
    "integration.oa-person-directory.enabled=false","integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false","srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false"})
class CostingResultBrowserSession extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtUtils jwt;
  @Autowired BomRawHierarchyMapper rawHierarchy;

  @org.springframework.test.context.DynamicPropertySource
  static void pricingPeriod(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",
        com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void browserSession() throws Exception {
    Path state=Path.of(System.getProperty("cost.result.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    String product="INLINE-"+UUID.randomUUID().toString().substring(0,8), material=product+"-PART";
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",product);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,product);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')",form,product,"结果直达测试产品");
    long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    var sources=new OrdinaryCostingTestSources(jdbc,rawHierarchy);
    sources.ordinarySources(product,material,form,date); sources.comparisonFixedPrice(material,"2",date);
    jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",product);
    jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
    var batchItems=new ArrayList<Long>();
    if(Boolean.getBoolean("cost.result.browser.batch")) {
      for(int i=1;i<=2;i++) {
        String extra="BATCH-"+i+"-"+UUID.randomUUID().toString().substring(0,6),part=extra+"-PART";
        jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')",form,extra,"整单性能验证产品"+i);
        batchItems.add(jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=? AND material_no=?",Long.class,form,extra));
        sources.ordinarySources(extra,part,form,date);sources.comparisonFixedPrice(part,"2",date);
        jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",extra+"-BARE",extra);
        jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",extra+"-BARE",extra+"-BARE",extra);
        jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",extra+"-BARE",extra);
      }
    }
    String actor="inline_"+UUID.randomUUID().toString().substring(0,8);
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'结果直达验收','unused','COMMERCIAL','0','0','INLINE',NOW(),'INLINE',NOW())",actor);
    long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,actor);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",user);
    var json=new ObjectMapper();
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    var fixture=new LinkedHashMap<String,Object>(Map.of("apiPort",port,"token",jwt.generateToken(actor,"COMMERCIAL"),
        "oaNo",product,"itemId",item,"materialNo",product,"child",material,"month",month));
    fixture.put("batchItems",batchItems);
    if(!batchItems.isEmpty()) {
      fixture.put("jdbcUrl",MYSQL.getJdbcUrl()+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true");
      fixture.put("jdbcUser",MYSQL.getUsername());fixture.put("jdbcPassword",MYSQL.getPassword());
    }
    Files.writeString(state,json.writeValueAsString(fixture));
    Path change=Path.of(state+".change-input"),release=Path.of(state+".release");
    Map<String,Object> first=null;
    long deadline=System.nanoTime()+Duration.ofMinutes(15).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline) {
      if(first==null&&Files.exists(change)) {
        first=jdbc.queryForMap("SELECT id,cost_run_no,pricing_month,total_cost,final_quote_amount FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status='SUCCESS'",item);
        jdbc.update("UPDATE lp_quality_loss_rate SET loss_rate=0.02 WHERE bare_product_code=? AND business_unit_type='COMMERCIAL'",product+"-BARE");
        Files.writeString(Path.of(state+".input-changed"),json.writeValueAsString(Map.of("firstVersionId",first.get("id"))));
      }
      Thread.sleep(250);
    }
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(first).isNotNull();
    var latest=jdbc.queryForMap("SELECT id,cost_run_no,pricing_month,total_cost,final_quote_amount FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status='SUCCESS'",item);
    assertThat(latest.get("id")).isNotEqualTo(first.get("id"));
    assertThat(latest.get("pricing_month")).isEqualTo(month);
    assertThat((BigDecimal)latest.get("final_quote_amount")).isNotEqualByComparingTo((BigDecimal)first.get("final_quote_amount"));
    assertThat(jdbc.queryForMap("SELECT id,cost_run_no,pricing_month,total_cost,final_quote_amount FROM lp_quote_cost_run_version WHERE id=?",first.get("id"))).isEqualTo(first);
    for(long batchItem:batchItems) {
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND pricing_month=? AND status='SUCCESS'",Integer.class,batchItem,month)).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_task WHERE oa_form_item_id=? AND status='SUCCESS'",Integer.class,batchItem)).isEqualTo(1);
    }
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of("passed",true,"oaNo",product,"itemId",item,"month",month,"first",first,"latest",latest,"firstVersionAmountsPreserved",true)));
  }

}
