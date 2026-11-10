package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowClient;
import com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Actual browser/API/MySQL acceptance; the outbound OA transport alone is controlled. */
@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15209",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "integration.oa-person-directory.source-system=HZ_KG_OA_TST", "integration.oa-person-directory.environment=TEST",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
class CostingNotificationRecalculateBrowserSession extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper rawHierarchy;
  @MockBean OaWorkflowClient oa;
  @Autowired com.sanhua.marketingcost.integration.oa.OaWorkflowNotificationService notifications;

  @org.springframework.test.context.DynamicPropertySource
  static void pricingPeriod(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",
        com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void browserSession() throws Exception {
    Path state=Path.of(System.getProperty("household.final.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    var scenarios=new ArrayList<Map<String,Object>>();
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    for (String businessType : List.of("新品","衍生品","批量品")) {
      String product="SR005-"+UUID.randomUUID().toString().substring(0,8),material=product+"-PART";
      jdbc.update("INSERT INTO oa_form(oa_no,process_code,business_unit_type,copper_price,accounting_period_month) VALUES(?,'FI-SR-005','COMMERCIAL',80000,?)",product,month);
      long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,product);
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,seq,material_no,product_name,business_type,shipping_fee,business_unit_type) VALUES(?,7,?,?,?,1.2345,'COMMERCIAL')",form,product,"家用成本回传"+businessType,businessType);
      long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
      jdbc.update("INSERT INTO lp_oa_quote_document(source_system,environment,external_document_id,oa_form_id,source_version) VALUES('HZ_KG_OA_TST','TEST',?,?,1)",product,form);
      jdbc.update("INSERT INTO lp_oa_form_item_extra_field(oa_form_id,oa_form_item_id,field_code,field_name,field_value,business_unit_type) VALUES(?,?,'OA_ROW_ID','原明细行','SR005-ROW','COMMERCIAL')",form,item);
      var sources=new OrdinaryCostingTestSources(jdbc,rawHierarchy);
      sources.ordinarySources(product,material,form,date);
      sources.comparisonFixedPrice(material,"2",date);
      jdbc.update("UPDATE lp_three_expense_rate SET product_category='家代商代销产品',department=CONCAT(department,'-直销'),applicant_department=CONCAT(applicant_department,'-直销') WHERE company=?",product);
      jdbc.update("""
          INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,
            parent_material_no,child_material_no,child_material_name,stock_unit,bom_purpose,bom_version,
            child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type)
          SELECT r.source_import_batch_id,'U9',NOW(),r.price_org_code,r.parent_code,r.material_code,
            r.material_name,'件',r.bom_purpose,r.bom_version,r.sort_seq,r.qty_per_parent,1,
            r.effective_from,r.effective_to,'NORMAL' FROM lp_bom_raw_hierarchy r
          WHERE r.top_product_code=? AND r.level>0
          """,product);
      jdbc.update("UPDATE oa_form SET process_code='FI-SR-005' WHERE id=?",form);
      jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",product);
      jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
      scenarios.add(Map.of("oaNo",product,"formId",form,"itemId",item,"material",material,"businessType",businessType));
    }
    String username="sr005_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,employee_no,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'家用回传验收',?,'SR005001','COMMERCIAL','0','0','SR005_TEST',NOW(),'SR005_TEST',NOW())",username,new BCryptPasswordEncoder().encode(password));
    long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,username);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",user);
    var sent=new CopyOnWriteArrayList<ObjectNode>();
    when(oa.submit(any())).thenAnswer(call -> {
      ObjectNode body=call.getArgument(0);sent.add(body.deepCopy());
      return new OaWorkflowResult("sr005-browser",OaWorkflowResult.Status.SUCCESS,200,"0","success",body.path("requestId").asText(),null,1);
    });
    var json=new ObjectMapper();
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(Map.of("apiPort",port,"username",username,"password",password,"month",month,"scenarios",scenarios,"jdbcUrl",MYSQL.getJdbcUrl(),"dbUser",MYSQL.getUsername(),"dbPassword",MYSQL.getPassword())));
    Path release=Path.of(state+".release");
    long deadline=System.nanoTime()+Duration.ofMinutes(12).toNanos();
    var oldCosts=new ArrayList<Map<String,Object>>();
    var oldSubmissions=new ArrayList<Map<String,Object>>();
    int delivered=0;
    while(!Files.exists(release)&&System.nanoTime()<deadline) {
      if(delivered<2 && Files.exists(Path.of(state+".return-"+delivered))) {
        var scenario=scenarios.get(delivered);
        oldCosts.add(jdbc.queryForMap("SELECT id,total_cost,final_quote_amount FROM lp_quote_cost_run_version WHERE oa_form_item_id=? ORDER BY id DESC LIMIT 1",scenario.get("itemId")));
        oldSubmissions.add(jdbc.queryForMap("SELECT id,cost_snapshot_json FROM lp_quote_final_submission WHERE oa_form_id=? ORDER BY id DESC LIMIT 1",scenario.get("formId")));
        var notice=json.createObjectNode().put("requestId",(String)scenario.get("oaNo")).put("eventType","COSTING");
        var reply=notifications.receive(new com.sanhua.marketingcost.integration.oa.OaPeer("HZ_KG_OA_TST","TEST",Set.of("COMMERCIAL")),json.writeValueAsString(notice));
        assertThat(reply.path("code").asText()).isEqualTo("0");
        assertThat(jdbc.queryForObject("SELECT calc_status FROM oa_form WHERE id=?",String.class,scenario.get("formId"))).isEqualTo("待核算");
        Files.writeString(Path.of(state+".returned-"+delivered),"ready");delivered++;
      }
      Thread.sleep(200);
    }
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(delivered).isEqualTo(2);
    assertThat(sent).hasSize(5);
    for (var scenario : scenarios) {
      var body=sent.stream().filter(b -> b.path("requestId").asText().equals(scenario.get("oaNo"))).findFirst().orElseThrow();
      String type=(String)scenario.get("businessType"),without="新品".equals(type)?"zcbbhs":"cb1",with="新品".equals(type)?"hysfzcbbhs":"zcbbhs";
      String table="批量品".equals(type)?"1328371833677696777":"1328371833677696778";
      var details=body.at("/formData/dataDetails");
      assertThat(details).hasSize(2);
      assertThat(details.get(0).path("dataKey").asText()).isEqualTo(without);
      assertThat(details.get(1).path("dataKey").asText()).isEqualTo(with);
      var cost=jdbc.queryForMap("SELECT id,final_quote_amount FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status='SUCCESS' ORDER BY id DESC LIMIT 1",scenario.get("itemId"));
      BigDecimal amount=(BigDecimal)cost.get("final_quote_amount");
      assertThat(new BigDecimal(details.get(0).path("content").asText())).isEqualByComparingTo(amount);
      assertThat(new BigDecimal(details.get(1).path("content").asText())).isEqualByComparingTo(amount.add(new BigDecimal("1.2345")));
      details.forEach(field -> {assertThat(field.path("subFormId").asText()).isEqualTo(table);assertThat(field.path("dataIndex").asInt()).isEqualTo(7);});
      assertThat(jdbc.queryForObject("SELECT status FROM lp_quote_final_submission WHERE oa_form_id=? ORDER BY id DESC LIMIT 1",String.class,scenario.get("formId"))).isEqualTo("SUBMITTED");
    }
    for(int i=0;i<2;i++) {
      var scenario=scenarios.get(i);var old=oldCosts.get(i);var oldSubmission=oldSubmissions.get(i);
      assertThat(jdbc.queryForMap("SELECT id,total_cost,final_quote_amount FROM lp_quote_cost_run_version WHERE id=?",old.get("id"))).isEqualTo(old);
      assertThat(jdbc.queryForObject("SELECT confirmed_cost_version_id FROM oa_form_item WHERE id=?",Long.class,scenario.get("itemId"))).isNotEqualTo(((Number)old.get("id")).longValue());
      assertThat(jdbc.queryForMap("SELECT id,cost_snapshot_json FROM lp_quote_final_submission WHERE id=?",oldSubmission.get("id"))).isEqualTo(oldSubmission);
      assertThat(jdbc.queryForList("SELECT status FROM lp_quote_final_submission WHERE oa_form_id=? ORDER BY id",String.class,scenario.get("formId"))).containsExactly("RETURNED","SUBMITTED");
    }
    Files.writeString(Path.of(state+".verified"),json.writeValueAsString(Map.of("passed",true,"actualApiAndMySql",true,"oaTransport","controlled","requests",sent)));
  }
}
