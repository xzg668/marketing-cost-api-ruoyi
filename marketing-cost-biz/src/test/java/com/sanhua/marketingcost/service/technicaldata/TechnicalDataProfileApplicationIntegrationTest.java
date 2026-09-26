package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.controller.TechnicalDataProfileController;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileUpdateRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProductResponse;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
@DisplayName("TW-07 产品来源只读、单件费用与提交完整性：真实 MySQL")
class TechnicalDataProfileApplicationIntegrationTest extends BomMapperTestBase {
  private static final java.util.concurrent.atomic.AtomicLong IDS = new java.util.concurrent.atomic.AtomicLong(9_770_000);
  private static final TechnicalDataActor TECH = actor(101L, "王工", "technical:data:task:list", "technical:data:task:edit");
  private static final TechnicalDataActor ADMIN = actor(1L, "管理员", "technical:data:admin:operate");
  @Autowired private TechnicalDataTaskApplicationService tasks;
  @Autowired private TechnicalDataProfileApplicationService profiles;
  @Autowired private TechnicalDataSubmissionValidationService validation;
  @Autowired private TechnicalDataSubmissionSnapshotService snapshots;
  @Autowired private com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository recipients;
  @Autowired private JdbcTemplate jdbc;

  @Test void optionalOaFieldsDoNotDefaultChoicesOrBlockSavingCompleteProductData() {
    var product = fixture();
    assertThat(product.productName()).isNull();
    assertThat(product.sourceSpec()).isNull();
    assertThat(product.annualVolume()).isNull();
    assertThat(product.annualVolumeUnit()).isNull();
    assertThat(product.profile().productProperty()).isNull();
    assertThat(product.profile().sourceFirstQuote()).isNull();
    assertThat(product.profile().hasAdditionalFees()).isNull();
    assertThat(product.profile().unitMouldFee()).isNull();
    var controller = new TechnicalDataProfileController(profiles, () -> TECH);
    var result = controller.save(product.id(), update(false, null, "0.300", null, 0));
    assertThat(result.isSuccess()).isTrue();
    var saved = result.getData();
    assertThat(saved.productModel()).isEqualTo("OA-MODEL");
    assertThat(saved.hasAdditionalFees()).isFalse();
    assertThat(saved.unitMouldFee()).isEqualTo("0.3");
    assertThat(saved.unitToolingFee()).isEqualTo("/");
    assertThat(saved.unitCertificationFee()).isEqualTo("/");
    assertThat(saved.expectedVersion()).isOne();
    var reloaded = tasks.detail(taskId(product), TECH).products().getFirst();
    assertThat(reloaded.profile()).isEqualTo(saved);
    assertThat(reloaded.sourceSnapshotJson()).isEqualTo(product.sourceSnapshotJson());
    assertThat(reloaded.modules().stream().filter(m -> "PROFILE".equals(m.moduleType())).findFirst().orElseThrow().moduleStatus()).isEqualTo("READY");
    assertThat(jdbc.queryForMap("SELECT version_status,product_model,product_property,created_by FROM lp_quote_tech_data_version WHERE id=?", saved.versionId()))
        .containsEntry("version_status", "DRAFT").containsEntry("product_model", "OA-MODEL")
        .containsEntry("product_property", "非标品").containsEntry("created_by", 101L);
  }

  @Test void requiredChoicesAndEveryFeeAreValidatedBeforeAnyDatabaseWrite() throws Exception {
    var product = fixture();
    var noProperty = update(false, null, null, null, 0); noProperty.setProductProperty(null);
    invalid(product.id(), noProperty, "产品属性");
    invalid(product.id(), update(null, null, null, null, 0), "请选择是否含");
    invalid(product.id(), update(true, "0.1", null, "/", 0), "模具费请填写");
    for (String value : new String[]{"0", "-1", "NaN", "1e2", "1.1234567", "1000000000000"}) {
      invalid(product.id(), update(false, value, null, null, 0), "工装费");
    }
    var readonly = new ObjectMapper().readValue("""
        {"productProperty":"非标品","hasAdditionalFees":false,"expectedVersion":0,
         "productModel":"OA-MODEL","newProduct":false,"annualVolume":123}
        """, TechnicalDataProfileUpdateRequest.class);
    invalid(product.id(), readonly, "OA只读字段");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_data_version WHERE product_id=?", Integer.class, product.id())).isZero();
  }

