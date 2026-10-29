package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit opt-in browser acceptance against this test's disposable MySQL only. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.cors.allowed-origins=http://127.0.0.1:15191", "server.address=127.0.0.1", "integration.oa-person-directory.enabled=false",
    "integration.oa-person-directory.run-at-startup=false", "cms.sync-publish.enabled=false",
    "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
class TechnicalDataPackageBrowserSession extends TechnicalDataPackageApplicationIntegrationTest {
  static {
    try { Class.forName("com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase"); }
    catch (ClassNotFoundException failure) { throw new ExceptionInInitializerError(failure); }
  }
  @Override protected String accountingMonth() { return com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth(); }
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper hierarchy;
  @Autowired TechnicalDataPackageApplicationService packages;
  @Autowired TechnicalDataParticipantVersions versions;
  @Autowired QuoteTechnicalDataRepository repository;
  @Autowired TechnicalDataVersionContentCodec codec;
  @Autowired com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository recipients;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void browserSession() throws Exception {
    Path state = Path.of(System.getProperty("package.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    var json = new ObjectMapper();
    var actor = new TechnicalDataActor(101L, "王工", Set.of("technical:data:task:edit"));
    long item = repository.findTask(taskId).orElseThrow().getOaFormItemId();
    jdbc.update("INSERT INTO oa_form(id,oa_no,business_unit_type,process_code,accounting_period_month,copper_price) VALUES(10,?,'COMMERCIAL','FI-SC-006',?,80000)", key, accountingMonth());
    jdbc.update("INSERT INTO oa_form_item(id,oa_form_id,material_no,product_name,business_unit_type) VALUES(?,10,?,'包装底数验收产品','COMMERCIAL')", item, key);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr) VALUES(?,'包装底数验收产品','只','COMMERCIAL',?,1,'10','制造件')", key, key);
    jdbc.update("INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,parent_material_no,child_material_no,bom_purpose,bom_version,child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to) VALUES(?,'EXCEL',NOW(),'210',?,?,'主制造','V1',1,1,150,'2026-09-01','9999-12-31')", key, key + "-T1", key + "-P");
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET qty_per_parent=150,qty_per_top=150 WHERE id=?", child1);
    jdbc.update("UPDATE lp_quote_tech_module SET required_flag=0,module_status='NOT_REQUIRED',source_availability='AVAILABLE' WHERE product_id=? AND module_type='PROFILE'", productId);
    jdbc.update("INSERT INTO lp_oa_integration_message(source_system,environment,direction,request_id,interface_type,schema_version,occurred_at,raw_payload,payload_hash,allowed_business_units) VALUES('TEST','UNIT','OUTBOUND',?,'TASK_DISPATCH',1,NOW(3),'{}',?,'COMMERCIAL')", key, "b".repeat(64));
    long messageId = jdbc.queryForObject("SELECT id FROM lp_oa_integration_message WHERE request_id=?", Long.class, key);
    jdbc.update("INSERT INTO lp_quote_tech_oa_recipient(task_id,assignment_version,assignee_user_id,assignee_name,external_user_id,module_types_json,outbound_message_id,external_task_id,dispatch_status,todo_status,active_flag,leader_external_id) VALUES(?,1,101,'王工','oa-tech-package','[\"PACKAGE\"]',?,'PACKAGE-TEST-TODO','CONFIRMED','OPEN',1,'oa-leader-package')", taskId, messageId);
    // A real target tree independent of the referenced packaging tree.
    BomRawHierarchy root = hierarchy.selectById(parent1);
    root.setId(null); root.setTopProductCode(key); root.setParentCode(key); root.setMaterialCode(key);
    root.setPath("/" + key + "/"); root.setLevel(0); root.setShapeAttr("制造件"); hierarchy.insert(root);
    BomRawHierarchy child = hierarchy.selectById(child1);
    child.setId(null); child.setTopProductCode(key); child.setParentCode(key); child.setLevel(1);
    child.setPath("/" + key + "/" + key + "-C/"); child.setShapeAttr("采购件"); child.setQtyPerParent(BigDecimal.ONE); child.setQtyPerTop(BigDecimal.ONE); hierarchy.insert(child);
    String password = UUID.randomUUID().toString(), adminPassword = UUID.randomUUID().toString();
    var encoder = new BCryptPasswordEncoder();
    jdbc.update("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag,create_time,update_time) VALUES(90101,'包装验收技术员','package_acceptance',99,'0','0',NOW(),NOW())");
    jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,password,business_unit_type,status,del_flag,create_time,update_time) VALUES(101,'package_technician','王工',?,'COMMERCIAL','0','0',NOW(),NOW()),(90101,'package_coordinator','验收报价员',?,'COMMERCIAL','0','0',NOW(),NOW())", encoder.encode(password), encoder.encode(adminPassword));
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(101,90101),(90101,1)");
    jdbc.update("INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT 90101,menu_id FROM sys_menu WHERE perms IN ('technical:data:task:list','technical:data:task:edit') OR path='collaboration'");
    var values = new LinkedHashMap<String,Object>();
    values.put("apiPort",port); values.put("taskId",taskId); values.put("productId",productId);
    values.put("itemId",item); values.put("oaNo",key); values.put("month",accountingMonth());
    values.put("reference",key + "-T1"); values.put("username","package_technician"); values.put("password",password);
    values.put("adminUsername","package_coordinator"); values.put("adminPassword",adminPassword);
    Files.createFile(state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(values));
    var tx = new TransactionTemplate(transactionManager);
    Long frozen = null;
    boolean restored = false;
    long deadline = System.nanoTime() + Duration.ofMinutes(25).toNanos();
    Path release = Path.of(state + ".release"), freeze = Path.of(state + ".freeze");
    while (!Files.exists(release) && System.nanoTime() < deadline) {
      if (frozen == null && Files.exists(freeze)) {
        var current = packages.get(productId,null,actor);
        assertThat(current.packaging().parentBaseQuantity()).isEqualByComparingTo("150");
        assertThat(current.items().getFirst().quantityPerProduct()).isEqualByComparingTo("1");
        var person = new Recipient(1L,taskId,1,101L,"王工","wang","FILL",List.of("PACKAGE"),1L,"todo","CONFIRMED","OPEN",null,"工程部","leader","王总",null,0,0,null,true,null,null,"PACKAGE-BROWSER",null);
        frozen = tx.execute(status -> versions.freeze(repository.lockProduct(productId).orElseThrow(),person,current.expectedVersion(),101L).getId());
        long frozenId = frozen;
        // 本地送审可见性夹具，不发送 OA；真实传输和回调在部署环境另行验收。
        var frozenVersion = repository.findVersion(frozenId).orElseThrow();
        String snapshot = codec.versionContentJson(frozenVersion, codec.readReferenceSnapshot(frozenVersion.getReferenceSnapshotJson()),
            repository.findPackageItems(frozenId), List.of(), List.of());
        jdbc.update("INSERT INTO lp_quote_tech_submission(task_id,product_id,technical_version_id,submission_round,request_id,expected_task_version,expected_product_version,content_schema_version,content_fingerprint,content_snapshot_json,summary_json,assignee_user_id,submitted_by,prepared_at,submission_status,module_types_json,recipient_id) VALUES(?,?,?,1,?,0,0,2,?,?,'[]',101,101,NOW(),'PREPARED','[\"PACKAGE\"]',(SELECT id FROM lp_quote_tech_oa_recipient WHERE task_id=? AND active_flag=1))",
            taskId, productId, frozenId, "PACKAGE-LOCAL-" + frozenId, frozenVersion.getContentFingerprint(), snapshot, taskId);
        jdbc.update("UPDATE lp_quote_tech_submission SET submission_status='SENDING' WHERE technical_version_id=?", frozenId);
        jdbc.update("UPDATE lp_quote_tech_submission SET submission_status='SENT',sent_at=NOW() WHERE technical_version_id=?", frozenId);
        jdbc.update("UPDATE lp_quote_tech_module SET module_status='SUBMITTED' WHERE product_id=? AND module_type='PACKAGE'", productId);
        jdbc.update("UPDATE lp_quote_tech_task SET task_status='SUBMITTED' WHERE id=?", taskId);
        jdbc.update("UPDATE lp_quote_tech_oa_recipient SET submission_round=1,todo_status='SUBMITTED',latest_submission_id=(SELECT id FROM lp_quote_tech_submission WHERE technical_version_id=?) WHERE task_id=?", frozenId, taskId);
        jdbc.update("UPDATE lp_quote_tech_data_version SET version_status='SUBMITTED' WHERE id=?", frozenId);
        jdbc.update("UPDATE lp_quote_tech_data_version SET version_status='APPROVED' WHERE id=?", frozenId);
        jdbc.update("UPDATE lp_quote_tech_product SET effective_version_id=?,latest_submitted_version_id=? WHERE id=?", frozenId, frozenId, productId);
        Files.writeString(Path.of(state + ".frozen"),json.writeValueAsString(java.util.Map.of("frozenVersionId",frozenId)));
      }
      if (frozen != null && !restored && Files.exists(Path.of(state + ".return"))) {
        long frozenId = frozen;
        var person = new Recipient(1L,taskId,1,101L,"王工","wang","FILL",List.of("PACKAGE"),1L,"todo","CONFIRMED","OPEN",null,"工程部","leader","王总",null,0,0,null,true,null,null,"PACKAGE-BROWSER",null);
        jdbc.update("UPDATE lp_quote_tech_submission SET submission_status='RETURNED' WHERE technical_version_id=?", frozenId);
        jdbc.update("UPDATE lp_quote_tech_oa_recipient SET todo_status='OPEN' WHERE task_id=?", taskId);
        recipients.refreshTask(taskId);
        tx.executeWithoutResult(status -> versions.restore(repository.lockProduct(productId).orElseThrow(),person,frozenId,101L));
        Files.writeString(Path.of(state + ".restored"),json.writeValueAsString(java.util.Map.of("frozenVersionId",frozenId)));
        restored = true;
      }
      Thread.sleep(250);
    }
    assertThat(Files.exists(release)).as("browser finished").isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(frozen).isNotNull();
    var current = packages.get(productId,null,actor);
    var history = packages.get(productId,frozen,actor);
    assertThat(current.packaging().parentBaseQuantity()).isEqualByComparingTo("6");
    assertThat(current.items().getFirst().quantity()).isEqualByComparingTo("2");
    assertThat(current.items().getFirst().quantityPerProduct()).isEqualByComparingTo("0.33333333");
    assertThat(history.packaging().parentBaseQuantity()).isEqualByComparingTo("150");
    assertThat(history.items().getFirst().quantityPerProduct()).isEqualByComparingTo("1");
    var evidence = java.util.Map.of("draft",current,"frozen",history,"quotationItem",item,"month",accountingMonth());
    Files.writeString(Path.of(state + ".verified"),json.writeValueAsString(evidence));
  }
}
