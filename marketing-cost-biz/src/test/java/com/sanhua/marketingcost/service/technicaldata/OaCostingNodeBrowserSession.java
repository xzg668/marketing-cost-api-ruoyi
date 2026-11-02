package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.SysMenuMapper;
import com.sanhua.marketingcost.security.JwtUtils;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 真实浏览器与实际隔离 MySQL/API 验收；仅 OA 外部传输使用父类的受控适配。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15192",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
@org.springframework.test.context.TestPropertySource(properties = {
    "integration.oa.mode=MOCK", "integration.oa.environment=TEST",
    "integration.oa.clients.costing-node.source-system=HZ_KG_OA_TST",
    "integration.oa.clients.costing-node.environment=TEST",
    "integration.oa.clients.costing-node.mode=MOCK",
    "integration.oa.clients.costing-node.business-units=COMMERCIAL",
    "integration.oa.clients.costing-node.secret=costing-node-isolated-test-token-32-characters"})
@org.junit.jupiter.api.Tag("integration")
class OaCostingNodeBrowserSession extends TechnicalDataNativeChainIntegrationTest {
  @LocalServerPort int port;
  @Autowired JwtUtils jwt;
  @Autowired SysMenuMapper menus;
  @Override protected String accountingMonth() { return com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth(); }