  @Test void switchingFeeChoicePreservesExplicitAmountsAndCreatesNoSecondDraft() {
    var product = fixture();
    var first = profiles.save(product.id(), update(true, "0.10", "0.30", "0.05", 0), TECH);
    var second = profiles.save(product.id(), update(false, first.unitToolingFee(), first.unitMouldFee(), first.unitCertificationFee(), 1), TECH);
    assertThat(second.versionId()).isEqualTo(first.versionId());
    assertThat(second.hasAdditionalFees()).isFalse();
    assertThat(second.unitToolingFee()).isEqualTo("0.1");
    assertThat(second.unitMouldFee()).isEqualTo("0.3");
    assertThat(second.unitCertificationFee()).isEqualTo("0.05");
    assertThat(tasks.detail(taskId(product), TECH).products().getFirst().profile()).isEqualTo(second);
  }

  @Test void staleBrowserCannotOverwriteAnotherSavedFee() {
    var product = fixture();
    var first = profiles.save(product.id(), update(true, "0.1", "0.3", "/", 0), TECH);
    assertThatThrownBy(() -> profiles.save(product.id(), update(true, "0.9", "0.8", "/", 0), TECH))
        .isInstanceOfSatisfying(TechnicalDataTaskException.class, error -> assertThat(error.code()).isEqualTo(TechnicalDataTaskErrorCode.VERSION_CONFLICT));
    assertThat(tasks.detail(taskId(product), TECH).products().getFirst().profile()).isEqualTo(first);
  }

