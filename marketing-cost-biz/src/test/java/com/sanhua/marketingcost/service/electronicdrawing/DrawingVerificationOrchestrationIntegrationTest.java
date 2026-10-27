package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.integration.drawing.*;
import com.sanhua.marketingcost.mapper.DrawingVerificationResultMapper;
import com.sanhua.marketingcost.mapper.DrawingVerificationTaskMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.QuoteCostingWorkbenchService;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomGateway;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DrawingVerificationOrchestrationIntegrationTest extends BomMapperTestBase {
  @Autowired DrawingVerificationService service;
  @Autowired DrawingVerificationTaskMapper tasks;
  @Autowired DrawingVerificationResultMapper results;
  @Autowired DrawingVerificationCodec codec;
  @Autowired OaFormItemMapper items;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactions;
  @Autowired QuoteCostingWorkbenchService workbench;
  @MockBean ElectronicDrawingWorkflowContextPort contexts;
  @MockBean CurrentU9BomGateway u9;
  @MockBean DrawingVerificationClient client;
  private Long itemId, formId;
  private String oaNo, material, model;
  private final String month = CostPricingPeriodUtils.currentPricingMonth();

  @BeforeAll
  void migrate() throws Exception {
    // 该共享测试基线未包含 U9 月度身份列；使用真实迁移补齐本用例所需结构。
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND"
                + " table_name='lp_quote_bom_monthly_snapshot' AND"
                + " column_name='snapshot_identity_key'",
            Integer.class)
        == 0) {
      MYSQL.copyFileToContainer(
          MountableFile.forClasspathResource("/db/V233__u9_monthly_first_query_snapshot.sql"),
          "/tmp/EDP04-V233.sql");
      var prerequisite =
          MYSQL.execInContainer(
              "sh",
              "-c",
              "mysql --default-character-set=utf8mb4 -uroot -p"
                  + MYSQL.getPassword()
                  + " "
                  + MYSQL.getDatabaseName()
                  + " < /tmp/EDP04-V233.sql");
      assertThat(prerequisite.getExitCode()).withFailMessage(prerequisite.getStderr()).isZero();
    }
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V338__drawing_verification_evidence.sql"),
        "/tmp/EDP04-V338.sql");
    var applied =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "mysql --default-character-set=utf8mb4 -uroot -p"
                + MYSQL.getPassword()
                + " "
                + MYSQL.getDatabaseName()
                + " < /tmp/EDP04-V338.sql");
    assertThat(applied.getExitCode()).withFailMessage(applied.getStderr()).isZero();
  }

  @BeforeEach
  void fixture() {
    material = "EDP04-" + UUID.randomUUID();
    model = "MODEL-" + material;
    oaNo = material;
    jdbc.update(
        "INSERT INTO oa_form(oa_no,accounting_period_month,business_unit_type)"
            + " VALUES(?,?,'COMMERCIAL')",
        oaNo,
        month);
    formId = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, oaNo);
    jdbc.update(
        "INSERT INTO"
            + " oa_form_item(oa_form_id,material_no,sunl_model,product_name,customer_drawing,business_unit_type)"
            + " VALUES(?,?,?,'热力膨胀阀','YCQB05L89','COMMERCIAL')",
        formId,
        material,
        model);
    itemId =
        jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, formId);
    jdbc.update(
        "INSERT INTO"
            + " lp_material_master_raw(material_code,organization_code,material_name,material_model,drawing_no,active_flag,source_type,import_batch_id)"
            + " VALUES(?,'COMMERCIAL','热力膨胀阀',?,'YCQB05L89',1,'U9_API','EDP-04')",
        material,
        model);
    when(contexts.load(any(), any(), any(), any())).thenAnswer(call -> scope());
    when(u9.read(any())).thenReturn(CurrentU9BomResult.notFound("explicit absence"));
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              var query = (DrawingVerificationClient.Query) call.getArgument(0);
              return codec.parse(
                  DrawingVerificationFixtures.initial("external-" + query.requestId()).toString());
            })
        .when(client)
        .verify(any());
  }

  @Test
  void zeroDifferencesAreAdoptedAndRepeatedStartsAndReadsDoNotPostAgain() {
    var first = service.start(scope(), null).orElseThrow();
    assertThat(first.getTaskState()).isEqualTo("RECEIVED");
    assertThat(first.getConsumptionState()).isEqualTo("PENDING");
    assertThat(results.findById(first.getCurrentResultId()).getRawSummaryJson())
        .contains("reviewRequired", "incomplete");
    assertThat(service.start(scope(), null).orElseThrow().getId()).isEqualTo(first.getId());
    assertThat(service.current(scope()).orElseThrow().getId()).isEqualTo(first.getId());
    verify(client, times(1)).verify(any());
    assertThat(taskCount()).isEqualTo(1);
  }

  @Test
  void materialCodeIgnoresConflictingQuoteModelSpecificationAndDrawingBeforePosting() {
    jdbc.update(
        "UPDATE oa_form_item SET"
            + " sunl_model='WRONG-MODEL',spec='WRONG-SPEC',customer_drawing='WRONG-DRAW' WHERE"
            + " id=?",
        itemId);
    var task = service.start(scope(), null).orElseThrow();
    assertThat(task.getDrawingNo()).isEqualTo("YCQB05L89-MX");
    verify(client).verify(argThat(query -> "YCQB05L89-MX".equals(query.drawingNo())));
    assertThat(task.getTaskState()).isEqualTo("RECEIVED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"SPEC-A", "UNKNOWN", ""})
  void noMaterialCodePostsTheDrawingFromExactSpecificationOrModelFallback(String specification) {
    jdbc.update(
        "UPDATE lp_material_master_raw SET material_spec='SPEC-A' WHERE material_code=?", material);
    if ("SPEC-A".equals(specification)) {
      jdbc.update(
          "INSERT INTO"
              + " lp_material_master_raw(material_code,organization_code,material_name,material_model,material_spec,drawing_no,active_flag,source_type,import_batch_id)"
              + " VALUES(?,'COMMERCIAL','另一规格',?,'SPEC-B','OTHER-DRAW',1,'U9_API','EDP04_PRIORITY')",
          material + "B",
          model);
    }
    jdbc.update(
        "UPDATE oa_form_item SET material_no=NULL,spec=?,customer_drawing='STALE-DRAW' WHERE id=?",
        specification,
        itemId);
    var task = service.start(scope(), null).orElseThrow();
    assertThat(task.getDrawingNo()).isEqualTo("YCQB05L89-MX");
    assertThat(service.start(scope(), null).orElseThrow().getId()).isEqualTo(task.getId());
    verify(client, times(1)).verify(argThat(query -> "YCQB05L89-MX".equals(query.drawingNo())));
  }

  @Test
  void differentMaterialsSharingOneDrawingPostOnceWithoutChangingTheQuoteMaterial() {
    jdbc.update(
        "INSERT INTO lp_material_master_raw(material_code,organization_code,material_name,"
            + "material_model,drawing_no,active_flag,source_type,import_batch_id)"
            + " VALUES(?,'COMMERCIAL','另一料号',?,'YCQB05L89',1,'U9_API','EDP04_SHARED')",
        material + "B",
        model);
    jdbc.update("UPDATE oa_form_item SET material_no=NULL WHERE id=?", itemId);
    var first = service.start(scope(), null).orElseThrow();
    assertThat(first.getDrawingNo()).isEqualTo("YCQB05L89-MX");
    assertThat(first.getTaskState()).isEqualTo("RECEIVED");
    assertThat(service.start(scope(), "YCQB05L89").orElseThrow().getId()).isEqualTo(first.getId());
    assertThat(items.selectById(itemId).getMaterialNo()).isNull();
    assertThat(taskCount()).isEqualTo(1);
    verify(client, times(1)).verify(argThat(query -> "YCQB05L89-MX".equals(query.drawingNo())));
  }

  @Test
  void differencesPersistPlmAndActualEditLinkBeforeWaitingForReview() {
    doAnswer(
            call -> {
              var data = DrawingVerificationFixtures.initial("review-" + UUID.randomUUID());
              data.put("differenceCount", 2);
              data.set("data", new ObjectMapper().createObjectNode());
              return codec.parse(data.toString());
            })
        .when(client)
        .verify(any());
    var task = service.start(scope(), null).orElseThrow();
    assertThat(task.getTaskState()).isEqualTo("WAIT_REVIEW");
    assertThat(task.getEditUrl()).isNotBlank();
    assertThat(task.getDifferenceCount()).isEqualTo(2);
    assertThat(task.getConsumptionState()).isEqualTo("NOT_READY");
    assertThat(results.findById(task.getCurrentResultId()).getPlmDataJson())
        .contains("V002", "V001");
    for (int index = 0; index < 3; index++)
      assertThat(service.start(scope(), null).orElseThrow().getId()).isEqualTo(task.getId());
    verify(client, times(1)).verify(any());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_quote_bom_supplement_version WHERE oa_form_item_id=?",
                Integer.class,
                itemId))
        .isZero();
  }

  @Test
  void u9AvailabilityAndEveryFailureStateHaveZeroVerificationCalls() {
    when(u9.read(any())).thenReturn(CurrentU9BomResult.available("U9", "V1", "batch", 2));
    assertThat(service.start(scope(), null)).isEmpty();
    for (var result :
        List.of(
            CurrentU9BomResult.error("error"),
            CurrentU9BomResult.timeout("timeout"),
            CurrentU9BomResult.dataEmpty("empty"),
            CurrentU9BomResult.organizationMismatch("wrong org"))) {
      when(u9.read(any())).thenReturn(result);
      assertThatThrownBy(() -> service.start(scope(), null)).hasMessageContaining("未确认缺失");
    }
    when(u9.read(any())).thenThrow(new IllegalStateException("database unavailable"));
    assertThatThrownBy(() -> service.start(scope(), null))
        .hasMessageContaining("database unavailable");
    verifyNoInteractions(client);
    assertThat(taskCount()).isZero();
  }

  @Test
  void absentOrAmbiguousProductDrawingNeverCreatesTaskOrCallsHttp() {
    jdbc.update(
        "UPDATE lp_material_master_raw SET drawing_no=NULL WHERE material_code=?", material);
    assertThatThrownBy(() -> service.start(scope(), null)).hasMessageContaining("未查到");
    jdbc.update(
        "UPDATE lp_material_master_raw SET drawing_no='YCQB05L89' WHERE material_code=?", material);
    jdbc.update(
        "INSERT INTO"
            + " lp_material_master_raw(material_code,organization_code,material_name,material_model,drawing_no,active_flag,source_type,import_batch_id)"
            + " VALUES(?,'COMMERCIAL','热力膨胀阀',?,'OTHER-DRAW',1,'U9_API','EDP-04')",
        material + "B",
        model);
    jdbc.update("UPDATE oa_form_item SET material_no=NULL WHERE id=?", itemId);
    assertThatThrownBy(() -> service.start(scope(), null)).hasMessageContaining("唯一图号");
    verifyNoInteractions(client);
    assertThat(taskCount()).isZero();
  }

  @Test
  void committedRequestingTaskSurvivesInterruptedProcessWithoutBlindReplay() {
    doThrow(new IllegalStateException("simulated process stop before save"))
        .when(client)
        .verify(any());
    assertThatThrownBy(() -> service.start(scope(), null)).hasMessageContaining("process stop");
    var task = service.current(scope()).orElseThrow();
    assertThat(task.getTaskState()).isEqualTo("REQUESTING");
    assertThat(task.getEditUrl()).isNull();
    assertThat(service.start(scope(), null).orElseThrow().getId()).isEqualTo(task.getId());
    verify(client, times(1)).verify(any());
  }

  @Test
  void transportFailureIsDurableAndSubsequentStartsDoNotRetryAutomatically() {
    doThrow(
            new DrawingVerificationException(
                DrawingVerificationException.Code.TIMEOUT, "核验超时", null, null, null))
        .when(client)
        .verify(any());
    var task = service.start(scope(), null).orElseThrow();
    assertThat(task.getTaskState()).isEqualTo("FAILED");
    assertThat(task.getErrorCode()).isEqualTo("TIMEOUT");
    assertThat(task.getCurrentResultId()).isNull();
    assertThat(task.getEditUrl()).isNull();
    assertThat(service.start(scope(), null).orElseThrow().getId()).isEqualTo(task.getId());
    verify(client, times(1)).verify(any());
  }

  @Test
  void simultaneousStartsHaveOneDurableTaskAndOnePost() throws Exception {
    var ready = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    doAnswer(
            call -> {
              ready.countDown();
              assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
              return codec.parse(
                  DrawingVerificationFixtures.initial("concurrent-" + UUID.randomUUID())
                      .toString());
            })
        .when(client)
        .verify(any());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> service.start(scope(), null).orElseThrow());
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      var second =
          executor
              .submit(() -> service.start(scope(), null).orElseThrow())
              .get(3, TimeUnit.SECONDS);
      assertThat(second.getTaskState()).isEqualTo("REQUESTING");
      assertThat(second.getEditUrl()).isNull();
      finish.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).getId()).isEqualTo(second.getId());
    } finally {
      finish.countDown();
    }
    verify(client, times(1)).verify(any());
    assertThat(taskCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void changingQuoteDuringRealHttpWaitReleasesRowLockAndPreservesOldResponseAsHistory(
      boolean throughWorkbench) throws Exception {
    for (String status : List.of("TRIAL", "SUCCESS")) {
      jdbc.update(
          "INSERT INTO"
              + " lp_quote_cost_run_version(cost_run_no,oa_no,oa_form_item_id,product_code,pricing_month,result_period,status,business_unit_type)"
              + " VALUES(?,?,?,?,?,?,?,'COMMERCIAL')",
          material + "-" + status,
          oaNo,
          itemId,
          material,
          month,
          month,
          status);
    }
    var frozenCostBefore =
        jdbc.queryForMap(
            "SELECT * FROM lp_quote_cost_run_version WHERE cost_run_no=?", material + "-SUCCESS");
    var received = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(httpExecutor);
    server.createContext(
        "/api/drawings/verify",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          received.countDown();
          try {
            finish.await(8, TimeUnit.SECONDS);
            byte[] data =
                DrawingVerificationFixtures.initial("slow-" + UUID.randomUUID())
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
          } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    var properties = new DrawingVerificationProperties();
    properties.setEnabled(true);
    properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    properties.setRequestTimeout(Duration.ofSeconds(10));
    var http = new HttpDrawingVerificationClient(properties, codec, new ObjectMapper());
    doAnswer(call -> http.verify(call.getArgument(0))).when(client).verify(any());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending =
          executor.submit(
              () -> {
                if (!throughWorkbench) return service.start(scope(), null).orElseThrow();
                assertThatThrownBy(() -> workbench.launchWorkbench(oaNo, itemId))
                    .hasMessageContaining("取图期间报价产品已变化");
                Long savedId =
                    jdbc.queryForObject(
                        "SELECT id FROM lp_drawing_verification_task WHERE oa_form_item_id=?",
                        Long.class,
                        itemId);
                return tasks.selectById(savedId);
              });
      assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
      try (var connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        try (var session = connection.createStatement()) {
          session.execute("SET innodb_lock_wait_timeout=1");
        }
        try (var lock =
            connection.prepareStatement(
                "SELECT status FROM lp_quote_cost_run_version WHERE cost_run_no=? FOR UPDATE")) {
          lock.setString(1, material + "-TRIAL");
          try (var version = lock.executeQuery()) {
            assertThat(version.next()).isTrue();
            assertThat(version.getString(1)).isEqualTo(throughWorkbench ? "STALE" : "TRIAL");
          }
        }
        try (var update =
            connection.prepareStatement("UPDATE oa_form_item SET sunl_model=? WHERE id=?")) {
          update.setString(1, "CHANGED-MODEL");
          update.setLong(2, itemId);
          assertThat(update.executeUpdate()).isEqualTo(1);
        }
        connection.commit();
      }
      finish.countDown();
      var task = pending.get(5, TimeUnit.SECONDS);
      assertThat(task.getTaskState()).isEqualTo("SUPERSEDED");
      assertThat(task.getActiveScopeKey()).isNull();
      assertThat(results.findHistory(task.getId())).hasSize(1);
      assertThat(results.findById(task.getCurrentResultId()).getPlmDataJson())
          .contains("V001", "V002");
      assertThat(service.current(scope())).isEmpty();
      assertThat(
              jdbc.queryForMap(
                  "SELECT * FROM lp_quote_cost_run_version WHERE cost_run_no=?",
                  material + "-SUCCESS"))
          .isEqualTo(frozenCostBefore);
    } finally {
      finish.countDown();
      server.stop(0);
      httpExecutor.shutdownNow();
    }
  }

  @Test
  void outerWriteTransactionIsRejectedBeforeAnyExternalRequest() {
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .execute(status -> service.start(scope(), null)))
        .hasMessageContaining("写事务提交后");
    verifyNoInteractions(client);
    assertThat(taskCount()).isZero();
  }

  @Test
  void changedSourceCreatesNewTaskAndLeavesPriorEvidenceUnchanged() {
    var first = service.start(scope(), null).orElseThrow();
    var original = results.findById(first.getCurrentResultId()).getRawSummaryJson();
    jdbc.update("UPDATE oa_form_item SET sunl_model=? WHERE id=?", model + "-NEW", itemId);
    jdbc.update(
        "UPDATE lp_material_master_raw SET material_model=? WHERE material_code=?",
        model + "-NEW",
        material);
    var second = service.start(scope(), null).orElseThrow();
    assertThat(second.getId()).isNotEqualTo(first.getId());
    assertThat(tasks.selectById(first.getId()).getTaskState()).isEqualTo("SUPERSEDED");
    assertThat(results.findById(first.getCurrentResultId()).getRawSummaryJson())
        .isEqualTo(original);
    assertThat(service.current(scope()).orElseThrow().getId()).isEqualTo(second.getId());
    assertThat(taskCount()).isEqualTo(2);
    verify(client, times(2)).verify(any());
  }

  @Test
  void actualWorkbenchLaunchCallsVerificationOutsideItsTransaction() {
    var response = workbench.launchWorkbench(oaNo, itemId);
    assertThat(response.getSnapshotGenerated()).isFalse();
    assertThat(service.current(scope()).orElseThrow().getTaskState()).isEqualTo("RECEIVED");
    verify(client, times(1)).verify(any());
    workbench.getWorkbench(oaNo, itemId);
    verify(client, times(1)).verify(any());
  }

  @Test
  void explicitRestartUsesExpectedTaskIdentityAndNeverReplaysTheSameReplacement() {
    var first = service.start(scope(), null).orElseThrow();
    var original = results.findById(first.getCurrentResultId()).getRawSummaryJson();
    var second = service.restart(scope(), null, first.getId()).orElseThrow();
    assertThat(second.getId()).isNotEqualTo(first.getId());
    assertThat(service.restart(scope(), null, first.getId()).orElseThrow().getId())
        .isEqualTo(second.getId());
    assertThat(service.start(scope(), null).orElseThrow().getId()).isEqualTo(second.getId());
    assertThat(tasks.selectById(first.getId()).getTaskState()).isEqualTo("SUPERSEDED");
    assertThat(results.findById(first.getCurrentResultId()).getRawSummaryJson())
        .isEqualTo(original);
    verify(client, times(2)).verify(any());
    assertThat(taskCount()).isEqualTo(2);
  }

  @Test
  void concurrentExplicitReplacementCreatesOneNewTask() throws Exception {
    var first = service.start(scope(), null).orElseThrow();
    var called = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    doAnswer(
            call -> {
              called.countDown();
              assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
              return codec.parse(
                  DrawingVerificationFixtures.initial("restart-" + UUID.randomUUID()).toString());
            })
        .when(client)
        .verify(any());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending =
          executor.submit(() -> service.restart(scope(), null, first.getId()).orElseThrow());
      assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
      var repeated = service.restart(scope(), null, first.getId()).orElseThrow();
      assertThat(repeated.getTaskState()).isEqualTo("REQUESTING");
      finish.countDown();
      assertThat(pending.get(5, TimeUnit.SECONDS).getId()).isEqualTo(repeated.getId());
    } finally {
      finish.countDown();
    }
    verify(client, times(2)).verify(any());
    assertThat(taskCount()).isEqualTo(2);
  }

  @Test
  void responseStorageConflictNeverExposesUncommittedEditUrlOrReposts() {
    var payload =
        codec.parse(
            DrawingVerificationFixtures.initial("collision-" + UUID.randomUUID()).toString());
    doReturn(payload).when(client).verify(any());
    var first = service.start(scope(), null).orElseThrow();
    jdbc.update("UPDATE oa_form_item SET sunl_model=? WHERE id=?", model + "-NEW", itemId);
    jdbc.update(
        "UPDATE lp_material_master_raw SET material_model=? WHERE material_code=?",
        model + "-NEW",
        material);
    assertThatThrownBy(() -> service.start(scope(), null)).hasMessageContaining("响应保存失败");
    var pending = service.current(scope()).orElseThrow();
    assertThat(pending.getTaskState()).isEqualTo("REQUESTING");
    assertThat(pending.getCurrentResultId()).isNull();
    assertThat(pending.getEditUrl()).isNull();
    assertThat(results.findHistory(pending.getId())).isEmpty();
    assertThat(results.findHistory(first.getId())).hasSize(1);
    service.start(scope(), null);
    verify(client, times(2)).verify(any());
  }

  private int taskCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM lp_drawing_verification_task WHERE oa_form_item_id=?",
        Integer.class,
        itemId);
  }

  private ElectronicDrawingWorkContext scope() {
    var item = items.selectById(itemId);
    return new ElectronicDrawingWorkContext(
        itemId,
        0,
        null,
        null,
        formId,
        itemId,
        "EDP-04",
        oaNo,
        material,
        null,
        item.getProductName(),
        item.getSpec(),
        item.getSunlModel(),
        "NON_BARE",
        "FULL_BOM",
        month,
        "210",
        "COMMERCIAL",
        "COMMERCIAL",
        "210",
        true,
        true,
        "NEED_TECH",
        null,
        null,
        null,
        null);
  }
}
