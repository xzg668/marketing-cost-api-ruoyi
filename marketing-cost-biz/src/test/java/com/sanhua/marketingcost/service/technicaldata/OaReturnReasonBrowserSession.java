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

/** 隔离浏览器核验：报价员修改原 remark 后按顺序重新同步和退回。 */
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
class OaReturnReasonBrowserSession extends TechnicalDataNativeChainIntegrationTest {
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
    Path state = Path.of(System.getProperty("return.reason.browser.state"));
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
    var json = new ObjectMapper();
    var value = new LinkedHashMap<String,Object>();
    value.put("requestId","FLOW-"+fixture.formId());
    value.put("apiPort",port); value.put("token",jwt.generateToken(username,"COMMERCIAL"));
    value.put("oaNo",oaNo); value.put("formId",fixture.formId()); value.put("month",accountingMonth()); value.put("items",items);
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(value));
    long deadline=System.nanoTime()+Duration.ofMinutes(20).toNanos();
    Path release=Path.of(state+".release"), prepare=Path.of(state+".prepare-return");
    TechnicalDataReturnService.Result pending=null;
    while(!Files.exists(release)&&System.nanoTime()<deadline) {
      if(pending==null&&Files.exists(prepare)) {
        long task=fixture.taskIds().getFirst();
        // 核算检查可增加不在本次退回场景的未分派缺口；仅收束本隔离样例的退回范围。
        jdbc.update("UPDATE lp_quote_tech_module SET required_flag=0,module_status='NOT_REQUIRED',source_availability='AVAILABLE',source_checked_at=NOW() WHERE product_id=? AND module_type IN ('PACKAGE','PRICE') AND assignee_user_id IS NULL AND current_version_id IS NULL", repository.findProducts(task).getFirst().getId());
        var req=new TechnicalDataReturnService.Request(UUID.randomUUID().toString(),List.of(
            new TechnicalDataReturnService.Target(task,taskMapper.selectById(task).getTaskVersion(),
                List.of("PROFILE","NET_LOSS"),"请调整净损失率")));
        doReturn(new OaWorkflowResult("reject",OaWorkflowResult.Status.REJECTED,200,"1200504","已操作",
            "FLOW-"+fixture.formId(),null,1)).when(oa).reject(any());
        pending=returns.submit(req,ADMIN);
        assertThat(pending.status()).isEqualTo("RETURN_FAILED");
        String oldNode=access.quoterNode(fixture.formId()).workItemId();
        var notice=json.createObjectNode().put("requestId","FLOW-"+fixture.formId())
            .put("eventType","COSTING").put("reason","人工干预");
        var peer=new com.sanhua.marketingcost.integration.oa.OaPeer("HZ_KG_OA_TST","TEST",Set.of("COMMERCIAL"));
        assertThat(notices.receive(peer,notice.toString()).path("code").asText()).isEqualTo("0");
        String newNode=access.quoterNode(fixture.formId()).workItemId();
        assertThat(newNode).isNotEqualTo(oldNode);
        doAnswer(call->new OaWorkflowResult("browser-retry",OaWorkflowResult.Status.SUCCESS,200,"0","成功",
            ((com.fasterxml.jackson.databind.node.ObjectNode)call.getArgument(0)).path("requestId").asText(),null,1)).when(oa).reject(any());
        Files.writeString(Path.of(state+".return-ready"),json.writeValueAsString(Map.of(
            "batchId",pending.batchId(),"oldNode",oldNode,"newNode",newNode)));
      }
      Thread.sleep(250);
    }
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(pending).isNotNull();
    var result=returns.status(pending.batchId(),ADMIN);
    assertThat(result.status()).isEqualTo("SUCCESS");
    verify(oa,times(2)).submit(any()); verify(oa,times(2)).reject(any());
    var requests=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.node.ObjectNode.class);
    verify(oa,times(2)).submit(requests.capture());
    assertThat(requests.getAllValues().get(0).path("remark").asText()).contains("请调整净损失率");
    assertThat(requests.getAllValues().get(1).path("remark").asText()).contains("请将净损失率修正为1%").doesNotContain("请调整净损失率");
    long task=fixture.taskIds().getFirst();
    var product=repository.findProducts(task).getFirst();
    assertThat(netLoss.get(product.getId(),null,DING).editable()).isTrue();
    assertThat(people.current(task).getFirst().returnReason()).isEqualTo("请将净损失率修正为1%");
    assertThat(people.current(task).getFirst().processingModules()).containsExactly("PROFILE","NET_LOSS");
    int rows=jdbc.queryForObject("SELECT COUNT(*) FROM lp_bom_costing_row WHERE oa_form_item_id=? AND period_month=?",
        Integer.class,product.getOaFormItemId(),accountingMonth());
    assertThat(rows).isPositive();
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of(
        "passed",true,"batchId",pending.batchId(),"status",result.status(),"attempt",result.attempt(),
        "formId",fixture.formId(),"itemId",product.getOaFormItemId(),"month",accountingMonth(),
        "bomRows",rows,"technicianEditable",true,"outboundCalls",Map.of("submit",2,"reject",2))));
  }
}