  @Test
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  void simultaneousFirstSavesCreateExactlyOneDraft() throws Exception {
    var product = fixture();
    var ready = new java.util.concurrent.CountDownLatch(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    int succeeded = 0, conflicts = 0;
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 2; i++) {
        final String amount = i == 0 ? "0.1" : "0.2";
        futures.add(executor.submit(() -> {
          ready.countDown(); start.await();
          return profiles.save(product.id(), update(true, amount, "/", "/", 0), TECH);
        }));
      }
      assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); start.countDown();
      for (var future : futures) {
        try { future.get(10, java.util.concurrent.TimeUnit.SECONDS); succeeded++; }
        catch (java.util.concurrent.ExecutionException error) {
          assertThat(error.getCause()).isInstanceOfSatisfying(TechnicalDataTaskException.class,
              conflict -> assertThat(conflict.code()).isEqualTo(TechnicalDataTaskErrorCode.VERSION_CONFLICT));
          conflicts++;
        }
      }
    }
    assertThat(succeeded).isOne(); assertThat(conflicts).isOne();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_data_version WHERE product_id=?", Integer.class, product.id())).isOne();
  }

  @Test void onlyAssigneeOrExplicitAdminMayFillAndAdminDoesNotTakeOwnership() {
    var product = fixture();
    var stranger = actor(102L, "李工", "technical:data:task:edit");
    assertThatThrownBy(() -> profiles.save(product.id(), update(false, null, null, null, 0), stranger))
        .isInstanceOfSatisfying(TechnicalDataTaskException.class, error -> assertThat(error.code()).isEqualTo(TechnicalDataTaskErrorCode.FORBIDDEN));
    var saved = profiles.save(product.id(), update(false, null, null, null, 0), ADMIN);
    assertThat(jdbc.queryForObject("SELECT updated_by FROM lp_quote_tech_data_version WHERE id=?", Long.class, saved.versionId())).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT assignee_user_id FROM lp_quote_tech_module WHERE product_id=? AND module_type='PROFILE'", Long.class, product.id())).isEqualTo(101);
  }

  @Test void ownCompleteProfileCanSubmitWhileIndependentOtherPersonsModuleIsMissing() {
    var product = fixture();
    jdbc.update("UPDATE lp_quote_tech_module SET required_flag=1,assignee_user_id=102,assignee_name='李工',source_availability='MISSING',module_status='PENDING' WHERE product_id=? AND module_type='SALARY'", product.id());
    profiles.save(product.id(), update(true, "0.1", "0.3", "0.05", 0), TECH);
    assertThat(validation.validate(taskId(product), 101L, TECH).valid()).isTrue();
    var latest = tasks.detail(taskId(product), TECH);
    var frozen = snapshots.prepare(taskId(product), "PROFILE-" + product.id(), latest.taskVersion(), latest.products().getFirst().rowVersion(), recipients.current(taskId(product)).getFirst(), TECH);
    assertThat(frozen.getContentSnapshotJson()).contains("unitMouldFee", "0.3").doesNotContain("\"moduleType\":\"SALARY\"");
  }

  @Test void incompletePersistedFeeCannotPassSubmissionEvenForAdmin() {
    var product = fixture();
    var saved = profiles.save(product.id(), update(false, null, null, null, 0), TECH);
    // Simulates a pre-upgrade incomplete draft; submit must validate contents, not trust READY.
    jdbc.update("UPDATE lp_quote_tech_data_version SET product_fees_json=NULL WHERE id=?", saved.versionId());
    for (var actor : new TechnicalDataActor[]{TECH, ADMIN}) {
      var checked = validation.validate(taskId(product), 101L, actor);
      assertThat(checked.valid()).isFalse();
      assertThat(checked.issues()).anyMatch(issue -> "hasAdditionalFees".equals(issue.field()));
      var latest = tasks.detail(taskId(product), actor);
      assertThatThrownBy(() -> snapshots.prepare(taskId(product), "MISSING-" + actor.userId(), latest.taskVersion(), latest.products().getFirst().rowVersion(), recipients.current(taskId(product)).getFirst(), actor))
          .hasMessageContaining("本人负责模块");
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_submission WHERE task_id=?", Integer.class, taskId(product))).isZero();
  }

  @Test void upgradeResetsOnlyUnansweredDraftReadinessAndPreservesFrozenVersions() throws Exception {
    var unfinished = fixture(); var reviewed = fixture();
    var draft = profiles.save(unfinished.id(), update(false, null, null, null, 0), TECH);
    jdbc.update("UPDATE lp_quote_tech_data_version SET product_fees_json=NULL WHERE id=?", draft.versionId());
    profiles.save(reviewed.id(), update(true, "0.1", "0.3", "0.05", 0), TECH);
    var task = tasks.detail(taskId(reviewed), TECH);
    var frozen = snapshots.prepare(task.id(), "UPGRADE-" + reviewed.id(), task.taskVersion(), task.products().getFirst().rowVersion(), recipients.current(task.id()).getFirst(), TECH);
    String original = jdbc.queryForObject("SELECT content_snapshot_json FROM lp_quote_tech_submission WHERE id=?", String.class, frozen.getId());
    var script = new org.springframework.core.io.ClassPathResource("db/V268__technical_profile_completeness.sql");
    try (var input = script.getInputStream()) { jdbc.update(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
    assertThat(jdbc.queryForObject("SELECT module_status FROM lp_quote_tech_module WHERE product_id=? AND module_type='PROFILE'", String.class, unfinished.id())).isEqualTo("EDITING");
    assertThat(jdbc.queryForObject("SELECT product_property FROM lp_quote_tech_data_version WHERE id=?", String.class, draft.versionId())).isEqualTo("非标品");
    assertThat(jdbc.queryForObject("SELECT module_status FROM lp_quote_tech_module WHERE product_id=? AND module_type='PROFILE'", String.class, reviewed.id())).isEqualTo("FROZEN");
    assertThat(jdbc.queryForObject("SELECT content_snapshot_json FROM lp_quote_tech_submission WHERE id=?", String.class, frozen.getId())).isEqualTo(original);
  }

  private Long taskId(TechnicalDataProductResponse product) {
    return jdbc.queryForObject("SELECT task_id FROM lp_quote_tech_product WHERE id=?", Long.class, product.id());
  }

  private TechnicalDataProductResponse fixture() {
    String key = "TW07-" + UUID.randomUUID();
    long itemId = IDS.incrementAndGet();
    String activeKey = "ITEM:" + itemId + ":MONTH:2026-09";
    jdbc.update("""
        INSERT INTO lp_quote_tech_task(task_no,oa_form_id,oa_form_item_id,oa_no,accounting_month,
          business_unit_type,applicable_org_code,assignee_user_id,assignee_name,active_flag,active_lock_key,
          oa_assignment_version,external_task_status)
        VALUES(?,77001,?,?,'2026-09','COMMERCIAL','210',101,'王工',1,?,1,'PUBLISHED')
        """, key, itemId, key, activeKey);
    Long taskId = jdbc.queryForObject("SELECT id FROM lp_quote_tech_task WHERE task_no=?", Long.class, key);
    jdbc.update("""
        INSERT INTO lp_quote_tech_product(task_id,oa_form_item_id,level_no,source_model,quote_no,accounting_month,
          source_snapshot_json,source_fingerprint,active_flag,active_lock_key,content_schema_version)
        VALUES(?,?,1,'OA-MODEL',?,'2026-09','{"sourceModel":"OA-MODEL"}',REPEAT('a',64),1,?,2)
        """, taskId, itemId, key, activeKey);
    Long productId = jdbc.queryForObject("SELECT id FROM lp_quote_tech_product WHERE task_id=?", Long.class, taskId);
    for (String type : TechnicalDataModuleType.orderedCodes()) {
      boolean required = "PROFILE".equals(type);
      jdbc.update("""
          INSERT INTO lp_quote_tech_module(product_id,module_type,required_flag,requirement_reason_code,
            requirement_reason,module_status,source_availability,assignee_user_id,assignee_name,source_checked_at,source_reference)
          VALUES(?,?,?,'ISOLATED_UNIT_FIXTURE','隔离单元场景构造',?,?,?,?,NOW(6),'ISOLATED_TEST')
          """, productId, type, required ? 1 : 0, required ? "PENDING" : "NOT_REQUIRED", required ? "MISSING" : "AVAILABLE", required ? 101L : null, required ? "王工" : null);
    }
    jdbc.update("INSERT INTO lp_oa_integration_message(source_system,environment,direction,request_id,interface_type,schema_version,occurred_at,raw_payload,payload_hash,allowed_business_units) VALUES('TEST','UNIT','OUTBOUND',?,'TASK_DISPATCH',1,NOW(3),'{}',REPEAT('b',64),'COMMERCIAL')", key);
    Long messageId = jdbc.queryForObject("SELECT id FROM lp_oa_integration_message WHERE request_id=?", Long.class, key);
    jdbc.update("INSERT INTO lp_quote_tech_oa_recipient(task_id,assignment_version,assignee_user_id,assignee_name,external_user_id,module_types_json,outbound_message_id,external_task_id,dispatch_status,todo_status,active_flag,leader_external_id) VALUES(?,1,101,'王工','oa-tech-1','[\"PROFILE\"]',?,'PROFILE-TODO','CONFIRMED','OPEN',1,'oa-leader-1')", taskId, messageId);
    return tasks.detail(taskId, TECH).products().getFirst();
  }

  private TechnicalDataProfileUpdateRequest update(Boolean included, String tooling, String mould, String certification, int version) {
    var request = new TechnicalDataProfileUpdateRequest();
    request.setProductProperty("非标品"); request.setHasAdditionalFees(included);
    request.setUnitToolingFee(tooling); request.setUnitMouldFee(mould); request.setUnitCertificationFee(certification);
    request.setExpectedVersion(version); return request;
  }

  private void invalid(Long productId, TechnicalDataProfileUpdateRequest request, String message) {
    assertThatThrownBy(() -> profiles.save(productId, request, TECH))
        .isInstanceOfSatisfying(TechnicalDataTaskException.class, error -> {
          assertThat(error.code()).isEqualTo(TechnicalDataTaskErrorCode.INVALID_REQUEST);
          assertThat(error.getMessage()).contains(message);
        });
  }
  private static TechnicalDataActor actor(Long id, String name, String... permissions) { return new TechnicalDataActor(id, name, Set.of(permissions)); }
}
