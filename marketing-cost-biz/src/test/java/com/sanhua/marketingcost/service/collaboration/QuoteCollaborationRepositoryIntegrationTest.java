package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.IntegrationInbox;
import com.sanhua.marketingcost.entity.IntegrationOutbox;
import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;
import com.sanhua.marketingcost.entity.QuoteCollaborationExternalTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.entity.QuoteCollaborationReviewItem;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@DisplayName("QCBP-02 协作Repository真实MySQL契约")
class QuoteCollaborationRepositoryIntegrationTest extends BomMapperTestBase {

  private static final String BU = "COMMERCIAL";
  private static final String OTHER_BU = "HOUSEHOLD";
  private static final String ORG = "210";
  private static final String OTHER_ORG = "220";
  private static final CollaborationScope SCOPE = new CollaborationScope(BU, ORG);
  private static final CollaborationScope OTHER_BU_SCOPE = new CollaborationScope(OTHER_BU, ORG);
  private static final CollaborationScope OTHER_ORG_SCOPE = new CollaborationScope(BU, OTHER_ORG);

  @Autowired private QuoteCollaborationTaskRepository taskRepository;
  @Autowired private QuotePriceDraftRepository draftRepository;
  @Autowired private QuoteCollaborationReviewRepository reviewRepository;
  @Autowired private QuoteCollaborationExternalTaskRepository externalTaskRepository;
  @Autowired private IntegrationOutboxRepository outboxRepository;
  @Autowired private IntegrationInboxRepository inboxRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  private final CollaborationActor actor = new CollaborationActor(901L, "测试技术员");

