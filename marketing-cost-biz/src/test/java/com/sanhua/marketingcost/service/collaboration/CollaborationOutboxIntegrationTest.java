package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sanhua.marketingcost.entity.IntegrationOutbox;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.integration.oa.collaboration.DisabledOaCollaborationCallbackPort;
import com.sanhua.marketingcost.integration.oa.collaboration.OaCollaborationCallback;
import com.sanhua.marketingcost.integration.oa.collaboration.OaCollaborationCallbackType;
import com.sanhua.marketingcost.integration.oa.collaboration.OaCollaborationEventType;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@DisplayName("QCBP-04 Outbox与状态事务真实MySQL集成")
class CollaborationOutboxIntegrationTest extends BomMapperTestBase {

  private static final String BU = "COMMERCIAL";
  private static final String ORG = "210";
  private static final CollaborationScope SCOPE = new CollaborationScope(BU, ORG);
  private static final CollaborationPrincipal TECH = new CollaborationPrincipal(
      601L, "王工", Set.of(CollaborationRole.TECHNICIAN));

  @Autowired private CollaborationEventService eventService;
  @Autowired private IntegrationOutboxRepository outboxRepository;
  @Autowired private QuoteCollaborationTaskRepository taskRepository;
  @Autowired private CollaborationProductStateService productStateService;
  @Autowired private DisabledOaCollaborationCallbackPort callbackPort;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);

  @BeforeAll
  static void createCollaborationSchema() throws Exception {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement();
        InputStream in = CollaborationOutboxIntegrationTest.class.getResourceAsStream(
            "/db/V206__quote_bom_price_collaboration_schema.sql")) {
      assertThat(in).isNotNull();
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String fragment : sql.split(";")) {
        if (!fragment.isBlank()) {
          statement.execute(fragment);
        }
      }
    }
  }

  @AfterEach
  void cleanRows() {
    jdbcTemplate.update("DELETE FROM lp_integration_inbox WHERE 1=1");
    jdbcTemplate.update("DELETE FROM lp_integration_outbox WHERE 1=1");
    jdbcTemplate.update("DELETE FROM lp_quote_collaboration_external_task WHERE 1=1");
    jdbcTemplate.update("DELETE FROM lp_quote_collaboration_product_task WHERE 1=1");
    jdbcTemplate.update("DELETE FROM lp_quote_collaboration_task WHERE 1=1");
  }

  @Test
  @DisplayName("OA V0.5十四类事件全部保存为HOLD且JSON通用契约一致")
  void persistsEveryOaV05EventAsHold() throws Exception {
    int version = 1;
    for (OaCollaborationEventType eventType : OaCollaborationEventType.values()) {
      eventService.append(command(eventType, version++, safeData(eventType)));
    }

    List<IntegrationOutbox> rows = jdbcTemplate.query(
        "SELECT * FROM lp_integration_outbox ORDER BY id",
        (rs, rowNum) -> {
          IntegrationOutbox value = new IntegrationOutbox();
          value.setId(rs.getLong("id"));
          value.setEventType(rs.getString("event_type"));
          value.setPayloadJson(rs.getString("payload_json"));
          value.setSendPolicy(rs.getString("send_policy"));
          value.setSendStatus(rs.getString("send_status"));
          value.setResponseJson(rs.getString("response_json"));
          return value;
        });
    assertThat(rows).hasSize(OaCollaborationEventType.values().length);
    assertThat(rows).allSatisfy(row -> {
      assertThat(row.getSendPolicy()).isEqualTo("HOLD");
      assertThat(row.getSendStatus()).isEqualTo("HOLD");
      assertThat(row.getResponseJson()).isNull();
      try {
        JsonNode json = objectMapper.readTree(row.getPayloadJson());
        // MySQL JSON columns canonicalize object key order. Serialization order
        // is verified before persistence; here the complete envelope is checked.
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
            "eventId", "eventType", "eventVersion", "occurredAt", "sourceSystem",
            "traceId", "idempotencyKey", "data");
        assertThat(json.path("eventType").asText()).isEqualTo(row.getEventType());
      } catch (Exception exception) {
        throw new AssertionError(exception);
      }
    });
    assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM lp_quote_collaboration_external_task "
                + "WHERE external_task_id IS NOT NULL OR external_process_id IS NOT NULL",
        Integer.class)).isZero();
  }

  @Test
  @DisplayName("重复与并发写同一事件只产生一行且返回同一事件ID")
  void appendsSameEventOnceAcrossConcurrentRequests() throws Exception {
    CollaborationEventCommand command = command(
        OaCollaborationEventType.TECH_TASK_UPDATED, 7,
        JsonNodeFactory.instance.objectNode().put("statusCode", "BOM_IN_PROGRESS"));
    CountDownLatch ready = new CountDownLatch(4);
    CountDownLatch start = new CountDownLatch(1);
    List<Object> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());

    try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
      for (int i = 0; i < 4; i++) {
        executor.submit(() -> {
          ready.countDown();
          try {
            start.await(5, TimeUnit.SECONDS);
            outcomes.add(eventService.append(command));
          } catch (Exception exception) {
            outcomes.add(exception);
          }
        });
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      executor.shutdown();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(outcomes).allMatch(CollaborationEventService.AppendResult.class::isInstance);
    assertThat(outcomes.stream()
        .map(CollaborationEventService.AppendResult.class::cast)
        .map(result -> result.event().getEventId()).distinct()).hasSize(1);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lp_integration_outbox WHERE idempotency_key=?",
        Integer.class, CollaborationIdempotencyKeys.oaEvent(
            command.aggregateNo(), command.aggregateVersion(), command.eventType().name(), null)))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("同一幂等键不同报文被拒绝且不覆盖原事件")
  void rejectsDifferentPayloadForSameIdempotencyKey() {
    CollaborationEventCommand first = command(
        OaCollaborationEventType.TECH_TASK_UPDATED, 8,
        JsonNodeFactory.instance.objectNode().put("statusCode", "BOM_IN_PROGRESS"));
    CollaborationEventCommand changed = command(
        OaCollaborationEventType.TECH_TASK_UPDATED, 8,
        JsonNodeFactory.instance.objectNode().put("statusCode", "PRICE_IN_PROGRESS"));
    IntegrationOutbox original = eventService.append(first).event();

    assertThatThrownBy(() -> eventService.append(changed))
        .isInstanceOfSatisfying(CollaborationDomainException.class,
            error -> assertThat(error.code()).isEqualTo(
                CollaborationDomainErrorCode.IDEMPOTENCY_CONFLICT));
    assertThat(outboxRepository.findByIdempotencyKey(original.getIdempotencyKey()).orElseThrow()
        .getPayloadHash()).isEqualTo(original.getPayloadHash());
  }

  @Test
  @DisplayName("产品状态迁移与HOLD事件同一事务提交")
  void commitsProductTransitionAndOutboxTogether() {
    Aggregate aggregate = createAggregate("ATOMIC-SUCCESS");
    QuoteCollaborationProductTask updated = productStateService.transition(
        aggregate.product().getId(), aggregate.product().getTaskVersion(), SCOPE,
        ProductAction.START_BOM, TECH).task();

    assertThat(updated.getTaskStatus()).isEqualTo("BOM_IN_PROGRESS");
    String key = CollaborationIdempotencyKeys.oaEvent(
        updated.getProductTaskNo(), updated.getTaskVersion(), "TECH_TASK_UPDATED", null);
    IntegrationOutbox event = outboxRepository.findByIdempotencyKey(key).orElseThrow();
    assertThat(event.getSendPolicy()).isEqualTo("HOLD");
    assertThat(event.getSendStatus()).isEqualTo("HOLD");
  }

  @Test
  @DisplayName("Outbox幂等冲突会回滚同事务内已经执行的状态迁移")
  void rollsBackProductTransitionWhenOutboxAppendFails() {
    Aggregate aggregate = createAggregate("ATOMIC-ROLLBACK");
    QuoteCollaborationProductTask original = aggregate.product();
    String key = CollaborationIdempotencyKeys.oaEvent(
        original.getProductTaskNo(), original.getTaskVersion() + 1,
        "TECH_TASK_UPDATED", null);
    outboxRepository.save(conflictingEvent(original, key));

    assertThatThrownBy(() -> productStateService.transition(
        original.getId(), original.getTaskVersion(), SCOPE,
        ProductAction.START_BOM, TECH))
        .isInstanceOfSatisfying(CollaborationDomainException.class,
            error -> assertThat(error.code()).isEqualTo(
                CollaborationDomainErrorCode.IDEMPOTENCY_CONFLICT));
    QuoteCollaborationProductTask stored = taskRepository.findProductTaskById(
        original.getId(), SCOPE).orElseThrow();
    assertThat(stored.getTaskStatus()).isEqualTo("WAIT_TECH");
    assertThat(stored.getTaskVersion()).isEqualTo(original.getTaskVersion());
  }

  @Test
  @DisplayName("OA禁用时重复或错误签名回调不写Inbox也不推进产品状态")
  void disabledCallbacksNeverMutateBusinessOrInbox() {
    Aggregate aggregate = createAggregate("CALLBACK-DISABLED");
    var callback = new OaCollaborationCallback(
        "callback-" + suffix, OaCollaborationCallbackType.OA_TASK_ASSIGNEE_CHANGED,
        OffsetDateTime.now(), "OA", "trace-" + suffix, "OA-KEY-" + suffix,
        JsonNodeFactory.instance.objectNode()
            .put("productTaskNo", aggregate.product().getProductTaskNo()));

    assertThat(callbackPort.handle(callback, "bad-signature").code())
        .isEqualTo("INTEGRATION_DISABLED");
    assertThat(callbackPort.handle(callback, "bad-signature").code())
        .isEqualTo("INTEGRATION_DISABLED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lp_integration_inbox", Integer.class)).isZero();
    QuoteCollaborationProductTask stored = taskRepository.findProductTaskById(
        aggregate.product().getId(), SCOPE).orElseThrow();
    assertThat(stored.getTaskStatus()).isEqualTo("WAIT_TECH");
    assertThat(stored.getTaskVersion()).isEqualTo(aggregate.product().getTaskVersion());
  }

  private CollaborationEventCommand command(
      OaCollaborationEventType eventType, int version, JsonNode data) {
    return new CollaborationEventCommand(
        "PRODUCT_TASK", 9000L, "QCPT-" + suffix, version, eventType, null,
        "trace-" + suffix, OffsetDateTime.now(), data);
  }

  private JsonNode safeData(OaCollaborationEventType eventType) {
    return JsonNodeFactory.instance.objectNode()
        .put("productTaskNo", "QCPT-" + suffix)
        .put("statusCode", eventType.name())
        .put("openGapCount", 1);
  }

  private Aggregate createAggregate(String marker) {
    QuoteCollaborationTask master = new QuoteCollaborationTask();
    master.setOaFormId(positiveKey(marker + "-FORM"));
    master.setOaNo("OA-" + marker + "-" + suffix);
    master.setRoundNo(1);
    master.setBusinessUnitType(BU);
    master.setAccountingMonth("2026-08");
    master.setSourceSystem("QUOTE");
    master.setMasterStatus("WAIT_TECH");
    master.setFinanceReviewerUserId(701L);
    master.setFinanceReviewerName("财务审核员");
    master = taskRepository.saveTask(master);

    QuoteCollaborationProductTask product = new QuoteCollaborationProductTask();
    product.setOriginCollaborationId(master.getId());
    product.setAccountingMonth("2026-08");
    product.setBusinessUnitType(BU);
    product.setApplicableOrgCode(ORG);
    product.setMaterialOrgCode(ORG);
    product.setPriceOrgCode(ORG);
    product.setProductCode("P-" + marker + "-" + suffix);
    product.setProductName("热力膨胀阀");
    product.setProductForm("NORMAL");
    product.setPrimaryScope("FULL_BOM");
    product.setNeedBom(1);
    product.setNeedPackage(0);
    product.setNeedPrice(1);
    product.setOpenGapCount(0);
    product.setTaskStatus("WAIT_TECH");
    product.setOriginalTechnicianUserId(601L);
    product.setOriginalTechnicianName("王工");
    product.setCurrentAssigneeUserId(601L);
    product.setCurrentAssigneeName("王工");
    product.setActiveLockKey(BU + ":" + ORG + ":" + marker + ":" + suffix);
    product = taskRepository.saveProductTask(product);
    return new Aggregate(master, product);
  }

  private IntegrationOutbox conflictingEvent(
      QuoteCollaborationProductTask product, String key) {
    IntegrationOutbox event = new IntegrationOutbox();
    event.setEventId(UUID.randomUUID().toString());
    event.setIdempotencyKey(key);
    event.setDestination("OA");
    event.setAggregateType("PRODUCT_TASK");
    event.setAggregateId(product.getId());
    event.setAggregateVersion(product.getTaskVersion() + 1);
    event.setEventType("TECH_TASK_UPDATED");
    event.setEventVersion("1.0");
    event.setPayloadJson("{\"conflict\":true}");
    event.setPayloadHash("f".repeat(64));
    event.setSendPolicy("HOLD");
    event.setSendStatus("HOLD");
    event.setRetryCount(0);
    event.setOccurredAt(LocalDateTime.now());
    return event;
  }

  private static long positiveKey(String value) {
    return Integer.toUnsignedLong(value.hashCode()) + 1L;
  }

  private record Aggregate(
      QuoteCollaborationTask master,
      QuoteCollaborationProductTask product) {}
}
