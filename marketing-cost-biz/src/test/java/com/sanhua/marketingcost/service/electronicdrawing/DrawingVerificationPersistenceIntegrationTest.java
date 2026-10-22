package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.DrawingVerificationTask;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationCodec;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationFixtures;
import com.sanhua.marketingcost.mapper.DrawingVerificationResultMapper;
import com.sanhua.marketingcost.mapper.DrawingVerificationTaskMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DrawingVerificationPersistenceIntegrationTest extends BomMapperTestBase {
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0);
  @Autowired DrawingVerificationTaskMapper tasks;
  @Autowired DrawingVerificationResultMapper results;
  @Autowired DrawingVerificationStore store;
  @Autowired DrawingVerificationCodec codec;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  private Long legacyId;
  private Map<String, Object> legacyBefore;
  private List<Map<String, Object>> triggersBefore;

  @BeforeAll
  void migrateOverExistingHistory() throws Exception {
    jdbc.update(
        """
        INSERT INTO lp_quote_bom_supplement_version
        (preparation_id,oa_no,oa_form_item_id,quote_product_code,product_type,supplement_scope,
         bom_source,version_no,version_status,active_flag,source_file_name,source_file_sha256)
        VALUES (338001,'EDP-LEGACY',338001,'OLD-P','NON_BARE','NON_BARE_FULL_BOM',
                'ELECTRONIC_DRAWING_EXCEL',1,'APPROVED',1,'old.xlsx',?)
        """,
        "a".repeat(64));
    legacyId =
        jdbc.queryForObject(
            "SELECT id FROM lp_quote_bom_supplement_version WHERE oa_no='EDP-LEGACY'", Long.class);
    legacyBefore =
        jdbc.queryForMap("SELECT * FROM lp_quote_bom_supplement_version WHERE id=?", legacyId);
    triggersBefore =
        jdbc.queryForList(
            "SELECT TRIGGER_NAME,ACTION_STATEMENT FROM information_schema.triggers "
                + "WHERE trigger_schema=DATABASE() ORDER BY TRIGGER_NAME");
    migrate();
  }

  private void migrate() throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V338__drawing_verification_evidence.sql"),
        "/tmp/V338.sql");
    var applied =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "mysql --default-character-set=utf8mb4 -uroot -p"
                + MYSQL.getPassword()
                + " "
                + MYSQL.getDatabaseName()
                + " < /tmp/V338.sql");
    assertThat(applied.getExitCode()).withFailMessage(applied.getStderr()).isZero();
  }

  @Test
  void rawPayloadAndScopeRoundTripWithoutChangingOriginalTypes() {
    var task = task();
    var payload =
        codec.parse(DrawingVerificationFixtures.initial(task.getRequestId()).toPrettyString());
    var result =
        store.append(task, DrawingVerificationStore.INITIAL, payload, null, "RECEIVED", NOW);
    var saved = results.findById(result.getId());
    assertThat(saved.getRawSummaryJson()).isEqualTo(payload.rawJson());
    assertThat(saved.getDataJson()).isEqualTo(payload.dataJson());
    assertThat(saved.getPlmDataJson()).isEqualTo(payload.plmDataJson());
    assertThat(saved.getContentFingerprint()).isEqualTo(payload.contentFingerprint());
    assertThat(saved.getReceivedAt()).isEqualTo(NOW);
    var current = tasks.selectById(task.getId());
    assertThat(current.getCurrentResultId()).isEqualTo(result.getId());
    assertThat(current.getOaFormItemId()).isEqualTo(task.getOaFormItemId());
    assertThat(current.getAccountingMonth()).isEqualTo("2026-10");
    assertThat(current.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(current.getMaterialOrgCode()).isEqualTo("COMMERCIAL");
    assertThat(current.getPriceOrgCode()).isEqualTo("210");
    assertThat(current.getRowVersion()).isEqualTo(1);
    assertThat(current.getConsumptionState()).isEqualTo("PENDING");
  }

  @Test
  void confirmationLinksInitialPlmAndDoesNotEraseItsOrderedOriginalList() {
    var task = task();
    var initial =
        store.append(
            task,
            DrawingVerificationStore.INITIAL,
            codec.parse(DrawingVerificationFixtures.initial(task.getRequestId()).toString()),
            null,
            "WAIT_REVIEW",
            NOW);
    var confirmed =
        store.append(
            tasks.selectById(task.getId()),
            DrawingVerificationStore.MANUAL_CONFIRMATION,
            codec.parse(DrawingVerificationFixtures.confirmed(task.getRequestId()).toString()),
            initial.getId(),
            "RECEIVED",
            NOW.plusMinutes(1));
    assertThat(confirmed.getPlmDataJson()).isEqualTo("[]");
    assertThat(confirmed.getPlmSourceResultId()).isEqualTo(initial.getId());
    assertThat(results.findById(initial.getId()).getPlmDataJson())
        .isEqualTo(initial.getPlmDataJson());
    assertThat(results.findHistory(task.getId())).hasSize(2);
    assertThat(tasks.selectById(task.getId()).getDifferenceCount()).isEqualTo(2);
    assertThat(confirmed.getConfirmedAtMs()).isEqualTo(1792550100000L);
  }

  @Test
  void uniqueTaskKeysPreventDuplicateRequestsAndActiveScopes() {
    var first = task();
    var duplicateRequest = unsavedTask();
    duplicateRequest.setRequestId(first.getRequestId());
    assertThatThrownBy(() -> tasks.insert(duplicateRequest))
        .isInstanceOf(DuplicateKeyException.class);
    var duplicateScope = unsavedTask();
    duplicateScope.setActiveScopeKey(first.getActiveScopeKey());
    assertThatThrownBy(() -> tasks.insert(duplicateScope))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(tasks.findActive(first.getActiveScopeKey()).getId()).isEqualTo(first.getId());
  }

  @Test
  void externalTaskCollisionRollsBackTheSecondResult() {
    var first = task();
    var second = task();
    var payload = codec.parse(DrawingVerificationFixtures.initial(first.getRequestId()).toString());
    store.append(first, DrawingVerificationStore.INITIAL, payload, null, "RECEIVED", NOW);
    assertThatThrownBy(
            () ->
                store.append(
                    second, DrawingVerificationStore.INITIAL, payload, null, "RECEIVED", NOW))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(results.findHistory(second.getId())).isEmpty();
    assertThat(tasks.selectById(second.getId()).getExternalTaskId()).isNull();
    assertThat(tasks.findExternal(first.getSourceSystem(), first.getRequestId()).getId())
        .isEqualTo(first.getId());
  }

  @Test
  void staleRevisionRollsBackNewConfirmationEvidence() {
    var stale = task();
    var initial =
        store.append(
            stale,
            DrawingVerificationStore.INITIAL,
            codec.parse(DrawingVerificationFixtures.initial(stale.getRequestId()).toString()),
            null,
            "WAIT_REVIEW",
            NOW);
    assertThatThrownBy(
            () ->
                store.append(
                    stale,
                    DrawingVerificationStore.MANUAL_CONFIRMATION,
                    codec.parse(
                        DrawingVerificationFixtures.confirmed(stale.getRequestId()).toString()),
                    initial.getId(),
                    "RECEIVED",
                    NOW))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(results.findHistory(stale.getId())).hasSize(1);
    assertThat(tasks.selectById(stale.getId()).getCurrentResultId()).isEqualTo(initial.getId());
  }

  @Test
  void duplicateEventsAndDirectMutationsCannotRewriteEvidence() {
    var task = task();
    var payload = codec.parse(DrawingVerificationFixtures.initial(task.getRequestId()).toString());
    var result =
        store.append(task, DrawingVerificationStore.INITIAL, payload, null, "RECEIVED", NOW);
    assertThatThrownBy(
            () ->
                store.append(
                    tasks.selectById(task.getId()),
                    DrawingVerificationStore.INITIAL,
                    payload,
                    null,
                    "RECEIVED",
                    NOW))
        .isInstanceOf(DuplicateKeyException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE lp_drawing_verification_result SET data_json='{}' WHERE id=?",
                    result.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM lp_drawing_verification_result WHERE id=?", result.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("immutable");
    assertThat(results.findHistory(task.getId())).hasSize(1);
    assertThat(results.findById(result.getId()).getRawSummaryJson()).isEqualTo(payload.rawJson());
  }

  @Test
  void explicitTransactionRollbackIncludesEvidenceAndCurrentPointer() {
    var task = task();
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .executeWithoutResult(
                        status -> {
                          store.append(
                              task,
                              DrawingVerificationStore.INITIAL,
                              codec.parse(
                                  DrawingVerificationFixtures.initial(task.getRequestId())
                                      .toString()),
                              null,
                              "RECEIVED",
                              NOW);
                          throw new IllegalStateException("rollback fixture");
                        }))
        .hasMessage("rollback fixture");
    assertThat(results.findHistory(task.getId())).isEmpty();
    assertThat(tasks.selectById(task.getId()).getCurrentResultId()).isNull();
  }

  @Test
  void crossTaskSourceAndCurrentPointerAreRejected() {
    var first = task();
    var second = task();
    var source =
        store.append(
            first,
            DrawingVerificationStore.INITIAL,
            codec.parse(DrawingVerificationFixtures.initial(first.getRequestId()).toString()),
            null,
            "WAIT_REVIEW",
            NOW);
    assertThatThrownBy(
            () ->
                store.append(
                    second,
                    DrawingVerificationStore.MANUAL_CONFIRMATION,
                    codec.parse(
                        DrawingVerificationFixtures.confirmed(second.getRequestId()).toString()),
                    source.getId(),
                    "RECEIVED",
                    NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("同任务");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE lp_drawing_verification_task SET current_result_id=? WHERE id=?",
                    source.getId(),
                    second.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThat(results.findHistory(second.getId())).isEmpty();
  }

  @Test
  void concurrentInitialWritersProduceExactlyOneCommittedResult() throws Exception {
    var task = task();
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var jobs =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  index ->
                      executor.submit(
                          () -> {
                            start.await(10, TimeUnit.SECONDS);
                            try {
                              store.append(
                                  task,
                                  DrawingVerificationStore.INITIAL,
                                  codec.parse(
                                      DrawingVerificationFixtures.initial(task.getRequestId())
                                          .toString()),
                                  null,
                                  "RECEIVED",
                                  NOW);
                              return true;
                            } catch (DataAccessException exception) {
                              return false;
                            }
                          }))
              .toList();
      start.countDown();
      int committed = 0;
      for (var job : jobs) if (job.get(20, TimeUnit.SECONDS)) committed++;
      assertThat(committed).isEqualTo(1);
    }
    assertThat(results.findHistory(task.getId())).hasSize(1);
    assertThat(tasks.selectById(task.getId()).getRowVersion()).isEqualTo(1);
  }

  @Test
  void forwardMigrationPreservesOldVersionFieldsAndExistingTriggers() throws Exception {
    migrate();
    var after =
        jdbc.queryForMap("SELECT * FROM lp_quote_bom_supplement_version WHERE id=?", legacyId);
    assertThat(after.get("drawing_result_id")).isNull();
    assertThat(after).containsAllEntriesOf(legacyBefore);
    var triggers =
        jdbc.queryForList(
            "SELECT TRIGGER_NAME,ACTION_STATEMENT FROM information_schema.triggers "
                + "WHERE trigger_schema=DATABASE() ORDER BY TRIGGER_NAME");
    assertThat(triggers).containsAll(triggersBefore);
  }

  private DrawingVerificationTask task() {
    var task = unsavedTask();
    assertThat(tasks.insert(task)).isEqualTo(1);
    return task;
  }

  private DrawingVerificationTask unsavedTask() {
    String key = "EDP-" + UUID.randomUUID();
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,'COMMERCIAL')", key);
    Long formId = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update(
        "INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type)"
            + " VALUES(?,'EDP-P','测试产品','COMMERCIAL')",
        formId);
    Long itemId =
        jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, formId);
    var task = new DrawingVerificationTask();
    task.setSourceSystem("PDF_V2");
    task.setRequestId(key);
    task.setOaNo(key);
    task.setOaFormItemId(itemId);
    task.setBusinessUnitType("COMMERCIAL");
    task.setMaterialOrgCode("COMMERCIAL");
    task.setPriceOrgCode("210");
    task.setAccountingMonth("2026-10");
    task.setDrawingNo("DRAW-01");
    task.setInputSnapshotJson("{\"materialNo\":\"EDP-P\",\"model\":\"M\"}");
    task.setActiveScopeKey(DrawingVerificationCodec.sha256(key));
    task.setTaskState("REQUESTING");
    task.setConsumptionState("NOT_READY");
    task.setRowVersion(0);
    task.setCreatedAt(NOW);
    task.setUpdatedAt(NOW);
    return task;
  }
}