  @BeforeAll
  static void createCollaborationSchema() throws Exception {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement()) {
      for (String resource : List.of(
          "/db/V206__quote_bom_price_collaboration_schema.sql",
          "/db/V209__technical_collaboration_menu_and_permissions.sql",
          "/db/V210__quote_collaboration_gap_trace_fields.sql")) {
        if (resource.contains("V210") && columnExists(connection, "lp_quote_collaboration_gap", "bom_quantity")) {
          continue;
        }
        try (InputStream in = QuoteCollaborationRepositoryIntegrationTest.class
            .getResourceAsStream(resource)) {
          assertThat(in).as(resource).isNotNull();
          String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
          for (String fragment : sql.split(";")) {
            if (!fragment.isBlank()) {
              statement.execute(fragment);
            }
          }
        }
      }
    }
  }

  private static boolean columnExists(Connection connection, String tableName, String columnName)
      throws Exception {
    try (var columns = connection.getMetaData().getColumns(
        connection.getCatalog(), null, tableName, columnName)) {
      return columns.next();
    }
  }

  @AfterEach
  void cleanRows() {
    for (String table : List.of(
        "lp_integration_inbox",
        "lp_integration_outbox",
        "lp_quote_collaboration_external_task",
        "lp_quote_collaboration_approved_result",
        "lp_quote_collaboration_review_item",
        "lp_quote_collaboration_review",
        "lp_quote_price_draft_field",
        "lp_quote_price_draft",
        "lp_quote_collaboration_gap",
        "lp_quote_collaboration_quote_link",
        "lp_quote_collaboration_product_task",
        "lp_quote_collaboration_task")) {
      jdbcTemplate.update("DELETE FROM " + table + " WHERE 1=1");
    }
  }

  @Test
  @DisplayName("十二张表可读写NULL、DECIMAL、JSON、日期、中文和长BOM路径")
  void readsAndWritesEveryAggregateWithExactTypes() {
    Graph graph = createGraph(SCOPE, "A");
    String longPath = "顶层/制造件/" + "原材料层级/".repeat(150);
    QuoteCollaborationGap gap = taskRepository.synchronizeGaps(
        graph.productTask().getId(), SCOPE,
        List.of(gapCommand("FP-A", "紫铜管", longPath)), actor).get(0);

    QuotePriceDraft draft = createDraft(graph.productTask(), gap, SCOPE);
    QuotePriceDraftField field = createDraftField(draft);
    QuoteCollaborationReview review = createReview(graph.task());
    QuoteCollaborationReviewItem reviewItem = createReviewItem(review, graph.productTask(), draft);
    QuoteCollaborationApprovedResult result = createApprovedResult(
        review, graph.productTask(), SCOPE);
    QuoteCollaborationExternalTask external = createExternalTask(graph, "TECH-A");
    IntegrationOutbox outbox = createOutbox(graph.productTask());
    IntegrationOutbox dispatchableOutbox = createDispatchableOutbox(graph.productTask());
    IntegrationInbox inbox = createInbox();

    QuoteCollaborationGap storedGap = taskRepository.findGaps(
        graph.productTask().getId(), SCOPE).get(0);
    QuotePriceDraft storedDraft = draftRepository.findById(draft.getId(), SCOPE).orElseThrow();
    QuotePriceDraftField storedField = draftRepository.findFields(draft.getId(), SCOPE).get(0);

    assertThat(graph.task().getCollaborationNo()).startsWith("QCT-");
    assertThat(graph.productTask().getProductTaskNo()).startsWith("QCPT-");
    assertThat(gap.getGapNo()).startsWith("QCG-");
    assertThat(draft.getDraftNo()).startsWith("QCPD-");
    assertThat(review.getReviewNo()).startsWith("QCR-");
    assertThat(result.getResultNo()).startsWith("QCAR-");
    assertThat(storedGap.getBomPath()).isEqualTo(longPath);
    assertThat(storedGap.getMaterialName()).isEqualTo("紫铜管");
    assertThat(storedGap.getSourceId()).isNull();
    assertThat(storedDraft.getTaxRate()).isEqualByComparingTo(new BigDecimal("0.123456"));
    assertThat(storedDraft.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 8, 1));
    assertThat(storedDraft.getEffectiveTo()).isNull();
    assertThat(draftRepository.findByNo(draft.getDraftNo(), SCOPE))
        .get().extracting(QuotePriceDraft::getId).isEqualTo(draft.getId());
    assertThat(draftRepository.findByProductTask(graph.productTask().getId(), SCOPE))
        .extracting(QuotePriceDraft::getId).containsExactly(draft.getId());
    assertThat(storedField.getReferenceValueJson()).contains("参考公式", "重量");
    assertThat(storedField.getTargetValueJson()).contains("目标公式", "净重");
    assertThat(reviewRepository.findReviewById(review.getId(), BU))
        .get().extracting(QuoteCollaborationReview::getId).isEqualTo(review.getId());
    assertThat(reviewRepository.findReviewByNo(review.getReviewNo(), BU))
        .get().extracting(QuoteCollaborationReview::getId).isEqualTo(review.getId());
    assertThat(reviewRepository.findReviewsByReviewer(701L, List.of("PENDING"), BU))
        .extracting(QuoteCollaborationReview::getId).containsExactly(review.getId());
    assertThat(reviewRepository.findReviewItems(review.getId(), SCOPE))
        .extracting(QuoteCollaborationReviewItem::getId).containsExactly(reviewItem.getId());
    assertThat(reviewRepository.findValidResults(
        graph.productTask().getProductCode(), "FULL_BOM", LocalDateTime.now(), SCOPE))
        .extracting(QuoteCollaborationApprovedResult::getId).containsExactly(result.getId());
    assertThat(externalTaskRepository.findCurrentByAssignee(
        "TECH-A", List.of("HOLD"), SCOPE))
        .extracting(QuoteCollaborationExternalTask::getId).containsExactly(external.getId());
    assertThat(outboxRepository.findByIdempotencyKey(outbox.getIdempotencyKey()))
        .get().extracting(IntegrationOutbox::getPayloadJson).asString().contains("技术任务");
    assertThat(outboxRepository.findDispatchable(
        "OA", "PENDING", LocalDateTime.now(), 10))
        .extracting(IntegrationOutbox::getId).containsExactly(dispatchableOutbox.getId());
    assertThat(outboxRepository.findDispatchable(
        "OA", "HOLD", LocalDateTime.now(), 10)).isEmpty();
    assertThat(inboxRepository.findByCallback("OA", inbox.getCallbackId()))
        .get().extracting(IntegrationInbox::getPayloadJson).asString().contains("回调");
    assertThat(inboxRepository.findByIdempotencyKey(inbox.getIdempotencyKey()))
        .get().extracting(IntegrationInbox::getId).isEqualTo(inbox.getId());
  }

  @Test
  @DisplayName("ID、编号、产品、责任人、活动锁、状态和有效期查询严格隔离BU与组织")
  void queriesNeverCrossBusinessUnitOrOrganization() {
    Graph current = createGraph(SCOPE, "CURRENT");
    Graph otherBusinessUnit = createGraph(OTHER_BU_SCOPE, "OTHER-BU");
    Graph otherOrganization = createGraph(OTHER_ORG_SCOPE, "OTHER-ORG");
    jdbcTemplate.update("""
        UPDATE lp_quote_collaboration_product_task
        SET original_technician_user_id=602, current_assignee_user_id=602,
            original_technician_name='李工', current_assignee_name='李工'
        WHERE id=?
        """, otherOrganization.productTask().getId());
    QuoteCollaborationReview review = createReview(current.task());
    createApprovedResult(review, current.productTask(), SCOPE);

    assertThat(taskRepository.findTaskById(current.task().getId(), BU)).isPresent();
    assertThat(taskRepository.findTaskById(current.task().getId(), OTHER_BU)).isEmpty();
    assertThat(taskRepository.findTaskByNo(current.task().getCollaborationNo(), BU)).isPresent();
    assertThat(taskRepository.findTasksByReviewer(701L, List.of("WAIT_TECH"), BU))
        .extracting(QuoteCollaborationTask::getId)
        .containsExactlyInAnyOrder(current.task().getId(), otherOrganization.task().getId());
    assertThat(taskRepository.findProductTaskByNo(
        current.productTask().getProductTaskNo(), SCOPE)).isPresent();
    assertThat(taskRepository.findProductTaskById(current.productTask().getId(), OTHER_BU_SCOPE))
        .isEmpty();
    assertThat(taskRepository.findProductTaskById(current.productTask().getId(), OTHER_ORG_SCOPE))
        .isEmpty();
    assertThat(taskRepository.findActiveProductTaskByLockKey(
        current.productTask().getActiveLockKey(), SCOPE)).isPresent();
    assertThat(taskRepository.findProductTasksByAssignee(
        601L, List.of("WAIT_TECH"), SCOPE))
        .extracting(QuoteCollaborationProductTask::getId)
        .containsExactly(current.productTask().getId());
    assertThat(taskRepository.findMineByTechnician(601L, BU))
        .extracting(QuoteCollaborationProductTask::getId)
        .containsExactly(current.productTask().getId());
    assertThat(taskRepository.findMineByTechnician(602L, BU))
        .extracting(QuoteCollaborationProductTask::getId)
        .containsExactly(otherOrganization.productTask().getId());
    assertThat(taskRepository.findMineById(
        otherOrganization.productTask().getId(), 601L, BU)).isEmpty();
    assertThat(taskRepository.findMineById(
        otherBusinessUnit.productTask().getId(), 601L, BU)).isEmpty();
    assertThat(taskRepository.findProductTasksByProductAndMonth(
        current.productTask().getProductCode(), "2026-08", SCOPE))
        .extracting(QuoteCollaborationProductTask::getId)
        .containsExactly(current.productTask().getId());
    assertThat(taskRepository.findActiveLinksByQuoteItem(current.link().getOaFormItemId(), SCOPE))
        .extracting(QuoteCollaborationQuoteLink::getId).containsExactly(current.link().getId());
    assertThat(taskRepository.findLinksByProductTask(current.productTask().getId(), SCOPE))
        .extracting(QuoteCollaborationQuoteLink::getId).containsExactly(current.link().getId());

    assertThat(otherBusinessUnit.productTask().getId()).isNotEqualTo(current.productTask().getId());
    assertThat(otherOrganization.productTask().getId()).isNotEqualTo(current.productTask().getId());
    assertThat(reviewRepository.findValidResults(
        current.productTask().getProductCode(), "FULL_BOM", LocalDateTime.now(), OTHER_BU_SCOPE))
        .isEmpty();
    assertThat(reviewRepository.findValidResults(
        current.productTask().getProductCode(), "FULL_BOM", LocalDateTime.now(), OTHER_ORG_SCOPE))
        .isEmpty();
  }

  @Test
  @DisplayName("QCBP-09真实MySQL菜单迁移只给协作角色保留技术协作入口")
  void technicalCollaborationMenuIsRestrictedToCollaboratorRole() {
    Integer menuCount = jdbcTemplate.queryForObject("""
        SELECT COUNT(*) FROM sys_menu
        WHERE component='collaboration/technical/index'
          AND perms='collaboration:task:read'
        """, Integer.class);
    Integer unrelatedCount = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM sys_role_menu rm
        JOIN sys_role r ON r.role_id=rm.role_id
        JOIN sys_menu m ON m.menu_id=rm.menu_id
        WHERE LOWER(r.role_key)='oa_collaborator'
          AND COALESCE(m.path, '') NOT IN ('collaboration', 'tasks', '#')
        """, Integer.class);

    assertThat(menuCount).isOne();
    assertThat(unrelatedCount).isZero();
  }

  @Test
  @DisplayName("QCBP-07半年结果在截止时刻前有效，截止时刻起只作为到期参考")
  void approvedResultUsesExclusiveSixMonthBoundary() {
    Graph graph = createGraph(SCOPE, "SIX-MONTH");
    QuoteCollaborationReview review = createReview(graph.task());
    QuoteCollaborationApprovedResult result = createApprovedResult(
        review, graph.productTask(), SCOPE);
    LocalDateTime validFrom = LocalDateTime.of(2026, 8, 12, 10, 30);
    LocalDateTime validUntil = validFrom.plusMonths(6);
    jdbcTemplate.update(
        "UPDATE lp_quote_collaboration_approved_result SET valid_from=?, valid_until=? WHERE id=?",
        validFrom, validUntil, result.getId());

    assertThat(reviewRepository.findValidResults(
        graph.productTask().getProductCode(), "FULL_BOM",
        validUntil.minusSeconds(1), SCOPE))
        .extracting(QuoteCollaborationApprovedResult::getId)
        .containsExactly(result.getId());
    assertThat(reviewRepository.findValidResults(
        graph.productTask().getProductCode(), "FULL_BOM", validUntil, SCOPE))
        .isEmpty();
    assertThat(reviewRepository.findLatestExpiredReference(
        graph.productTask().getProductCode(), "FULL_BOM", validUntil, SCOPE))
        .get().extracting(QuoteCollaborationApprovedResult::getId).isEqualTo(result.getId());
    assertThat(reviewRepository.findValidResults(
        graph.productTask().getProductCode(), "FULL_BOM",
        validUntil.plusSeconds(1), SCOPE))
        .isEmpty();
  }

  @Test
  @DisplayName("QCBP-07手工失效结果立即退出有效匹配并保留原因与审计时间")
  void invalidatedApprovedResultCannotBeReused() {
    Graph graph = createGraph(SCOPE, "INVALIDATE");
    QuoteCollaborationReview review = createReview(graph.task());
    QuoteCollaborationApprovedResult active = createApprovedResult(
        review, graph.productTask(), SCOPE);
    LocalDateTime invalidatedAt = LocalDateTime.of(2026, 8, 13, 11, 0);

    QuoteCollaborationApprovedResult invalid = reviewRepository.invalidateApprovedResult(
        active.getId(), "ACTIVE", "电子图库正式版本已撤销", SCOPE, actor, invalidatedAt);

    assertThat(invalid.getResultStatus()).isEqualTo("INVALIDATED");
    assertThat(invalid.getInvalidReason()).isEqualTo("电子图库正式版本已撤销");
    assertThat(invalid.getInvalidatedAt()).isEqualTo(invalidatedAt);
    assertThat(reviewRepository.findValidResults(
        graph.productTask().getProductCode(), "FULL_BOM", invalidatedAt, SCOPE)).isEmpty();
  }

  @Test
  @DisplayName("主任务、产品任务和价格草稿乐观锁成功递增，旧版本明确失败")
  void rejectsStaleOptimisticUpdates() {
    Graph graph = createGraph(SCOPE, "LOCK");
    QuoteCollaborationGap gap = taskRepository.synchronizeGaps(
        graph.productTask().getId(), SCOPE,
        List.of(gapCommand("FP-LOCK", "锁测试", "顶层/锁测试")), actor).get(0);
    QuotePriceDraft draft = createDraft(graph.productTask(), gap, SCOPE);
    QuoteCollaborationTask storedTask = taskRepository.findTaskById(graph.task().getId(), BU)
        .orElseThrow();
    QuoteCollaborationProductTask storedProduct = taskRepository.findProductTaskById(
        graph.productTask().getId(), SCOPE).orElseThrow();
    QuotePriceDraft storedDraft = draftRepository.findById(draft.getId(), SCOPE).orElseThrow();

    QuoteCollaborationTask updatedTask = taskRepository.transitionTaskStatus(
        storedTask.getId(), storedTask.getTaskVersion(), "WAIT_TECH",
        "WAIT_FINANCE", BU, actor);
    QuoteCollaborationProductTask updatedProduct = taskRepository.transitionProductTaskStatus(
        storedProduct.getId(), storedProduct.getTaskVersion(), "WAIT_TECH", "PRICE_IN_PROGRESS",
        601L, "王工", SCOPE, actor);
    QuotePriceDraft updatedDraft = draftRepository.transitionStatus(
        storedDraft.getId(), storedDraft.getDraftVersion(), "EDITING", "VALIDATED",
        SCOPE, actor);

    assertThat(updatedTask.getTaskVersion()).isEqualTo(storedTask.getTaskVersion() + 1);
    assertThat(updatedProduct.getTaskVersion()).isEqualTo(storedProduct.getTaskVersion() + 1);
    assertThat(updatedDraft.getDraftVersion()).isEqualTo(storedDraft.getDraftVersion() + 1);
    assertThatThrownBy(() -> taskRepository.transitionTaskStatus(
        storedTask.getId(), storedTask.getTaskVersion(), "WAIT_TECH",
        "PARTIAL_RETURN", BU, actor))
        .isInstanceOf(CollaborationOptimisticLockException.class)
        .hasMessageContaining("刷新后重试");
    assertThatThrownBy(() -> taskRepository.transitionProductTaskStatus(
        storedProduct.getId(), storedProduct.getTaskVersion(), "WAIT_TECH", "TECH_SUBMITTED",
        null, null, SCOPE, actor))
        .isInstanceOf(CollaborationOptimisticLockException.class);
    assertThatThrownBy(() -> draftRepository.transitionStatus(
        storedDraft.getId(), storedDraft.getDraftVersion(), "EDITING", "SUBMITTED",
        SCOPE, actor))
        .isInstanceOf(CollaborationOptimisticLockException.class);
  }

  @Test
  @DisplayName("缺口同步复用同一行、重开当前缺口并只将消失缺口标记OBSOLETE")
  void upsertsAndObsoletesGapsWithoutPhysicalDeletion() {
    Graph graph = createGraph(SCOPE, "GAP");
    List<QuoteCollaborationGap> first = taskRepository.synchronizeGaps(
        graph.productTask().getId(), SCOPE,
        List.of(
            gapCommand("FP-KEEP", "保留材料", "顶层/保留"),
            gapCommand("FP-GONE", "消失材料", "顶层/消失")), actor);
    Long keptId = first.stream().filter(row -> row.getGapFingerprint().equals("FP-KEEP"))
        .findFirst().orElseThrow().getId();

    List<QuoteCollaborationGap> second = taskRepository.synchronizeGaps(
        graph.productTask().getId(), SCOPE,
        List.of(gapCommand("FP-KEEP", "保留材料已更新", "顶层/新路径")), actor);

    assertThat(second).hasSize(2);
    assertThat(second).filteredOn(row -> row.getGapFingerprint().equals("FP-KEEP"))
        .singleElement().satisfies(row -> {
          assertThat(row.getId()).isEqualTo(keptId);
          assertThat(row.getGapStatus()).isEqualTo("OPEN");
          assertThat(row.getMaterialName()).isEqualTo("保留材料已更新");
          assertThat(row.getBomPath()).isEqualTo("顶层/新路径");
          assertThat(row.getBomQuantity()).isEqualByComparingTo("1.25");
          assertThat(row.getBomUnit()).isEqualTo("kg");
          assertThat(row.getAccountingMonth()).isEqualTo("2026-08");
          assertThat(row.getApplicableOrgCode()).isEqualTo("210");
        });
    assertThat(second).filteredOn(row -> row.getGapFingerprint().equals("FP-GONE"))
        .singleElement().extracting(QuoteCollaborationGap::getGapStatus)
        .isEqualTo("OBSOLETE");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_collaboration_gap WHERE product_task_id=?",
        Integer.class, graph.productTask().getId())).isEqualTo(2);

    List<QuoteCollaborationGap> reopened = taskRepository.synchronizeGaps(
        graph.productTask().getId(), SCOPE,
        List.of(gapCommand("FP-GONE", "重新出现材料", "顶层/重新出现")), actor);
    assertThat(reopened).filteredOn(row -> row.getGapFingerprint().equals("FP-GONE"))
        .singleElement().satisfies(row -> {
          assertThat(row.getGapStatus()).isEqualTo("OPEN");
          assertThat(row.getResolvedAt()).isNull();
          assertThat(row.getResolvedBy()).isNull();
        });
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_collaboration_gap WHERE product_task_id=?",
        Integer.class, graph.productTask().getId())).isEqualTo(2);
  }

  @Test
  @DisplayName("草稿发布追溯只从新增表反查正式记录并受BU组织隔离")
  void tracesPublishedDraftWithoutReadingOrChangingLegacyTables() {
    Graph graph = createGraph(SCOPE, "TRACE");
    QuoteCollaborationGap gap = taskRepository.synchronizeGaps(
        graph.productTask().getId(), SCOPE,
        List.of(gapCommand("FP-TRACE", "追溯材料", "顶层/追溯")), actor).get(0);
    QuotePriceDraft draft = createDraft(graph.productTask(), gap, SCOPE);
    jdbcTemplate.update(
        "UPDATE lp_quote_price_draft SET published_source_table=?, published_source_id=?, "
            + "publish_batch_no=?, published_at=NOW(), draft_status='PUBLISHED' WHERE id=?",
        "lp_price_linked_item", 778899L, "PUB-" + suffix, draft.getId());

    assertThat(draftRepository.findByPublishedSource(
        "lp_price_linked_item", 778899L, SCOPE))
        .extracting(QuotePriceDraft::getId).containsExactly(draft.getId());
    assertThat(draftRepository.findByPublishedSource(
        "lp_price_linked_item", 778899L, OTHER_BU_SCOPE)).isEmpty();
    assertThat(draftRepository.findByPublishedSource(
        "lp_price_linked_item", 778899L, OTHER_ORG_SCOPE)).isEmpty();
  }

  @Test
  @DisplayName("事务中途失败后主任务与产品任务均不残留")
  void rollsBackAggregateWritesOnFailure() {
    String marker = "ROLLBACK-" + suffix;
    TransactionTemplate template = new TransactionTemplate(transactionManager);

    assertThatThrownBy(() -> template.executeWithoutResult(status -> {
      QuoteCollaborationTask task = newTask(SCOPE, marker);
      taskRepository.saveTask(task);
      QuoteCollaborationProductTask productTask = newProductTask(task, SCOPE, marker);
      taskRepository.saveProductTask(productTask);
      throw new IllegalStateException("验证事务回滚");
    })).isInstanceOf(IllegalStateException.class).hasMessage("验证事务回滚");

    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_collaboration_task WHERE oa_no=?",
        Integer.class, "OA-" + marker)).isZero();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_collaboration_product_task WHERE product_code=?",
        Integer.class, "PRODUCT-" + marker)).isZero();
  }

  @Test
  @DisplayName("价格字段和审核项批量写入失败时各自不残留半批数据")
  void rollsBackRepositoryOwnedBatchWritesOnFailure() {
    Graph graph = createGraph(SCOPE, "BATCH-ROLLBACK");
    QuoteCollaborationGap gap = taskRepository.synchronizeGaps(
        graph.productTask().getId(), SCOPE,
        List.of(gapCommand("FP-BATCH", "批量事务材料", "顶层/批量事务")), actor).get(0);
    QuotePriceDraft draft = createDraft(graph.productTask(), gap, SCOPE);
    QuotePriceDraftField firstField = newDraftField(draft);
    QuotePriceDraftField duplicateField = newDraftField(draft);

    assertThatThrownBy(() -> draftRepository.saveFields(
        List.of(firstField, duplicateField)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(draftRepository.findFields(draft.getId(), SCOPE)).isEmpty();

    QuoteCollaborationReview review = createReview(graph.task());
    QuoteCollaborationReviewItem firstItem = newReviewItem(
        review, graph.productTask(), draft);
    QuoteCollaborationReviewItem duplicateItem = newReviewItem(
        review, graph.productTask(), draft);

    assertThatThrownBy(() -> reviewRepository.saveReviewItems(
        List.of(firstItem, duplicateItem)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(reviewRepository.findReviewItems(review.getId(), SCOPE)).isEmpty();
  }

  private Graph createGraph(CollaborationScope scope, String marker) {
    QuoteCollaborationTask task = taskRepository.saveTask(newTask(scope, marker));
    QuoteCollaborationProductTask productTask = taskRepository.saveProductTask(
        newProductTask(task, scope, marker));
    QuoteCollaborationQuoteLink link = new QuoteCollaborationQuoteLink();
    link.setProductTaskId(productTask.getId());
    link.setCollaborationId(task.getId());
    link.setOaFormId(task.getOaFormId());
    link.setOaFormItemId(positiveKey("ITEM-" + marker));
    link.setOaNo(task.getOaNo());
    link.setProductCode(productTask.getProductCode());
    link.setAccountingMonth("2026-08");
    link.setApplicableOrgCode(scope.applicableOrgCode());
    link.setLinkType("OWNER");
    link.setLinkStatus("WAIT_SOURCE");
    link.setActiveLinkKey("OA_ITEM:" + link.getOaFormItemId());
    taskRepository.saveQuoteLink(link);
    return new Graph(task, productTask, link);
  }

  private QuoteCollaborationTask newTask(CollaborationScope scope, String marker) {
    QuoteCollaborationTask task = new QuoteCollaborationTask();
    task.setOaFormId(positiveKey("FORM-" + marker));
    task.setOaNo("OA-" + marker);
    task.setBusinessUnitType(scope.businessUnitType());
    task.setAccountingMonth("2026-08");
    task.setMasterStatus("WAIT_TECH");
    task.setFinanceReviewerUserId(701L);
    task.setFinanceReviewerName("财务审核员");
    return task;
  }

  private QuoteCollaborationProductTask newProductTask(
      QuoteCollaborationTask task, CollaborationScope scope, String marker) {
    QuoteCollaborationProductTask productTask = new QuoteCollaborationProductTask();
    productTask.setOriginCollaborationId(task.getId());
    productTask.setAccountingMonth("2026-08");
    productTask.setBusinessUnitType(scope.businessUnitType());
    productTask.setApplicableOrgCode(scope.applicableOrgCode());
    productTask.setMaterialOrgCode(scope.applicableOrgCode());
    productTask.setPriceOrgCode(scope.applicableOrgCode());
    productTask.setProductCode("PRODUCT-" + marker);
    productTask.setProductName("热力膨胀阀" + marker);
    productTask.setProductSpec("规格-" + marker);
    productTask.setProductModel("型号-" + marker);
    productTask.setProductForm("NORMAL");
    productTask.setPrimaryScope("FULL_BOM");
    productTask.setNeedBom(1);
    productTask.setNeedPrice(1);
    productTask.setTaskStatus("WAIT_TECH");
    productTask.setOriginalTechnicianUserId(601L);
    productTask.setOriginalTechnicianName("王工");
    productTask.setCurrentAssigneeUserId(601L);
    productTask.setCurrentAssigneeName("王工");
    productTask.setActiveLockKey(
        scope.businessUnitType() + ":" + scope.applicableOrgCode() + ":" + marker);
    return productTask;
  }

  private GapUpsertCommand gapCommand(String fingerprint, String name, String path) {
    return new GapUpsertCommand(
        "PRICE", "MISSING_PRICE", "PRICE_PREPARE", null, fingerprint,
        "NODE-" + fingerprint, path, "MAT-" + fingerprint, name,
        "TP2 Φ9.52×0.7", "TP2-952-07", "RAW", "LINKED",
        "NO_EFFECTIVE_PRICE", "当前核算日没有有效价格",
        new BigDecimal("1.25"), "kg", "2026-08", "210");
  }

  private QuotePriceDraft createDraft(
      QuoteCollaborationProductTask productTask,
      QuoteCollaborationGap gap,
      CollaborationScope scope) {
    QuotePriceDraft draft = new QuotePriceDraft();
    draft.setProductTaskId(productTask.getId());
    draft.setGapId(gap.getId());
    draft.setMaterialCode(gap.getMaterialCode());
    draft.setMaterialName(gap.getMaterialName());
    draft.setBusinessUnitType(scope.businessUnitType());
    draft.setOrgCode(scope.applicableOrgCode());
    draft.setPriceType("LINKED");
    draft.setSourceMode("COPY");
    draft.setReferenceSourceType("LINKED");
    draft.setReferenceSourceId(12345L);
    draft.setTargetSourceType("LINKED");
    draft.setUnit("元/kg");
    draft.setTaxIncluded(1);
    draft.setTaxRate(new BigDecimal("0.123456"));
    draft.setEffectiveFrom(LocalDate.of(2026, 8, 1));
    draft.setEffectiveTo(null);
    draft.setDraftStatus("EDITING");
    return draftRepository.saveDraft(draft);
  }

  private QuotePriceDraftField createDraftField(QuotePriceDraft draft) {
    QuotePriceDraftField field = newDraftField(draft);
    draftRepository.saveFields(List.of(field));
    return field;
  }

  private QuotePriceDraftField newDraftField(QuotePriceDraft draft) {
    QuotePriceDraftField field = new QuotePriceDraftField();
    field.setPriceDraftId(draft.getId());
    field.setSectionCode("FORMULA");
    field.setRowKey("MAIN");
    field.setFieldCode("FORMULA_EXPRESSION");
    field.setFieldName("联动公式");
    field.setValueType("JSON");
    field.setReferenceValueJson("{\"公式\":\"参考公式\",\"变量\":\"重量\"}");
    field.setTargetValueJson("{\"公式\":\"目标公式\",\"变量\":\"净重\"}");
    field.setRequiredFlag(1);
    field.setTechInputRequired(1);
    return field;
  }

  private QuoteCollaborationReview createReview(QuoteCollaborationTask task) {
    QuoteCollaborationReview review = new QuoteCollaborationReview();
    review.setCollaborationId(task.getId());
    review.setReviewStatus("PENDING");
    review.setReviewerUserId(701L);
    review.setReviewerName("财务审核员");
    review.setSourceTaskVersion(1);
    review.setProductCount(1);
    review.setPriceDraftCount(1);
    return reviewRepository.saveReview(review);
  }

  private QuoteCollaborationReviewItem createReviewItem(
      QuoteCollaborationReview review,
      QuoteCollaborationProductTask productTask,
      QuotePriceDraft draft) {
    QuoteCollaborationReviewItem item = newReviewItem(review, productTask, draft);
    reviewRepository.saveReviewItems(List.of(item));
    return item;
  }

  private QuoteCollaborationReviewItem newReviewItem(
      QuoteCollaborationReview review,
      QuoteCollaborationProductTask productTask,
      QuotePriceDraft draft) {
    QuoteCollaborationReviewItem item = new QuoteCollaborationReviewItem();
    item.setReviewId(review.getId());
    item.setProductTaskId(productTask.getId());
    item.setItemType("PRICE_DRAFT");
    item.setItemRefId(draft.getId());
    item.setItemVersion(1);
    item.setItemSummary("联动价草稿审核");
    item.setDifferenceSnapshotJson("{\"差异\":\"仅修改净重\"}");
    item.setValidationSnapshotJson("{\"校验\":\"通过\"}");
    return item;
  }

  private QuoteCollaborationApprovedResult createApprovedResult(
      QuoteCollaborationReview review,
      QuoteCollaborationProductTask productTask,
      CollaborationScope scope) {
    QuoteCollaborationApprovedResult result = new QuoteCollaborationApprovedResult();
    result.setResultType("FULL_BOM");
    result.setSourceProductTaskId(productTask.getId());
    result.setSourceReviewId(review.getId());
    result.setProductCode(productTask.getProductCode());
    result.setProductForm(productTask.getProductForm());
    result.setApplicableOrgCode(scope.applicableOrgCode());
    result.setSourceObjectType("SUPPLEMENT_VERSION");
    result.setSourceObjectId(8899L);
    result.setSourceSystem("ELECTRONIC_DRAWING");
    result.setStructureFingerprint("a".repeat(64));
    result.setValidityPolicyCode("COLLAB_RESULT_SIX_MONTHS_V1");
    result.setValidFrom(LocalDateTime.now().minusDays(1));
    result.setValidUntil(LocalDateTime.now().plusMonths(6));
    result.setResultStatus("ACTIVE");
    return reviewRepository.saveApprovedResult(result);
  }

  private QuoteCollaborationExternalTask createExternalTask(Graph graph, String assignee) {
    QuoteCollaborationExternalTask task = new QuoteCollaborationExternalTask();
    task.setCollaborationId(graph.task().getId());
    task.setProductTaskId(graph.productTask().getId());
    task.setTaskKind("TECH");
    task.setLogicalTaskVersion(1);
    task.setExternalStatus("HOLD");
    task.setAssigneeUserId(assignee);
    task.setAssigneeName("技术协作者");
    return externalTaskRepository.save(task);
  }

  private IntegrationOutbox createOutbox(QuoteCollaborationProductTask productTask) {
    IntegrationOutbox event = new IntegrationOutbox();
    event.setEventId(UUID.randomUUID().toString());
    event.setIdempotencyKey("OUT-" + suffix);
    event.setDestination("OA");
    event.setAggregateType("PRODUCT_TASK");
    event.setAggregateId(productTask.getId());
    event.setAggregateVersion(1);
    event.setEventType("TECH_TASK_READY");
    event.setPayloadJson("{\"消息\":\"技术任务已就绪\"}");
    event.setPayloadHash("b".repeat(64));
    event.setSendPolicy("HOLD");
    event.setSendStatus("HOLD");
    event.setOccurredAt(LocalDateTime.now());
    return outboxRepository.save(event);
  }

  private IntegrationOutbox createDispatchableOutbox(
      QuoteCollaborationProductTask productTask) {
    IntegrationOutbox event = new IntegrationOutbox();
    event.setEventId(UUID.randomUUID().toString());
    event.setIdempotencyKey("OUT-AUTO-" + suffix);
    event.setDestination("OA");
    event.setAggregateType("PRODUCT_TASK");
    event.setAggregateId(productTask.getId());
    event.setAggregateVersion(2);
    event.setEventType("TECH_TASK_READY");
    event.setPayloadJson("{\"消息\":\"允许调度的技术任务\"}");
    event.setPayloadHash("d".repeat(64));
    event.setSendPolicy("AUTO");
    event.setSendStatus("PENDING");
    event.setOccurredAt(LocalDateTime.now().minusMinutes(2));
    event.setNextRetryAt(LocalDateTime.now().minusMinutes(1));
    return outboxRepository.save(event);
  }

  private IntegrationInbox createInbox() {
    IntegrationInbox callback = new IntegrationInbox();
    callback.setSourceSystem("OA");
    callback.setCallbackId("CALL-" + suffix);
    callback.setIdempotencyKey("IN-" + suffix);
    callback.setCallbackType("TASK_CALLBACK");
    callback.setPayloadJson("{\"消息\":\"回调已收到\"}");
    callback.setPayloadHash("c".repeat(64));
    callback.setSignatureStatus("NOT_CHECKED");
    callback.setProcessStatus("RECEIVED");
    callback.setReceivedAt(LocalDateTime.now());
    return inboxRepository.save(callback);
  }

  private long positiveKey(String marker) {
    return Integer.toUnsignedLong((suffix + marker).hashCode()) + 1000L;
  }

  private record Graph(
      QuoteCollaborationTask task,
      QuoteCollaborationProductTask productTask,
      QuoteCollaborationQuoteLink link) {}
}
