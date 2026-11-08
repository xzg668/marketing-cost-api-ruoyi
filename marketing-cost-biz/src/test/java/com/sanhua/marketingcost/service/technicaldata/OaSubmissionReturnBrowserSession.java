package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult;
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

/** 隔离浏览器核验：退回后沿用未修改资料并重提；核算页面仍可重进和刷新。 */
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
class OaSubmissionReturnBrowserSession extends TechnicalDataNativeChainIntegrationTest {
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
    Path state = Path.of(System.getProperty("submission.return.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    var fixture = submittedFixture(1, true);
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
    var technician = users.getById(DING.userId());
    technician.setBusinessUnitType("COMMERCIAL"); technician.setPassword("unused");
    when(users.findByUsername(technician.getUserName())).thenReturn(technician);
    when(users.findRolesByUserId(DING.userId())).thenReturn(List.of());
    when(users.findPermissionsByUserId(DING.userId())).thenReturn(DING.authorities());
    when(users.findVisibleMenus(DING.userId(), "COMMERCIAL")).thenReturn(List.of());
    var json = new ObjectMapper();
    var value = new LinkedHashMap<String,Object>();
    value.put("apiPort", port); value.put("token", jwt.generateToken(username, "COMMERCIAL"));
    value.put("technicianToken", jwt.generateOaSessionToken(technician.getEmployeeNo(), technician.getNickName(),
        "COMMERCIAL", fixture.formId(), "TEST", 900));
    value.put("oaNo", oaNo); value.put("formId", fixture.formId());
    value.put("month", accountingMonth()); value.put("items", items);
    Files.createFile(state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state, json.writeValueAsString(value));
    long deadline = System.nanoTime() + Duration.ofMinutes(15).toNanos();
    Path release = Path.of(state + ".release"), prepare = Path.of(state + ".prepare-return");
    boolean returned = false;
    long task = fixture.taskIds().getFirst();
    var product = repository.findProducts(task).getFirst();
    var history = jdbc.queryForList("SELECT * FROM lp_quote_tech_submission WHERE task_id=? ORDER BY id", task);
    while (!Files.exists(release) && System.nanoTime() < deadline) {
      if (!returned && Files.exists(prepare)) {
        jdbc.update("UPDATE lp_quote_tech_module SET required_flag=0,module_status='NOT_REQUIRED',source_availability='AVAILABLE',source_checked_at=NOW() WHERE product_id=? AND module_type IN ('PACKAGE','PRICE') AND assignee_user_id IS NULL AND current_version_id IS NULL", product.getId());
        jdbc.update("UPDATE lp_material_master_raw SET material_model=?,production_division='其他事业部' WHERE material_code=? AND organization_code='COMMERCIAL'", product.getSourceModel(), product.getMaterialNo());
        var notice = json.createObjectNode().put("requestId", "FLOW-" + fixture.formId())
            .put("eventType", "TECHNICAL").put("reason", "请将净损失率修正为1%");
        notices.receive(new com.sanhua.marketingcost.integration.oa.OaPeer("HZ_KG_OA_TST", "TEST", Set.of("COMMERCIAL")), notice.toString());
        assertThat(documents.workbench(fixture.formId(), DING).lastSubmission().status()).isEqualTo("RETURNED");
        var viewer = new TechnicalDataActor(1L, "OA查看人", DING.authorities(), fixture.formId());
        assertThat(views.detail(task, viewer).taskStatus()).isEqualTo("PARTIALLY_RETURNED");
        assertThat(documents.workbench(fixture.formId(), DING).canSubmit()).isFalse();
        Files.writeString(Path.of(state + ".return-ready"), "ready");
        returned = true;
      }
      Thread.sleep(250);
    }
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(returned).isTrue();
    assertThat(people.current(task).getFirst().todoStatus()).isEqualTo("SUBMITTED");
    assertThat(netLoss.get(product.getId(), null, ADMIN).content().rate()).isEqualByComparingTo("0.01");
    for (var row : history) {
      assertThat(jdbc.queryForObject("SELECT content_snapshot_json FROM lp_quote_tech_submission WHERE id=?",
          String.class, row.get("id"))).isEqualTo(row.get("content_snapshot_json"));
    }
    verify(oa, times(1)).submit(any()); verify(oa, never()).reject(any());
    int rows = jdbc.queryForObject("SELECT COUNT(*) FROM lp_bom_costing_row WHERE oa_form_item_id=? AND period_month=?",
        Integer.class, product.getOaFormItemId(), accountingMonth());
    assertThat(rows).isPositive();
    Files.writeString(Path.of(state + ".db-verified"), json.writeValueAsString(Map.of(
        "passed", true, "formId", fixture.formId(), "itemId", product.getOaFormItemId(),
        "month", accountingMonth(), "bomRows", rows, "netLossRate", "0.01", "historicalSnapshotsPreserved", true)));
  }
}
