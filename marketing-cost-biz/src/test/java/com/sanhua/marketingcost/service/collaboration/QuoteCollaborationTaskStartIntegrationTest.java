package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductForm;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanAction;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanStatus;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@DisplayName("QCBP-06 发起任务并发真实MySQL事务")
class QuoteCollaborationTaskStartIntegrationTest extends BomMapperTestBase {

  @Autowired private QuoteCollaborationTaskRepository repository;
  @Autowired private QuoteCollaborationReviewRepository reviewRepository;
  @Autowired private OaFormMapper formMapper;
  @Autowired private OaFormItemMapper itemMapper;
  @Autowired private CollaborationTaskLogService taskLogService;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final String suffix = UUID.randomUUID().toString().replace("-", "")
      .substring(0, 10);
  private final List<Long> formIds = new ArrayList<>();

  @BeforeAll
  static void createCollaborationSchema() throws Exception {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement()) {
      for (String resource : List.of(
          "/db/V206__quote_bom_price_collaboration_schema.sql",
          "/db/V210__quote_collaboration_gap_trace_fields.sql")) {
        // 多个协作集成测试共享同一个 Testcontainers MySQL；V210 可能已由先运行的
        // 测试建好。只在字段尚不存在时执行 ALTER，避免测试顺序造成重复列假失败。
        if (resource.contains("V210") && collaborationGapTraceColumnsExist(connection)) {
          continue;
        }
        try (InputStream in = QuoteCollaborationTaskStartIntegrationTest.class
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

  private static boolean collaborationGapTraceColumnsExist(Connection connection)
      throws Exception {
    try (var statement = connection.prepareStatement(
        "SELECT COUNT(*) FROM information_schema.columns "
            + "WHERE table_schema=DATABASE() AND table_name='lp_quote_collaboration_gap' "
            + "AND column_name IN "
            + "('bom_quantity','bom_unit','accounting_month','applicable_org_code')")) {
      try (var resultSet = statement.executeQuery()) {
        return resultSet.next() && resultSet.getInt(1) == 4;
      }
    }
  }

  @AfterEach
  void cleanRows() {
    jdbcTemplate.update("DELETE FROM lp_business_change_log "
        + "WHERE biz_domain='QUOTE_COLLABORATION' AND biz_type='PRODUCT_TASK_EVENT'");
    jdbcTemplate.update("DELETE FROM lp_quote_collaboration_approved_result WHERE 1=1");
    jdbcTemplate.update("DELETE FROM lp_quote_collaboration_review_item WHERE 1=1");
    jdbcTemplate.update("DELETE FROM lp_quote_collaboration_review WHERE 1=1");
    jdbcTemplate.update("DELETE FROM lp_quote_collaboration_gap WHERE 1=1");
    jdbcTemplate.update("DELETE FROM lp_quote_collaboration_quote_link WHERE 1=1");
    jdbcTemplate.update("DELETE FROM lp_quote_collaboration_product_task WHERE 1=1");
    jdbcTemplate.update("DELETE FROM lp_quote_collaboration_task WHERE 1=1");
    for (Long formId : formIds) {
      jdbcTemplate.update("DELETE FROM oa_form_item WHERE oa_form_id=?", formId);
      jdbcTemplate.update("DELETE FROM oa_form WHERE id=?", formId);
    }
    formIds.clear();
  }

  @Test
  @DisplayName("两个报价在两个事务同时发起只生成一个活动任务，另一请求关联原任务")
  void concurrentStartsCreateOneTaskAndTwoLinks() throws Exception {
    QuoteRow first = createQuote("A", "1008900001289");
    QuoteRow second = createQuote("B", "1008900001289");
    QuoteCollaborationScanService scanService = mock(QuoteCollaborationScanService.class);
    when(scanService.scanQuoteItem(first.itemId())).thenReturn(scan(first, "2026-08", "210", PrimaryScope.FULL_BOM));
    when(scanService.scanQuoteItem(second.itemId())).thenReturn(scan(second, "2026-08", "210", PrimaryScope.FULL_BOM));
    QuoteCollaborationTaskServiceImpl service = service(scanService);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch fire = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<QuoteCollaborationStartResult> firstStart = concurrentCall(
          ready, fire, service, first.itemId());
      Callable<QuoteCollaborationStartResult> secondStart = concurrentCall(
          ready, fire, service, second.itemId());
      Future<QuoteCollaborationStartResult> firstFuture = executor.submit(firstStart);
      Future<QuoteCollaborationStartResult> secondFuture = executor.submit(secondStart);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      fire.countDown();

      List<QuoteCollaborationStartResult> results = List.of(
          firstFuture.get(30, TimeUnit.SECONDS), secondFuture.get(30, TimeUnit.SECONDS));

      assertThat(results).extracting(QuoteCollaborationStartResult::action)
          .containsExactlyInAnyOrder(
              CollaborationStartAction.CREATED,
              CollaborationStartAction.LINKED_ACTIVE_TASK);
      assertThat(results).extracting(QuoteCollaborationStartResult::productTaskId)
          .containsOnly(results.get(0).productTaskId());
      assertThat(count("lp_quote_collaboration_product_task")).isEqualTo(1);
      assertThat(count("lp_quote_collaboration_quote_link")).isEqualTo(2);
      assertThat(jdbcTemplate.queryForObject(
          "SELECT COUNT(DISTINCT product_task_id) FROM lp_quote_collaboration_quote_link",
          Integer.class)).isEqualTo(1);
      assertThat(jdbcTemplate.queryForList(
          "SELECT link_type FROM lp_quote_collaboration_quote_link ORDER BY link_type",
          String.class)).containsExactly("ACTIVE_TASK_LINK", "OWNER");
      assertThat(jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM lp_business_change_log WHERE biz_domain='QUOTE_COLLABORATION' "
              + "AND biz_type='PRODUCT_TASK_EVENT' AND field_name IN "
              + "('TECH_TASK_CREATED','TECH_TASK_LINKED')", Integer.class)).isEqualTo(2);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("50个并发发起同一报价产品只创建一个活动任务和一个报价关联")
  void fiftyConcurrentStartsAreIdempotent() throws Exception {
    QuoteRow row = createQuote("FIFTY", "1008900001289");
    QuoteCollaborationScanService scanService = mock(QuoteCollaborationScanService.class);
    when(scanService.scanQuoteItem(row.itemId()))
        .thenReturn(scan(row, "2026-08", "210", PrimaryScope.FULL_BOM));
    QuoteCollaborationTaskServiceImpl service = service(scanService);
    int requests = 50;
    CountDownLatch ready = new CountDownLatch(requests);
    CountDownLatch fire = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(requests);
    try {
      List<Future<QuoteCollaborationStartResult>> futures = new java.util.ArrayList<>();
      for (int index = 0; index < requests; index++) {
        futures.add(executor.submit(concurrentCall(ready, fire, service, row.itemId())));
      }
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      fire.countDown();
      List<QuoteCollaborationStartResult> results = new java.util.ArrayList<>();
      for (Future<QuoteCollaborationStartResult> future : futures) {
        results.add(future.get(60, TimeUnit.SECONDS));
      }

      assertThat(results).hasSize(requests);
      assertThat(results).extracting(QuoteCollaborationStartResult::productTaskId)
          .containsOnly(results.get(0).productTaskId());
      assertThat(count("lp_quote_collaboration_task")).isEqualTo(1);
      assertThat(count("lp_quote_collaboration_product_task")).isEqualTo(1);
      assertThat(count("lp_quote_collaboration_quote_link")).isEqualTo(1);
      assertThat(jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM lp_business_change_log WHERE biz_domain='QUOTE_COLLABORATION' "
              + "AND biz_type='PRODUCT_TASK_EVENT' AND field_name='TECH_TASK_CREATED'",
          Integer.class)).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("同一报价单两个不同产品并发发起只生成一个主任务并正确累计产品数")
  void concurrentProductsInSameFormShareOneMasterTask() throws Exception {
    QuoteRow first = createQuote("SAME-FORM-A", "1008900001289");
    QuoteRow second = createQuoteItem(first, "SAME-FORM-B", "100110005060");
    QuoteCollaborationScanService scanService = mock(QuoteCollaborationScanService.class);
    when(scanService.scanQuoteItem(first.itemId()))
        .thenReturn(scan(first, "2026-08", "210", PrimaryScope.FULL_BOM));
    when(scanService.scanQuoteItem(second.itemId()))
        .thenReturn(scan(second, "2026-08", "210", PrimaryScope.FULL_BOM));
    QuoteCollaborationTaskServiceImpl service = service(scanService);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch fire = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<QuoteCollaborationStartResult> firstFuture = executor.submit(
          concurrentCall(ready, fire, service, first.itemId()));
      Future<QuoteCollaborationStartResult> secondFuture = executor.submit(
          concurrentCall(ready, fire, service, second.itemId()));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      fire.countDown();

      assertThat(List.of(
          firstFuture.get(30, TimeUnit.SECONDS),
          secondFuture.get(30, TimeUnit.SECONDS)))
          .extracting(QuoteCollaborationStartResult::action)
          .containsOnly(CollaborationStartAction.CREATED);
      assertThat(count("lp_quote_collaboration_task")).isEqualTo(1);
      assertThat(count("lp_quote_collaboration_product_task")).isEqualTo(2);
      assertThat(count("lp_quote_collaboration_quote_link")).isEqualTo(2);
      assertThat(jdbcTemplate.queryForObject(
          "SELECT owned_product_count FROM lp_quote_collaboration_task", Integer.class))
          .isEqualTo(2);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("同一报价顺序重复点击返回同一OWNER关联且不重复事件")
  void repeatedStartIsIdempotent() {
    QuoteRow row = createQuote("REPEAT", "1008900001289");
    QuoteCollaborationScanService scanService = mock(QuoteCollaborationScanService.class);
    when(scanService.scanQuoteItem(row.itemId()))
        .thenReturn(scan(row, "2026-08", "210", PrimaryScope.FULL_BOM));
    QuoteCollaborationTaskServiceImpl service = service(scanService);

    QuoteCollaborationStartResult first = inTransaction(() -> service.start(command(row.itemId())));
    QuoteCollaborationStartResult second = inTransaction(() -> service.start(command(row.itemId())));

    assertThat(first.action()).isEqualTo(CollaborationStartAction.CREATED);
    assertThat(second.action()).isEqualTo(CollaborationStartAction.CREATED);
    assertThat(second.idempotentReplay()).isTrue();
    assertThat(second.productTaskId()).isEqualTo(first.productTaskId());
    assertThat(second.quoteLinkId()).isEqualTo(first.quoteLinkId());
    assertThat(count("lp_quote_collaboration_product_task")).isEqualTo(1);
    assertThat(count("lp_quote_collaboration_quote_link")).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lp_business_change_log WHERE biz_domain='QUOTE_COLLABORATION' "
            + "AND biz_type='PRODUCT_TASK_EVENT'", Integer.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("同产品跨月份和缺口阶段共用任务，不同组织相互隔离")
  void lockDimensionsAreIsolatedInDatabase() {
    List<QuoteRow> rows = List.of(
        createQuote("MONTH", "1008900001289"),
        createQuote("ORG", "1008900001289"),
        createQuote("SCOPE", "1008900001289"));
    QuoteCollaborationScanService scanService = mock(QuoteCollaborationScanService.class);
    when(scanService.scanQuoteItem(rows.get(0).itemId()))
        .thenReturn(scan(rows.get(0), "2026-09", "210", PrimaryScope.FULL_BOM));
    when(scanService.scanQuoteItem(rows.get(1).itemId()))
        .thenReturn(scan(rows.get(1), "2026-08", "220", PrimaryScope.FULL_BOM));
    when(scanService.scanQuoteItem(rows.get(2).itemId()))
        .thenReturn(scan(rows.get(2), "2026-08", "210", PrimaryScope.PRICE_ONLY));
    QuoteCollaborationTaskServiceImpl service = service(scanService);

    List<QuoteCollaborationStartResult> results = rows.stream()
        .map(row -> inTransaction(() -> service.start(command(row.itemId()))))
        .toList();

    assertThat(results).extracting(QuoteCollaborationStartResult::action)
        .containsExactly(
            CollaborationStartAction.CREATED,
            CollaborationStartAction.CREATED,
            CollaborationStartAction.LINKED_ACTIVE_TASK);
    assertThat(results.get(2).productTaskId()).isEqualTo(results.get(0).productTaskId());
    assertThat(count("lp_quote_collaboration_product_task")).isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(DISTINCT active_lock_key) FROM lp_quote_collaboration_product_task",
        Integer.class)).isEqualTo(2);
  }

  @Test
  @DisplayName("两个无料号报价只要组织和型号相同就共用一个进行中任务")
  void modelLocksNewProductsAcrossQuotes() {
    QuoteRow first = createQuote("MODEL-A", null);
    QuoteRow second = createQuote("MODEL-B", null);
    QuoteCollaborationScanService scanService = mock(QuoteCollaborationScanService.class);
    when(scanService.scanQuoteItem(first.itemId()))
        .thenReturn(scan(first, "2026-08", "210", PrimaryScope.FULL_BOM));
    when(scanService.scanQuoteItem(second.itemId()))
        .thenReturn(scan(second, "2026-09", "210", PrimaryScope.FULL_BOM));
    QuoteCollaborationTaskServiceImpl service = service(scanService);

    QuoteCollaborationStartResult created = inTransaction(
        () -> service.start(command(first.itemId())));
    QuoteCollaborationStartResult linked = inTransaction(
        () -> service.start(command(second.itemId())));

    assertThat(created.action()).isEqualTo(CollaborationStartAction.CREATED);
    assertThat(linked.action()).isEqualTo(CollaborationStartAction.LINKED_ACTIVE_TASK);
    assertThat(linked.productTaskId()).isEqualTo(created.productTaskId());
    assertThat(count("lp_quote_collaboration_product_task")).isEqualTo(1);
    assertThat(count("lp_quote_collaboration_quote_link")).isEqualTo(2);
  }

  @Test
  @DisplayName("原任务取消后释放活动锁，同组织同料号可以重新创建任务")
  void terminalTaskReleasesLockForNewTask() {
    QuoteRow first = createQuote("RELEASE-A", "1008900001289");
    QuoteRow second = createQuote("RELEASE-B", "1008900001289");
    QuoteCollaborationScanService scanService = mock(QuoteCollaborationScanService.class);
    when(scanService.scanQuoteItem(first.itemId()))
        .thenReturn(scan(first, "2026-08", "210", PrimaryScope.FULL_BOM));
    when(scanService.scanQuoteItem(second.itemId()))
        .thenReturn(scan(second, "2026-09", "210", PrimaryScope.FULL_BOM));
    QuoteCollaborationTaskServiceImpl service = service(scanService);

    QuoteCollaborationStartResult firstResult = inTransaction(
        () -> service.start(command(first.itemId())));
    QuoteCollaborationProductTask active = repository.findProductTaskById(
        firstResult.productTaskId(), new CollaborationScope("COMMERCIAL", "210")).orElseThrow();
    inTransaction(() -> {
      repository.transitionProductTaskStatus(
          active.getId(), active.getTaskVersion(), active.getTaskStatus(), "CANCELLED",
          null, null, new CollaborationScope("COMMERCIAL", "210"),
          new CollaborationActor(901L, "报价员"));
      return Boolean.TRUE;
    });

    QuoteCollaborationStartResult secondResult = inTransaction(
        () -> service.start(command(second.itemId())));

    assertThat(secondResult.action()).isEqualTo(CollaborationStartAction.CREATED);
    assertThat(secondResult.productTaskId()).isNotEqualTo(firstResult.productTaskId());
    assertThat(count("lp_quote_collaboration_product_task")).isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_collaboration_product_task "
            + "WHERE active_flag=1 AND active_lock_key IS NOT NULL",
        Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_collaboration_product_task "
            + "WHERE task_status='CANCELLED' AND active_flag=0 AND active_lock_key IS NULL",
        Integer.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("QCBP-07复用结果在真实MySQL只新增READY关联，不复制技术任务和价格成本对象")
  void approvedResultReuseCreatesOnlyReadyLink() {
    QuoteRow source = createQuote("REUSE-SOURCE", "1008900001289");
    QuoteCollaborationScanService sourceScan = mock(QuoteCollaborationScanService.class);
    when(sourceScan.scanQuoteItem(source.itemId()))
        .thenReturn(scan(source, "2026-08", "210", PrimaryScope.FULL_BOM));
    QuoteCollaborationStartResult sourceStart = inTransaction(
        () -> service(sourceScan).start(command(source.itemId())));
    jdbcTemplate.update(
        "UPDATE lp_quote_collaboration_product_task SET task_status='READY_FOR_COSTING', "
            + "active_flag=0, active_lock_key=NULL WHERE id=?",
        sourceStart.productTaskId());
    QuoteCollaborationReview review = new QuoteCollaborationReview();
    review.setCollaborationId(jdbcTemplate.queryForObject(
        "SELECT origin_collaboration_id FROM lp_quote_collaboration_product_task WHERE id=?",
        Long.class, sourceStart.productTaskId()));
    review.setReviewRound(1);
    review.setReviewStatus("EFFECTIVE");
    review.setReviewerUserId(701L);
    review.setReviewerName("财务审核员");
    review.setSourceTaskVersion(1);
    review.setProductCount(1);
    review.setPriceDraftCount(0);
    review.setEffectiveAt(LocalDateTime.of(2026, 8, 12, 10, 30));
    reviewRepository.saveReview(review);
    QuoteCollaborationApprovedResult approved = new QuoteCollaborationApprovedResult();
    approved.setResultType("FULL_BOM");
    approved.setSourceProductTaskId(sourceStart.productTaskId());
    approved.setSourceReviewId(review.getId());
    approved.setProductCode(source.productCode());
    approved.setProductForm("NORMAL");
    approved.setApplicableOrgCode("210");
    approved.setSourceObjectType("SUPPLEMENT_VERSION");
    approved.setSourceObjectId(999L);
    approved.setSourceSystem("ELECTRONIC_DRAWING");
    approved.setStructureFingerprint("a".repeat(64));
    approved.setValidityPolicyCode("COLLAB_RESULT_SIX_MONTHS_V1");
    approved.setValidityMonths(6);
    approved.setValidFrom(LocalDateTime.of(2026, 8, 12, 10, 30));
    approved.setValidUntil(LocalDateTime.of(2027, 2, 12, 10, 30));
    approved.setResultStatus("ACTIVE");
    reviewRepository.saveApprovedResult(approved);

    QuoteRow current = createQuote("REUSE-CURRENT", source.productCode());
    QuoteCollaborationScanService currentScan = mock(QuoteCollaborationScanService.class);
    when(currentScan.scanQuoteItem(current.itemId())).thenReturn(reuseScan(current, approved.getId()));
    int taskCountBefore = count("lp_quote_collaboration_product_task");
    int draftCountBefore = count("lp_quote_price_draft");
    int prepareBatchCountBefore = count("lp_price_prepare_batch");
    int prepareItemCountBefore = count("lp_price_prepare_item");
    int costVersionCountBefore = count("lp_quote_cost_run_version");

    QuoteCollaborationStartResult reused = inTransaction(
        () -> service(currentScan).start(new QuoteCollaborationStartCommand(
            current.itemId(), null, null, null, null,
            new CollaborationActor(901L, "报价员"))));

    assertThat(reused.action()).isEqualTo(CollaborationStartAction.REUSED_APPROVED_RESULT);
    assertThat(count("lp_quote_collaboration_product_task")).isEqualTo(taskCountBefore);
    assertThat(count("lp_quote_price_draft")).isEqualTo(draftCountBefore);
    assertThat(count("lp_price_prepare_batch")).isEqualTo(prepareBatchCountBefore);
    assertThat(count("lp_price_prepare_item")).isEqualTo(prepareItemCountBefore);
    assertThat(count("lp_quote_cost_run_version")).isEqualTo(costVersionCountBefore);
    assertThat(jdbcTemplate.queryForMap(
        "SELECT link_type, link_status, approved_result_id, latest_price_prepare_no "
            + "FROM lp_quote_collaboration_quote_link WHERE oa_form_item_id=?",
        current.itemId()))
        .containsEntry("link_type", "APPROVED_RESULT_REUSE")
        .containsEntry("link_status", "READY")
        .containsEntry("approved_result_id", approved.getId())
        .containsEntry("latest_price_prepare_no", null);
  }

  private Callable<QuoteCollaborationStartResult> concurrentCall(
      CountDownLatch ready,
      CountDownLatch fire,
      QuoteCollaborationTaskServiceImpl service,
      Long itemId) {
    return () -> {
      ready.countDown();
      assertThat(fire.await(10, TimeUnit.SECONDS)).isTrue();
      return inTransaction(() -> service.start(command(itemId)));
    };
  }

  private QuoteCollaborationTaskServiceImpl service(QuoteCollaborationScanService scanService) {
    return new QuoteCollaborationTaskServiceImpl(
        scanService, repository, reviewRepository, itemMapper, formMapper,
        taskLogService);
  }

  private QuoteCollaborationStartCommand command(Long itemId) {
    return new QuoteCollaborationStartCommand(
        itemId, 601L, "王工", 701L, "财务审核员",
        new CollaborationActor(901L, "报价员"));
  }

  private <T> T inTransaction(java.util.function.Supplier<T> action) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return template.execute(status -> action.get());
  }

  private QuoteRow createQuote(String marker, String productCode) {
    OaForm form = new OaForm();
    form.setOaNo("QCBP06-" + suffix + "-" + marker);
    form.setProcessCode("FI-SC-006");
    form.setApplyDate(LocalDate.of(2026, 8, 13));
    form.setAccountingPeriodMonth("2026-08");
    form.setBusinessUnitType("COMMERCIAL");
    form.setSourceSystem("OA");
    formMapper.insert(form);
    formIds.add(form.getId());
    OaFormItem item = new OaFormItem();
    item.setOaFormId(form.getId());
    item.setExternalLineId("LINE-" + marker);
    item.setSeq(1);
    item.setMaterialNo(productCode);
    item.setProductName("热力膨胀阀");
    item.setSpec("4.5");
    item.setSunlModel("RFKH11E-4.5-54A");
    item.setBusinessUnitType("COMMERCIAL");
    itemMapper.insert(item);
    return new QuoteRow(form.getId(), item.getId(), form.getOaNo(), productCode);
  }

  private QuoteRow createQuoteItem(QuoteRow formRow, String marker, String productCode) {
    OaFormItem item = new OaFormItem();
    item.setOaFormId(formRow.formId());
    item.setExternalLineId("LINE-" + marker);
    item.setSeq(2);
    item.setMaterialNo(productCode);
    item.setProductName("电磁阀阀体");
    item.setSpec("HDF19");
    item.setSunlModel("HDF19DK02");
    item.setBusinessUnitType("COMMERCIAL");
    itemMapper.insert(item);
    return new QuoteRow(formRow.formId(), item.getId(), formRow.oaNo(), productCode);
  }

  private QuoteCollaborationScanResult scan(
      QuoteRow row, String month, String org, PrimaryScope primaryScope) {
    CollaborationPriceScanResult price = primaryScope == PrimaryScope.PRICE_ONLY
        ? CollaborationPriceScanResult.gaps(
            1, List.of(new CollaborationPriceScanResult.PriceGap(
                "RAW-001", "MISSING_PRICE", "MAINTAIN_PRICE", "当前无价格",
                "lp_price_fixed_item", "FIXED_PURCHASE")))
        : CollaborationPriceScanResult.pendingBom("等待补BOM");
    return new QuoteCollaborationScanResult(
        row.itemId(), row.oaNo(), month, row.productCode(), "COMMERCIAL", org, org,
        ProductForm.NORMAL, QuoteCollaborationScanStatus.COLLABORATION_REQUIRED,
        QuoteCollaborationScanAction.CREATE_COLLABORATION, primaryScope, null, null, 0,
        null, null, null, price, List.of(), null, "需要技术协作");
  }

  private QuoteCollaborationScanResult reuseScan(QuoteRow row, Long approvedResultId) {
    return new QuoteCollaborationScanResult(
        row.itemId(), row.oaNo(), "2026-08", row.productCode(),
        "COMMERCIAL", "210", "210", ProductForm.NORMAL,
        QuoteCollaborationScanStatus.REUSABLE_RESULT,
        QuoteCollaborationScanAction.REUSE_APPROVED_RESULT, null,
        "ELECTRONIC_DRAWING", null, 8, null, null, approvedResultId,
        CollaborationPriceScanResult.ready(8), List.of(), null,
        "已审核结果可复用，本次价格重新检查通过");
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  private record QuoteRow(Long formId, Long itemId, String oaNo, String productCode) {}
}
