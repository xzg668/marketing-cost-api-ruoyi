package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.mapper.QuoteCollaborationTaskMapper;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.DraftAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.MasterAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.QuoteLinkAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ReviewAction;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@DisplayName("QCBP-03 状态与责任人真实MySQL事务")
class CollaborationStateTransitionIntegrationTest extends BomMapperTestBase {

  private static final String BU = "COMMERCIAL";
  private static final String ORG = "210";
  private static final CollaborationScope SCOPE = new CollaborationScope(BU, ORG);
  private static final CollaborationPrincipal TECH = principal(
      601L, "王工", CollaborationRole.TECHNICIAN);
  private static final CollaborationPrincipal FINANCE = principal(
      701L, "财务审核员", CollaborationRole.FINANCE_REVIEWER);
  private static final CollaborationPrincipal COSTING = principal(
      801L, "核算员", CollaborationRole.COSTING_OPERATOR);
  private static final CollaborationPrincipal SYSTEM = principal(
      0L, "系统", CollaborationRole.SYSTEM);
  private static final CollaborationPrincipal ADMIN = principal(
      901L, "管理员", CollaborationRole.ADMINISTRATOR);
  private static final CollaborationPrincipal OUTSIDER = principal(
      602L, "其他技术", CollaborationRole.TECHNICIAN);

  @Autowired private QuoteCollaborationTaskRepository repository;
  @Autowired private QuoteCollaborationTaskMapper taskMapper;
  @Autowired private CollaborationProductStateService productStateService;
  @Autowired private CollaborationMasterStateService masterStateService;
  @Autowired private CollaborationDraftStateService draftStateService;
  @Autowired private CollaborationReviewStateService reviewStateService;
  @Autowired private CollaborationQuoteLinkStateService quoteLinkStateService;
  @Autowired private QuotePriceDraftRepository draftRepository;
  @Autowired private QuoteCollaborationReviewRepository reviewRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private final String suffix = UUID.randomUUID().toString().replace("-", "")
      .substring(0, 12);