  @org.springframework.test.context.DynamicPropertySource
  static void pricingPeriod(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",
        com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void browserSession() throws Exception {
    Path state = Path.of(System.getProperty("costing.node.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    var fixture = submittedFixture(1, true);
    long initialSequence=jdbc.queryForObject("SELECT applied_version FROM lp_oa_workflow_state WHERE workflow_request_id=?",Long.class,"FLOW-"+fixture.formId());
    jdbc.update("UPDATE lp_oa_workflow_state SET state='TECHNICAL',active_work_items_json='[]' WHERE workflow_request_id=?","FLOW-"+fixture.formId());
    jdbc.update("UPDATE sys_user SET employee_no='11101516' WHERE user_id=1");
    var originalVersions = jdbc.queryForList("SELECT * FROM lp_quote_tech_data_version ORDER BY id");
    var originalSubmissions = jdbc.queryForList("SELECT * FROM lp_quote_tech_submission ORDER BY id");
    String username = jdbc.queryForObject("SELECT user_name FROM sys_user WHERE user_id=1", String.class);
    var admin = users.getById(1L); admin.setUserName(username); admin.setPassword("unused");
    admin.setBusinessUnitType("COMMERCIAL");
    when(users.findByUsername(username)).thenReturn(admin);
    var role = new SysRole(); role.setRoleKey("admin");
    when(users.findRolesByUserId(1L)).thenReturn(List.of(role));
    when(users.findPermissionsByUserId(1L)).thenReturn(Set.of("*:*:*"));
    when(users.findVisibleMenus(1L,"COMMERCIAL")).thenReturn(menus.selectList(null));
    String oaNo = taskMapper.selectById(fixture.taskIds().getFirst()).getOaNo();
    var items = new ArrayList<Map<String,Object>>();
    for (long taskId : fixture.taskIds()) {
      var product = repository.findProducts(taskId).getFirst();
      String code = product.getMaterialNo(), child = code + "-PART";
      jdbc.update("""
          INSERT INTO lp_quote_costing_workspace(oa_no,oa_form_item_id,product_code,period_month,
            business_unit_type,workspace_status,current_step,gap_count,last_checked_at,last_error_message)
          VALUES(?,?,?,?,'COMMERCIAL','WAIT_TECH_DATA','PRODUCT_DETAIL',2,NOW(),'旧检查：产品资料和净损失率缺失')
          """, oaNo,product.getOaFormItemId(),code,accountingMonth());
      for (String material : List.of(code,child)) {
        jdbc.update("""
            INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,
              import_batch_id,active_flag,main_category_code,shape_attr)
            VALUES(?,?,'只','COMMERCIAL',?,1,?,?)
            """, material, material.equals(code)?"补录联动验收产品":"验收采购件",oaNo,
            material.equals(code)?"10":"20",material.equals(code)?"制造件":"采购件");
      }
      jdbc.update("""
          INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,
            parent_material_no,child_material_no,bom_purpose,bom_version,child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to)
          VALUES(?,'EXCEL',NOW(),'210',?,?,'主制造','V1',1,1,1,'2026-01-01','9999-12-31')
          """, oaNo,code,child);
      for (int level : List.of(0,1)) {
        jdbc.update("""
            INSERT INTO lp_bom_raw_hierarchy(price_org_code,top_product_code,parent_code,material_code,
              level,path,sort_seq,qty_per_parent,qty_per_top,material_name,shape_attr,source_category,
              bom_purpose,bom_version,is_leaf,effective_from,effective_to,source_type,
              source_import_batch_id,build_batch_id,built_at,business_unit_type,source_line_key)
            VALUES('210',?,?,?, ?,?,1,1,1,?,?,?,'主制造','V1',?,'2026-01-01','9999-12-31','U9',?,?,NOW(),'COMMERCIAL',?)
            """,code,code,level==0?code:child,level,"/"+code+"/"+(level==0?"":child+"/"),
            level==0?"补录联动验收产品":"验收采购件",level==0?"制造件":"采购件",level==0?"制造件":"采购件",level,oaNo,oaNo,UUID.randomUUID().toString());
      }
      items.add(Map.of("taskId",taskId,"itemId",product.getOaFormItemId(),"productId",product.getId(),"materialNo",code));
    }
    var json = new ObjectMapper();
    var value = new LinkedHashMap<String,Object>();
    value.put("requestId","FLOW-"+fixture.formId());
    value.put("apiPort",port); value.put("token",jwt.generateToken(username,"COMMERCIAL"));
    value.put("oaNo",oaNo); value.put("formId",fixture.formId()); value.put("month",accountingMonth()); value.put("items",items);
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(value));
    long deadline = System.nanoTime()+Duration.ofMinutes(20).toNanos();
    Path release=Path.of(state+".release"), returnCost=Path.of(state+".return-cost");
    Long costSubmission=null;
    String snapshot=null;
    while (!Files.exists(release) && System.nanoTime()<deadline) {
      if (costSubmission==null && Files.exists(returnCost)) {
        assertThat(jdbc.queryForObject("SELECT state FROM lp_oa_workflow_state WHERE workflow_request_id=?",String.class,"FLOW-"+fixture.formId())).isEqualTo("COSTING");
        String request="BROWSER-COST-"+fixture.formId();
        jdbc.update("INSERT INTO lp_oa_integration_message(source_system,environment,direction,request_id,interface_type,schema_version,occurred_at,raw_payload,payload_hash,allowed_business_units) VALUES('HZ_KG_OA_TST','TEST','OUTBOUND',?,'QUOTE_COST_SUBMIT',1,NOW(3),'{}',?,'COMMERCIAL')",request,"a".repeat(64));
        long message=jdbc.queryForObject("SELECT id FROM lp_oa_integration_message WHERE request_id=?",Long.class,request);
        var product=repository.findProducts(fixture.taskIds().getFirst()).getFirst();
        // 明确构造已发送成本的隔离夹具；不会调用真实 OA，也不声称夹具金额是实际核算结果。
        snapshot=json.writeValueAsString(List.of(Map.of("itemId",product.getOaFormItemId(),"productCode",product.getMaterialNo(),"costVersionId",999999,"totalCost","12.34","status","READY")));
        jdbc.update("INSERT INTO lp_quote_final_submission(oa_form_id,oa_no,accounting_month,submission_round,source_system,environment,external_document_id,business_unit_type,status,outbound_message_id,content_fingerprint,cost_snapshot_json,operator_user_id,operator_external_id) VALUES(?,?,?,1,'HZ_KG_OA_TST','TEST',?,'COMMERCIAL','SUBMITTED',?,?,?,1,'11101516')",fixture.formId(),oaNo,accountingMonth(),"FLOW-"+fixture.formId(),message,"a".repeat(64),snapshot);
        costSubmission=jdbc.queryForObject("SELECT id FROM lp_quote_final_submission WHERE outbound_message_id=?",Long.class,message);
        snapshot=jdbc.queryForObject("SELECT cost_snapshot_json FROM lp_quote_final_submission WHERE id=?",String.class,costSubmission);
        Files.writeString(Path.of(state+".cost-ready"),"ready");
      }
      Thread.sleep(250);
    }
    assertThat(Files.exists(release)).as("Browser session completed").isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(costSubmission).isNotNull();
    assertThat(jdbc.queryForObject("SELECT status FROM lp_quote_final_submission WHERE id=?",String.class,costSubmission)).isEqualTo("RETURNED");
    assertThat(jdbc.queryForObject("SELECT cost_snapshot_json FROM lp_quote_final_submission WHERE id=?",String.class,costSubmission)).isEqualTo(snapshot);
    var flow=jdbc.queryForMap("SELECT state,applied_version FROM lp_oa_workflow_state WHERE workflow_request_id=?","FLOW-"+fixture.formId());
    assertThat(flow.get("state")).isEqualTo("RECOSTING");
    assertThat(((Number)flow.get("applied_version")).longValue()).isEqualTo(initialSequence+2);
    assertThat(jdbc.queryForList("SELECT * FROM lp_quote_tech_data_version ORDER BY id")).isEqualTo(originalVersions);
    assertThat(jdbc.queryForList("SELECT * FROM lp_quote_tech_submission ORDER BY id")).isEqualTo(originalSubmissions);
    long item=repository.findProducts(fixture.taskIds().getFirst()).getFirst().getOaFormItemId();
    int rows=jdbc.queryForObject("SELECT COUNT(*) FROM lp_bom_costing_row WHERE oa_form_item_id=? AND period_month=?",Integer.class,item,accountingMonth());
    assertThat(rows).isPositive();
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of(
        "passed",true,"itemId",item,"month",accountingMonth(),"flow",flow,
        "technicalVersionsAndSubmissionsPreserved",true,"frozenCostPreserved",true,"bomRows",rows)));
  }
}
