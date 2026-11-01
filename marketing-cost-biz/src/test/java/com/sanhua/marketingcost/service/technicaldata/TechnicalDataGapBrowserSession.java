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
@org.junit.jupiter.api.Tag("integration")
class TechnicalDataGapBrowserSession extends TechnicalDataNativeChainIntegrationTest {
  @LocalServerPort int port;
  @Autowired JwtUtils jwt;
  @Autowired SysMenuMapper menus;
  @Override protected String accountingMonth() { return com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth(); }

  @Test void browserSession() throws Exception {
    Path state = Path.of(System.getProperty("gap.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    var fixture = submittedFixture(2, true);
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
    value.put("apiPort",port); value.put("token",jwt.generateToken(username,"COMMERCIAL"));
    value.put("oaNo",oaNo); value.put("formId",fixture.formId()); value.put("month",accountingMonth()); value.put("items",items);
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(value));
    long deadline = System.nanoTime()+Duration.ofMinutes(25).toNanos();
    Path release=Path.of(state+".release"), resubmit=Path.of(state+".resubmit");
    boolean resubmitted=false;
    boolean missingMaster=false;
    List<Map<String,Object>> preservedVersions=List.of(), preservedCosts=List.of();
    int preservedBomRows=0;
    while (!Files.exists(release) && System.nanoTime()<deadline) {
      if (!resubmitted && Files.exists(resubmit)) {
        long task=fixture.taskIds().getFirst();
        var product=repository.findProducts(task).getFirst();
        assertThat(gapStatus.forForm(fixture.formId(),product.getOaFormItemId(),accountingMonth()).getFirst().pendingModules()).hasSize(2);
        save(task,DING,"8.25"); saveRate(task,"0.4");
        assertThat(documents.submit(fixture.formId(),request(fixture.formId(),DING),DING).status()).isEqualTo("SUCCESS");
        assertThat(gapStatus.forForm(fixture.formId(),product.getOaFormItemId(),accountingMonth()).getFirst().pendingModules()).isEmpty();
        Files.writeString(Path.of(state+".resubmitted"),"ok"); resubmitted=true;
      }
      if (Boolean.getBoolean("gap.browser.bom-failure") && !missingMaster
          && Files.exists(Path.of(state+".missing-master"))) {
        var product=repository.findProducts(fixture.taskIds().getFirst()).getFirst();
        preservedVersions=jdbc.queryForList("SELECT * FROM lp_quote_tech_data_version ORDER BY id");
        preservedCosts=jdbc.queryForList("SELECT * FROM lp_quote_cost_run_version ORDER BY id");
        preservedBomRows=jdbc.queryForObject("SELECT COUNT(*) FROM lp_bom_costing_row WHERE oa_form_item_id=?",
            Integer.class,product.getOaFormItemId());
        assertThat(preservedBomRows).isPositive();
        assertThat(jdbc.update("DELETE FROM lp_material_master_raw WHERE material_code=? AND import_batch_id=?",
            product.getMaterialNo(),oaNo)).isEqualTo(1);
        missingMaster=true;
        Files.writeString(Path.of(state+".master-removed"),"isolated owned raw material removed");
      }
      Thread.sleep(250);
    }
    assertThat(Files.exists(release)).as("Browser session completed").isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(resubmitted).isTrue();
    if (Boolean.getBoolean("gap.browser.bom-failure")) {
      assertThat(missingMaster).isTrue();
      var product=repository.findProducts(fixture.taskIds().getFirst()).getFirst();
      var workspace=jdbc.queryForMap("SELECT workspace_status,current_step,last_error_message FROM lp_quote_costing_workspace WHERE oa_form_item_id=? AND period_month=?",
          product.getOaFormItemId(),accountingMonth());
      assertThat(workspace.get("workspace_status")).isEqualTo("SYSTEM_FAILED");
      assertThat(workspace.get("current_step")).isEqualTo("QUOTE_BOM");
      assertThat(workspace.get("last_error_message").toString()).contains("料品主档缺失");
      assertThat(jdbc.queryForList("SELECT * FROM lp_quote_tech_data_version ORDER BY id")).isEqualTo(preservedVersions);
      assertThat(jdbc.queryForList("SELECT * FROM lp_quote_cost_run_version ORDER BY id")).isEqualTo(preservedCosts);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_bom_costing_row WHERE oa_form_item_id=?",
          Integer.class,product.getOaFormItemId())).isEqualTo(preservedBomRows);
      Files.writeString(Path.of(state+".db-verified"), json.writeValueAsString(Map.of(
          "passed",true,"itemId",product.getOaFormItemId(),"month",accountingMonth(),
          "workspace",workspace,"savedTechnicalVersionsUnchanged",true,"savedCostVersionsUnchanged",true,
          "preservedBomRows",preservedBomRows)));
    }
    var untouched=repository.findProducts(fixture.taskIds().getLast()).getFirst();
    assertThat(gapStatus.forForm(fixture.formId(),untouched.getOaFormItemId(),accountingMonth()).getFirst().pendingModules()).isEmpty();
  }
}