  @BeforeAll
  static void createCollaborationSchema() throws Exception {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement();
        InputStream in = CollaborationStateTransitionIntegrationTest.class.getResourceAsStream(
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
    jdbcTemplate.update("DELETE FROM lp_integration_outbox WHERE 1=1");
    jdbcTemplate.update("DELETE FROM lp_quote_collaboration_review WHERE 1=1");
    jdbcTemplate.update("DELETE FROM lp_quote_price_draft WHERE 1=1");
    jdbcTemplate.update("DELETE FROM lp_quote_collaboration_quote_link WHERE 1=1");
    jdbcTemplate.update("DELETE FROM lp_quote_collaboration_product_task WHERE 1=1");
    jdbcTemplate.update("DELETE FROM lp_quote_collaboration_task WHERE 1=1");
  }

  @Test
  @DisplayName("完整产品状态链同步切换技术、财务、系统和核算责任人")
  void transitionsFullProductLifecycleWithConsistentAssigneeAndNextAction() {
    Aggregate aggregate = createAggregate("FULL");
    QuoteCollaborationProductTask task = aggregate.product();
    QuoteCollaborationQuoteLink linked = createLinkedQuoteLink(aggregate);

    task = transition(task, ProductAction.START_BOM, TECH);
    assertState(task, "BOM_IN_PROGRESS", 601L, "王工");
    task = transition(task, ProductAction.FAIL_TECH_VALIDATION, TECH);
    assertState(task, "TECH_VALIDATION_FAILED", 601L, "王工");
    task = transition(task, ProductAction.RETRY_BOM, TECH);
    task = transition(task, ProductAction.CONTINUE_PRICE_AFTER_BOM, TECH);
    assertState(task, "PRICE_IN_PROGRESS", 601L, "王工");
    task = transition(task, ProductAction.SUBMIT_TECH, TECH);
    assertState(task, "TECH_SUBMITTED", null, null);
    task = transition(task, ProductAction.ROUTE_TO_FINANCE, SYSTEM);
    assertState(task, "WAIT_FINANCE", 701L, "财务审核员");
    task = transition(task, ProductAction.REJECT_TO_TECH, FINANCE);
    assertState(task, "RETURNED_TO_TECH", 601L, "王工");
    task = transition(task, ProductAction.SUBMIT_TECH, TECH);
    task = transition(task, ProductAction.ROUTE_TO_FINANCE, SYSTEM);
    task = transition(task, ProductAction.APPROVE_FOR_PUBLISHING, FINANCE);
    assertState(task, "APPROVED_PUBLISHING", null, "系统");
    task = transition(task, ProductAction.FAIL_PUBLISH_OR_REPRICE, SYSTEM);
    assertState(task, "PUBLISH_OR_REPRICE_FAILED", null, "系统");
    task = transition(task, ProductAction.RETRY_PUBLISH_OR_REPRICE, SYSTEM);
    task = transition(task, ProductAction.MARK_READY_FOR_COSTING, SYSTEM);
    assertState(task, "READY_FOR_COSTING", null, "核算角色");
    assertThat(task.getReadyAt()).isNotNull();
    assertThat(task.getActiveFlag()).isZero();
    assertThat(task.getActiveLockKey()).isNull();
    assertThat(repository.findQuoteLinkById(linked.getId(), SCOPE).orElseThrow().getLinkStatus())
        .isEqualTo("RECHECKING");
    assertThat(taskMapper.refreshReadyProductCount(
        aggregate.master().getId(), BU, SYSTEM.userId(), SYSTEM.userName())).isOne();
    assertThat(repository.findTaskById(aggregate.master().getId(), BU).orElseThrow()
        .getReadyProductCount()).isOne();
    task = transition(task, ProductAction.START_COSTING, COSTING);
    assertState(task, "COSTING", 801L, "核算员");
    task = transition(task, ProductAction.COMPLETE_COSTING, COSTING);
    assertState(task, "COMPLETED", null, null);
  }

  @Test
  @DisplayName("取消产品任务在同一状态迁移中释放活动唯一锁")
  void cancelReleasesActiveLock() {
    Aggregate aggregate = createAggregate("CANCEL-LOCK");
    QuoteCollaborationQuoteLink linked = createLinkedQuoteLink(aggregate);
    QuoteCollaborationQuoteLink owner = repository.findLinksByProductTask(
        aggregate.product().getId(), SCOPE).stream()
        .filter(link -> "OWNER".equals(link.getLinkType()))
        .findFirst()
        .orElseThrow();

    QuoteCollaborationProductTask cancelled = transition(
        aggregate.product(), ProductAction.CANCEL, ADMIN);

    assertThat(cancelled.getTaskStatus()).isEqualTo("CANCELLED");
    assertThat(cancelled.getActiveFlag()).isZero();
    assertThat(cancelled.getActiveLockKey()).isNull();
    assertCancelledLink(owner);
    assertCancelledLink(linked);
  }

  @Test
  @DisplayName("主任务只有指定财务可审核，管理员不能代审")
  void masterReviewRequiresAssignedFinance() {
    Aggregate aggregate = createAggregate("MASTER");
    QuoteCollaborationTask waitingTask = masterStateService.transition(
        aggregate.master().getId(), aggregate.master().getTaskVersion(), BU,
        MasterAction.ROUTE_TO_FINANCE, SYSTEM);

    assertThatThrownBy(() -> masterStateService.transition(
        waitingTask.getId(), waitingTask.getTaskVersion(), BU,
        MasterAction.FINANCE_APPROVE, ADMIN))
        .isInstanceOfSatisfying(CollaborationDomainException.class, error ->
            assertThat(error.code()).isEqualTo(
                CollaborationDomainErrorCode.TASK_ASSIGNEE_MISMATCH));
    assertThat(readMaster(waitingTask).getMasterStatus()).isEqualTo("WAIT_FINANCE");

    QuoteCollaborationTask task = masterStateService.transition(
        waitingTask.getId(), waitingTask.getTaskVersion(), BU,
        MasterAction.FINANCE_REJECT, FINANCE);
    assertThat(task.getMasterStatus()).isEqualTo("PARTIAL_RETURN");
  }

  @Test
  @DisplayName("非本人、非法状态和旧页面提交全部拒绝且数据库不变")
  void invalidCommandsNeverMutateDatabase() {
    Aggregate aggregate = createAggregate("GUARD");
    QuoteCollaborationProductTask original = aggregate.product();

    assertDomainError(() -> productStateService.transition(
        original.getId(), original.getTaskVersion(), SCOPE,
        ProductAction.START_BOM, OUTSIDER),
        CollaborationDomainErrorCode.TASK_ASSIGNEE_MISMATCH);
    assertProductUnchanged(original);

    assertDomainError(() -> productStateService.transition(
        original.getId(), original.getTaskVersion(), SCOPE,
        ProductAction.APPROVE_FOR_PUBLISHING, FINANCE),
        CollaborationDomainErrorCode.STATE_TRANSITION_INVALID);
    assertProductUnchanged(original);

    assertDomainError(() -> productStateService.transition(
        original.getId(), original.getTaskVersion(), SCOPE,
        ProductAction.START_PRICE, TECH),
        CollaborationDomainErrorCode.STATE_TRANSITION_INVALID);
    assertProductUnchanged(original);

    QuoteCollaborationProductTask started = transition(original, ProductAction.START_BOM, TECH);
    assertDomainError(() -> productStateService.transition(
        started.getId(), original.getTaskVersion(), SCOPE,
        ProductAction.SUBMIT_TECH, TECH),
        CollaborationDomainErrorCode.TASK_VERSION_CONFLICT);
    assertThat(readProduct(started).getTaskStatus()).isEqualTo("BOM_IN_PROGRESS");
  }

  @Test
  @DisplayName("并发财务审核同一版本只有一次成功，另一请求得到版本冲突")
  void concurrentFinanceReviewHasSingleWinner() throws Exception {
    Aggregate aggregate = createAggregate("CONCURRENT");
    QuoteCollaborationProductTask task = aggregate.product();
    task = transition(task, ProductAction.START_BOM, TECH);
    task = transition(task, ProductAction.SUBMIT_TECH, TECH);
    QuoteCollaborationProductTask waiting = transition(
        task, ProductAction.ROUTE_TO_FINANCE, SYSTEM);
    int expectedVersion = waiting.getTaskVersion();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Object> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      for (int i = 0; i < 2; i++) {
        executor.submit(() -> {
          ready.countDown();
          try {
            start.await(5, TimeUnit.SECONDS);
            outcomes.add(productStateService.transition(
                waiting.getId(), expectedVersion, SCOPE,
                ProductAction.APPROVE_FOR_PUBLISHING, FINANCE));
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

    assertThat(outcomes).filteredOn(
        CollaborationProductStateService.ProductTransitionResult.class::isInstance).hasSize(1);
    assertThat(outcomes).filteredOn(CollaborationDomainException.class::isInstance)
        .singleElement().satisfies(value -> assertThat(
            ((CollaborationDomainException) value).code())
            .isEqualTo(CollaborationDomainErrorCode.TASK_VERSION_CONFLICT));
    QuoteCollaborationProductTask stored = readProduct(waiting);
    assertThat(stored.getTaskStatus()).isEqualTo("APPROVED_PUBLISHING");
    assertThat(stored.getTaskVersion()).isEqualTo(expectedVersion + 1);
  }

  @Test
  @DisplayName("状态迁移所在事务后续失败时状态、责任人和版本全部回滚")
  void rollsBackTransitionWhenLaterStepFails() {
    Aggregate aggregate = createAggregate("ROLLBACK");
    QuoteCollaborationProductTask original = aggregate.product();
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
      productStateService.transition(original.getId(), original.getTaskVersion(), SCOPE,
          ProductAction.START_BOM, TECH);
      throw new IllegalStateException("模拟后续步骤失败");
    })).isInstanceOf(IllegalStateException.class);

    assertProductUnchanged(original);
  }

  @Test
  @DisplayName("草稿、审核和报价关联也只能按合法动作迁移")
  void transitionsDraftReviewAndQuoteLinkThroughCentralServices() {
    Aggregate aggregate = createAggregate("SUB-STATES");
    QuotePriceDraft draft = createDraft(aggregate.product());
    QuoteCollaborationReview review = createReview(aggregate.master());
    QuoteCollaborationQuoteLink link = createQuoteLink(aggregate);

    draft = draftStateService.transition(
        draft.getId(), draft.getDraftVersion(), SCOPE, DraftAction.VALIDATE, TECH);
    draft = draftStateService.transition(
        draft.getId(), draft.getDraftVersion(), SCOPE, DraftAction.SUBMIT, TECH);
    QuoteCollaborationProductTask product = transition(
        aggregate.product(), ProductAction.START_BOM, TECH);
    product = transition(product, ProductAction.SUBMIT_TECH, TECH);
    transition(product, ProductAction.ROUTE_TO_FINANCE, SYSTEM);
    draft = draftStateService.transition(
        draft.getId(), draft.getDraftVersion(), SCOPE, DraftAction.APPROVE, FINANCE);
    draft = draftStateService.transition(
        draft.getId(), draft.getDraftVersion(), SCOPE, DraftAction.PUBLISH, SYSTEM);
    assertThat(draft.getDraftStatus()).isEqualTo("PUBLISHED");
    assertThat(draft.getPublishedAt()).isNotNull();

    review = reviewStateService.transition(
        review.getId(), review.getSourceTaskVersion(), review.getReviewStatus(), BU,
        ReviewAction.SAVE_PARTIAL, FINANCE);
    review = reviewStateService.transition(
        review.getId(), review.getSourceTaskVersion(), review.getReviewStatus(), BU,
        ReviewAction.SUBMIT_APPROVED, FINANCE);
    review = reviewStateService.transition(
        review.getId(), review.getSourceTaskVersion(), review.getReviewStatus(), BU,
        ReviewAction.START_PUBLISHING, SYSTEM);
    review = reviewStateService.transition(
        review.getId(), review.getSourceTaskVersion(), review.getReviewStatus(), BU,
        ReviewAction.MARK_FAILED, SYSTEM);
    review = reviewStateService.transition(
        review.getId(), review.getSourceTaskVersion(), review.getReviewStatus(), BU,
        ReviewAction.RETRY_PUBLISHING, SYSTEM);
    review = reviewStateService.transition(
        review.getId(), review.getSourceTaskVersion(), review.getReviewStatus(), BU,
        ReviewAction.MARK_EFFECTIVE, SYSTEM);
    assertThat(review.getReviewStatus()).isEqualTo("EFFECTIVE");
    assertThat(review.getEffectiveAt()).isNotNull();

    link = quoteLinkStateService.transition(
        link.getId(), SCOPE, QuoteLinkAction.START_RECHECK, SYSTEM);
    link = quoteLinkStateService.transition(
        link.getId(), SCOPE, QuoteLinkAction.MARK_FAILED, SYSTEM);
    link = quoteLinkStateService.transition(
        link.getId(), SCOPE, QuoteLinkAction.RETRY_RECHECK, SYSTEM);
    link = quoteLinkStateService.transition(
        link.getId(), SCOPE, QuoteLinkAction.MARK_READY, SYSTEM);
    assertThat(link.getLinkStatus()).isEqualTo("READY");
    assertThat(link.getReadyAt()).isNotNull();
  }

  @Test
  @DisplayName("草稿非责任人、过期审核来源和重复关联迁移均拒绝且不改库")
  void guardsDraftReviewAndQuoteLinkWithoutPartialMutation() {
    Aggregate aggregate = createAggregate("SUB-GUARDS");
    QuotePriceDraft draft = createDraft(aggregate.product());
    QuoteCollaborationReview review = createReview(aggregate.master());
    QuoteCollaborationQuoteLink link = createQuoteLink(aggregate);

    assertDomainError(() -> draftStateService.transition(
        draft.getId(), draft.getDraftVersion(), SCOPE, DraftAction.VALIDATE, OUTSIDER),
        CollaborationDomainErrorCode.TASK_ASSIGNEE_MISMATCH);
    assertThat(draftRepository.findById(draft.getId(), SCOPE).orElseThrow().getDraftStatus())
        .isEqualTo("EDITING");

    QuoteCollaborationTask changedMaster = masterStateService.transition(
        aggregate.master().getId(), aggregate.master().getTaskVersion(), BU,
        MasterAction.ROUTE_TO_FINANCE, SYSTEM);
    assertThat(changedMaster.getTaskVersion()).isGreaterThan(review.getSourceTaskVersion());
    assertDomainError(() -> reviewStateService.transition(
        review.getId(), review.getSourceTaskVersion(), review.getReviewStatus(), BU,
        ReviewAction.SUBMIT_APPROVED, FINANCE),
        CollaborationDomainErrorCode.TASK_VERSION_CONFLICT);
    assertThat(reviewRepository.findReviewById(review.getId(), BU).orElseThrow().getReviewStatus())
        .isEqualTo("PENDING");

    QuoteCollaborationQuoteLink rechecking = quoteLinkStateService.transition(
        link.getId(), SCOPE, QuoteLinkAction.START_RECHECK, SYSTEM);
    assertDomainError(() -> quoteLinkStateService.transition(
        rechecking.getId(), SCOPE, QuoteLinkAction.START_RECHECK, SYSTEM),
        CollaborationDomainErrorCode.STATE_TRANSITION_INVALID);
    assertThat(repository.findQuoteLinkById(link.getId(), SCOPE).orElseThrow().getLinkStatus())
        .isEqualTo("RECHECKING");
  }

  @Test
  @DisplayName("并发提交同一财务审核只有一个结果生效")
  void concurrentReviewDecisionHasSingleWinner() throws Exception {
    Aggregate aggregate = createAggregate("REVIEW-RACE");
    QuoteCollaborationReview review = createReview(aggregate.master());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Object> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      executor.submit(() -> runReviewDecision(
          review, ReviewAction.SUBMIT_APPROVED, ready, start, outcomes));
      executor.submit(() -> runReviewDecision(
          review, ReviewAction.SUBMIT_REJECTED, ready, start, outcomes));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      executor.shutdown();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(outcomes).filteredOn(QuoteCollaborationReview.class::isInstance).hasSize(1);
    assertThat(outcomes).filteredOn(CollaborationDomainException.class::isInstance)
        .singleElement().satisfies(value -> assertThat(
            ((CollaborationDomainException) value).code())
            .isEqualTo(CollaborationDomainErrorCode.TASK_VERSION_CONFLICT));
    assertThat(reviewRepository.findReviewById(review.getId(), BU).orElseThrow().getReviewStatus())
        .isIn("APPROVED", "REJECTED");
  }

  private QuoteCollaborationProductTask transition(
      QuoteCollaborationProductTask task,
      ProductAction action,
      CollaborationPrincipal principal) {
    return productStateService.transition(
        task.getId(), task.getTaskVersion(), SCOPE, action, principal).task();
  }

  private void runReviewDecision(
      QuoteCollaborationReview review,
      ReviewAction action,
      CountDownLatch ready,
      CountDownLatch start,
      List<Object> outcomes) {
    ready.countDown();
    try {
      start.await(5, TimeUnit.SECONDS);
      outcomes.add(reviewStateService.transition(
          review.getId(), review.getSourceTaskVersion(), review.getReviewStatus(), BU,
          action, FINANCE));
    } catch (Exception exception) {
      outcomes.add(exception);
    }
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
    master = repository.saveTask(master);

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
    product = repository.saveProductTask(product);
    return new Aggregate(master, product);
  }

  private QuotePriceDraft createDraft(QuoteCollaborationProductTask product) {
    QuotePriceDraft draft = new QuotePriceDraft();
    draft.setProductTaskId(product.getId());
    draft.setGapId(positiveKey("GAP-" + product.getProductCode()));
    draft.setMaterialCode("M-" + product.getProductCode());
    draft.setBusinessUnitType(BU);
    draft.setOrgCode(ORG);
    draft.setPriceType("FIXED_PURCHASE");
    draft.setSourceMode("DIRECT");
    draft.setTargetSourceType("FIXED_PURCHASE");
    draft.setDraftStatus("EDITING");
    return draftRepository.saveDraft(draft);
  }

  private QuoteCollaborationReview createReview(QuoteCollaborationTask master) {
    QuoteCollaborationReview review = new QuoteCollaborationReview();
    review.setCollaborationId(master.getId());
    review.setReviewRound(1);
    review.setReviewStatus("PENDING");
    review.setReviewerUserId(FINANCE.userId());
    review.setReviewerName(FINANCE.userName());
    review.setSourceTaskVersion(master.getTaskVersion());
    review.setProductCount(1);
    review.setPriceDraftCount(1);
    return reviewRepository.saveReview(review);
  }

  private QuoteCollaborationQuoteLink createQuoteLink(Aggregate aggregate) {
    QuoteCollaborationQuoteLink link = new QuoteCollaborationQuoteLink();
    link.setProductTaskId(aggregate.product().getId());
    link.setCollaborationId(aggregate.master().getId());
    link.setOaFormId(aggregate.master().getOaFormId());
    link.setOaFormItemId(positiveKey("ITEM-" + aggregate.product().getProductCode()));
    link.setOaNo(aggregate.master().getOaNo());
    link.setProductCode(aggregate.product().getProductCode());
    link.setAccountingMonth("2026-08");
    link.setApplicableOrgCode(ORG);
    link.setLinkType("OWNER");
    link.setLinkStatus("WAIT_SOURCE");
    link.setActiveLinkKey("OA_ITEM:" + link.getOaFormItemId());
    return repository.saveQuoteLink(link);
  }

  private QuoteCollaborationQuoteLink createLinkedQuoteLink(Aggregate aggregate) {
    QuoteCollaborationQuoteLink link = createQuoteLink(aggregate);
    link.setId(null);
    link.setLinkType("ACTIVE_TASK_LINK");
    link.setOaFormItemId(positiveKey("LINKED-ITEM-" + aggregate.product().getProductCode()));
    link.setActiveLinkKey("OA_ITEM:" + link.getOaFormItemId());
    return repository.saveQuoteLink(link);
  }

  private QuoteCollaborationProductTask readProduct(QuoteCollaborationProductTask task) {
    return repository.findProductTaskById(task.getId(), SCOPE).orElseThrow();
  }

  private void assertCancelledLink(QuoteCollaborationQuoteLink link) {
    QuoteCollaborationQuoteLink stored =
        repository.findQuoteLinkById(link.getId(), SCOPE).orElseThrow();
    assertThat(stored.getLinkStatus()).isEqualTo("CANCELLED");
    assertThat(stored.getActiveFlag()).isZero();
    assertThat(stored.getActiveLinkKey()).isNull();
  }

  private QuoteCollaborationTask readMaster(QuoteCollaborationTask task) {
    return repository.findTaskById(task.getId(), BU).orElseThrow();
  }

  private void assertProductUnchanged(QuoteCollaborationProductTask expected) {
    QuoteCollaborationProductTask stored = readProduct(expected);
    assertThat(stored.getTaskStatus()).isEqualTo(expected.getTaskStatus());
    assertThat(stored.getTaskVersion()).isEqualTo(expected.getTaskVersion());
    assertThat(stored.getCurrentAssigneeUserId())
        .isEqualTo(expected.getCurrentAssigneeUserId());
  }

  private static void assertState(
      QuoteCollaborationProductTask task,
      String status,
      Long assigneeId,
      String assigneeName) {
    assertThat(task.getTaskStatus()).isEqualTo(status);
    assertThat(task.getCurrentAssigneeUserId()).isEqualTo(assigneeId);
    assertThat(task.getCurrentAssigneeName()).isEqualTo(assigneeName);
  }

  private static void assertDomainError(
      Runnable action, CollaborationDomainErrorCode expectedCode) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(
        CollaborationDomainException.class,
        error -> assertThat(error.code()).isEqualTo(expectedCode));
  }

  private static CollaborationPrincipal principal(
      Long id, String name, CollaborationRole role) {
    return new CollaborationPrincipal(id, name, Set.of(role));
  }

  private static long positiveKey(String value) {
    return Integer.toUnsignedLong(value.hashCode()) + 1L;
  }

  private record Aggregate(
      QuoteCollaborationTask master,
      QuoteCollaborationProductTask product) {}
}
