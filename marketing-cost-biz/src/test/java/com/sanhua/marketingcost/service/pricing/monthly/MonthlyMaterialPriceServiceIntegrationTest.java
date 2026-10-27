package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.*;

/** The public module is exercised through real SQL and independent transactions. */
@Tag("integration")
class MonthlyMaterialPriceServiceIntegrationTest {
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(
              DockerImageName.parse(System.getProperty("mpp.mysql.image", "mysql:8.4"))
                  .asCompatibleSubstituteFor("mysql"))
          .withDatabaseName("mpp_module_test")
          .withUsername("root")
          .withPassword("mpp-module-test-only")
          .withConfigurationOverride("monthly-pricing/mysql");
  static JdbcTemplate jdbc;
  static MonthlyMaterialPriceService module;
  static MonthlyPriceStorage storage;
  static HouseholdMonthlyPriceSource householdSource;
  static com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryService inquiries;
  static MonthlyPriceDocumentCodec codec;
  static final AtomicInteger preparedStatements = new AtomicInteger();
  static final AtomicInteger sourceCalls = new AtomicInteger();

  @BeforeAll
  static void database() throws Exception {
    MYSQL.start();
    var raw =
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    var ds =
        new DelegatingDataSource(raw) {
          @Override
          public java.sql.Connection getConnection() throws java.sql.SQLException {
            var connection = super.getConnection();
            return (java.sql.Connection)
                java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {java.sql.Connection.class},
                    (proxy, method, args) -> {
                      if (method.getName().equals("prepareStatement"))
                        preparedStatements.incrementAndGet();
                      try {
                        return method.invoke(connection, args);
                      } catch (java.lang.reflect.InvocationTargetException error) {
                        throw error.getCause();
                      }
                    });
          }
        };
    jdbc = new JdbcTemplate(ds);
    assertThat(jdbc.queryForObject("SELECT @@version", String.class))
        .startsWith(System.getProperty("mpp.mysql.expected-version", "8.4"));
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/pre-v342-schema.sql"))
        .execute(ds);
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("db/V342__monthly_material_price_storage.sql"),
        "/tmp/monthly.sql");
    var applied =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4"
                + " mpp_module_test < /tmp/monthly.sql");
    assertThat(applied.getExitCode()).withFailMessage(applied.getStderr()).isZero();
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("db/V344__monthly_material_price_single_table.sql"),
        "/tmp/monthly-use.sql");
    var scopePatch =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4"
                + " mpp_module_test < /tmp/monthly-use.sql");
    assertThat(scopePatch.getExitCode()).withFailMessage(scopePatch.getStderr()).isZero();
    var config = new org.apache.ibatis.session.Configuration();
    config.setMapUnderscoreToCamelCase(true);
    config.addMapper(MonthlyPriceStorageMapper.class);
    config.addMapper(MonthlyPriceLinkMapper.class);
    config.addMapper(HouseholdInquiryStorageMapper.class);
    config.addMapper(com.sanhua.marketingcost.mapper.household.HouseholdInquiryQueryMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(ds);
    factory.setConfiguration(config);
    var sql = new SqlSessionTemplate(factory.getObject());
    codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
    storage =
        new MonthlyPriceStorage(
            sql.getMapper(MonthlyPriceStorageMapper.class),
            codec,
            sql.getMapper(MonthlyPriceLinkMapper.class));
    module =
        new MonthlyMaterialPriceService(
            storage,
            new DataSourceTransactionManager(ds),
            codec,
            (context, requests, dependencyPrices) -> {
              sourceCalls.incrementAndGet();
              var candidates = new LinkedHashMap<MonthlyPriceRequest, Candidate>();
              for (var request : requests)
                candidates.put(
                    request, new Candidate(new Selection(null, shared(), normal("10")), null));
              return new Candidates(candidates, Map.of());
            });
    householdSource =
        new HouseholdMonthlyPriceSource(
            new HouseholdInquiryStorage(sql.getMapper(HouseholdInquiryStorageMapper.class), codec),
            codec, org.mockito.Mockito.mock(com.sanhua.marketingcost.service.MaterialPricePriorityService.class));
    inquiries =
        new com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryService(
            sql.getMapper(
                com.sanhua.marketingcost.mapper.household.HouseholdInquiryQueryMapper.class),
            new HouseholdInquiryStorage(sql.getMapper(HouseholdInquiryStorageMapper.class), codec),
            new com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryWorkbookParser(),
            new com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryFiles(
                java.nio.file.Files.createTempDirectory("mpp05-household-").toString()),
            codec,
            new DataSourceTransactionManager(ds),
            new ObjectMapper());
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  static Context context() {
    return new Context(
        "COMMERCIAL",
        "MPP05",
        1L,
        "TOP",
        YearMonth.of(2026, 10),
        LocalDateTime.of(2026, 10, 23, 0, 0),
        "test",
        "210");
  }

  static MonthlyPriceRequest request(String code, String org) {
    return new MonthlyPriceRequest(
        new MonthlyPriceFamilyKey(
            YearMonth.of(2026, 10), "COMMERCIAL", code, null, "件", "CNY", TaxBasis.EXCLUDED, null),
        org,
        NonCuConditions.empty(),
        BigDecimal.ONE);
  }

  static MonthlyPriceApplicability shared() {
    return MonthlyPriceApplicability.of(
        new MonthlyPriceCoverage(
            Set.of("210", "220"), QuantityInterval.all(), ConditionDomain.any()));
  }

  static PriceFact normal(String price) {
    return PriceFact.normal(
        new BigDecimal(price),
        SourceEvidence.ofJson(
            "{\"snapshotSchema\":1,\"sourceKind\":\"FIXED\",\"rawPrice\":" + price + "}"),
        Set.of());
  }

  static MonthlyAdoptionPlan plan(MonthlyPriceRequest request, Selection selected) {
    return new MonthlyAdoptionPlan(
        Map.of(request, selected),
        List.of(new Use("root/part", request, BigDecimal.ONE)),
        Set.of());
  }

  static long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  @Test
  void
      candidateAndLookupAreReadOnlyAndFirstUseReturnsDatabaseFactThenOtherOrgReusesWithoutSources() {
    var request = request("MPP05-" + UUID.randomUUID(), "210");
    long before = count("lp_material_monthly_price"), receipts = count("lp_material_monthly_price");
    var lookup = module.lookup(List.of(request));
    assertThat(lookup.missing()).containsExactly(request);
    var candidates = module.resolveCandidates(context(), Set.of(request));
    assertThat(count("lp_material_monthly_price")).isEqualTo(before);
    var receipt =
        module.adopt(context(), plan(request, candidates.values().get(request).selection()));
    var saved = receipt.plan().selections().get(request);
    assertThat(saved.monthlyPriceId()).isPositive();
    assertThat(saved.fact().price()).isEqualByComparingTo("10");
    assertThat(storage.readByIds("COMMERCIAL", Set.of(saved.monthlyPriceId())))
        .singleElement()
        .satisfies(row -> assertThat(row.evidence()).isEqualTo(saved.fact().evidence()));
    int calls = sourceCalls.get();
    var other = request(request.family().materialCode(), "220");
    var reused = module.resolveCandidates(context(), Set.of(other));
    assertThat(reused.values().get(other).selection().monthlyPriceId())
        .isEqualTo(saved.monthlyPriceId());
    assertThat(sourceCalls.get()).isEqualTo(calls);
    assertThat(count("lp_material_monthly_price")).isEqualTo(before + 1);
  }

  @Test
  void twoConsumerAliasesAdoptOneSharedFactAndKeepBothUseContexts() {
    String code = "MPP05-" + UUID.randomUUID();
    var left = request(code, "210");
    var right = request(code, "220");
    var selected = new Selection(null, shared(), normal("12"));
    long before = count("lp_material_monthly_price");
    var plan =
        new MonthlyAdoptionPlan(
            Map.of(left, selected, right, selected),
            List.of(
                new Use("root/left", left, BigDecimal.ONE),
                new Use("root/right", right, new BigDecimal("2"))),
            Set.of());
    var receipt = module.adopt(context(), plan);
    long id = receipt.plan().selections().get(left).monthlyPriceId();
    assertThat(receipt.plan().selections().get(right).monthlyPriceId()).isEqualTo(id);
    assertThat(count("lp_material_monthly_price")).isEqualTo(before + 1);
  }

  @Test
  void sourceSharedCoverageOnlyFillsTheRemainingOrganization() {
    String code = "MPP05-" + UUID.randomUUID();
    var left = request(code, "210");
    var right = request(code, "220");
    var only210 =
        MonthlyPriceApplicability.of(
            new MonthlyPriceCoverage(Set.of("210"), QuantityInterval.all(), ConditionDomain.any()));
    var old = module.adopt(context(), plan(left, new Selection(null, only210, normal("5"))));
    var candidate =
        module.resolveCandidates(context(), Set.of(right)).values().get(right).selection();
    assertThat(candidate.applicability().matches(left)).isFalse();
    assertThat(candidate.applicability().matches(right)).isTrue();
    var saved = module.adopt(context(), plan(right, candidate));
    assertThat(saved.plan().selections().get(right).fact().price()).isEqualByComparingTo("10");
    assertThat(module.lookup(List.of(left)).values().get(left).monthlyPriceId())
        .isEqualTo(old.plan().selections().get(left).monthlyPriceId());
  }

  @Test
  void savedDependencyGraphIsRestoredWithoutCallingCurrentSources() {
    var parent = request("MPP05-parent-" + UUID.randomUUID(), "210");
    var child = request("MPP05-child-" + UUID.randomUUID(), "210");
    var parentFact =
        PriceFact.normal(
            new BigDecimal("30"),
            SourceEvidence.ofJson("{\"sourceKind\":\"LINKED_LEGACY\",\"coefficient\":3}"),
            Set.of(child));
    var plan =
        new MonthlyAdoptionPlan(
            Map.of(
                parent,
                new Selection(null, shared(), parentFact),
                child,
                new Selection(null, shared(), normal("10"))),
            List.of(new Use("root/make", parent, BigDecimal.ONE)),
            Set.of());
    var receipt = module.adopt(context(), plan);
    int calls = sourceCalls.get();
    var restored = module.lookup(List.of(parent));
    assertThat(restored.values()).isEqualTo(receipt.plan().selections());
    assertThat(sourceCalls.get()).isEqualTo(calls);
    long parentId = restored.values().get(parent).monthlyPriceId(),
        childId = restored.values().get(child).monthlyPriceId();
    assertThat(firstUse(parentId).dependencies())
        .extracting(MonthlyMaterialPriceService.Dependency::monthlyPriceId)
        .containsExactly(childId);
  }

  @Test
  void invalidOrphanPlanRollsBackEveryFactAndEmptyPlanWritesNothing() {
    var request = request("MPP05-" + UUID.randomUUID(), "210");
    long prices = count("lp_material_monthly_price");
    var orphan =
        new MonthlyAdoptionPlan(
            Map.of(request, new Selection(null, shared(), normal("10"))), List.of(), Set.of());
    assertThatThrownBy(() -> module.adopt(context(), orphan))
        .isInstanceOfSatisfying(
            MonthlyPriceProblem.class,
            e -> assertThat(e.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_PLAN));
    var empty = module.adopt(context(), new MonthlyAdoptionPlan(Map.of(), List.of(), Set.of()));
    assertThat(empty.plan().selections()).isEmpty();
    assertThat(count("lp_material_monthly_price")).isEqualTo(prices);
  }

  @Test
  void independentThreadsAllReturnTheSameActuallyCommittedSharedPrice() throws Exception {
    String code = "MPP05-race-" + UUID.randomUUID();
    long before = count("lp_material_monthly_price");
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(16)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var futures = new ArrayList<java.util.concurrent.Future<AdoptionResult>>();
      for (int i = 0; i < 16; i++) {
        var request = request(code, i % 2 == 0 ? "210" : "220");
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return new MonthlyAdoptionExecutor(module)
                      .execute(
                          context(),
                          budget -> plan(request, new Selection(null, shared(), normal("10"))));
                }));
      }
      start.countDown();
      var ids = new HashSet<Long>();
      for (var future : futures)
        ids.add(
            future
                .get(10, java.util.concurrent.TimeUnit.SECONDS)
                .plan()
                .selections()
                .values()
                .iterator()
                .next()
                .monthlyPriceId());
      assertThat(ids).hasSize(1);
      assertThat(count("lp_material_monthly_price")).isEqualTo(before + 1);
    }
  }

  @Test
  void restoringManyDependencyFactsUsesBatchReadsAndNoPerCandidateFamilyQueries() {
    var selections = new LinkedHashMap<MonthlyPriceRequest, Selection>();
    var uses = new ArrayList<Use>();
    var roots = new ArrayList<MonthlyPriceRequest>();
    for (int i = 0; i < 20; i++) {
      var parent = request("MPP05-batch-parent-" + UUID.randomUUID(), "210");
      var child = request("MPP05-batch-child-" + UUID.randomUUID(), "210");
      roots.add(parent);
      selections.put(
          parent,
          new Selection(
              null,
              shared(),
              PriceFact.normal(
                  new BigDecimal("30"),
                  SourceEvidence.ofJson("{\"sourceKind\":\"LINKED_LEGACY\",\"coefficient\":3}"),
                  Set.of(child))));
      selections.put(child, new Selection(null, shared(), normal("10")));
      uses.add(new Use("root/" + i, parent, BigDecimal.ONE));
    }
    module.adopt(context(), new MonthlyAdoptionPlan(selections, uses, Set.of()));
    preparedStatements.set(0);
    var restored = module.lookup(roots);
    int statements = preparedStatements.get();
    assertThat(restored.missing()).isEmpty();
    assertThat(restored.values()).hasSize(40);
    assertThat(statements)
        .as("actual prepared SQL, including saved dependency/family reads")
        .isLessThanOrEqualTo(12);
  }

  @Test
  void differentDependencyAliasesToOnePhysicalChildSaveOnlyOneTypedReference() {
    var parent = request("MPP05-alias-parent-" + UUID.randomUUID(), "210");
    String childCode = "MPP05-alias-child-" + UUID.randomUUID();
    var child = request(childCode, "210");
    var alias = request(childCode, "220");
    var fact =
        PriceFact.normal(
            new BigDecimal("30"),
            SourceEvidence.ofJson("{\"sourceKind\":\"LINKED_LEGACY\",\"coefficient\":3}"),
            Set.of(child, alias));
    var childSelection = new Selection(null, shared(), normal("10"));
    var plan =
        new MonthlyAdoptionPlan(
            Map.of(
                parent,
                new Selection(null, shared(), fact),
                child,
                childSelection,
                alias,
                childSelection),
            List.of(new Use("root", parent, BigDecimal.ONE)),
            Set.of());
    var receipt = module.adopt(context(), plan);
    long id = receipt.plan().selections().get(parent).monthlyPriceId();
    assertThat(
            firstUse(id).dependencies().stream()
                .map(MonthlyMaterialPriceService.Dependency::monthlyPriceId)
                .distinct())
        .hasSize(1);
    assertThat(module.lookup(List.of(parent)).values()).isEqualTo(receipt.plan().selections());
  }

  @Test
  void commitAcknowledgementLossRecoversTheSameActuallyCommittedPrices() throws Exception {
    var lost = new java.util.concurrent.atomic.AtomicBoolean(true);
    var ds =
        new DelegatingDataSource(jdbc.getDataSource()) {
          @Override
          public java.sql.Connection getConnection() throws java.sql.SQLException {
            var connection = super.getConnection();
            return (java.sql.Connection)
                java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {java.sql.Connection.class},
                    (proxy, method, args) -> {
                      try {
                        var returned = method.invoke(connection, args);
                        if (method.getName().equals("commit")
                            && !connection.isReadOnly()
                            && lost.compareAndSet(true, false))
                          throw new java.sql.SQLException(
                              "controlled lost COMMIT acknowledgement", "08006");
                        return returned;
                      } catch (java.lang.reflect.InvocationTargetException error) {
                        throw error.getCause();
                      }
                    });
          }
        };
    var config = new org.apache.ibatis.session.Configuration();
    config.setMapUnderscoreToCamelCase(true);
    config.addMapper(MonthlyPriceStorageMapper.class);
    config.addMapper(MonthlyPriceLinkMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(ds);
    factory.setConfiguration(config);
    var sql = new SqlSessionTemplate(factory.getObject());
    var codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
    var persistence =
        new MonthlyPriceStorage(
            sql.getMapper(MonthlyPriceStorageMapper.class),
            codec,
            sql.getMapper(MonthlyPriceLinkMapper.class));
    var operation =
        new MonthlyMaterialPriceService(
            persistence,
            new DataSourceTransactionManager(ds),
            codec,
            (context, requests, dependencyPrices) -> {
              throw new AssertionError("Recovery must not consult sources");
            });
    var request = request("MPP05-commit-" + UUID.randomUUID(), "210");
    var requestId = UUID.randomUUID().toString();
    long before = count("lp_material_monthly_price");
    var outer =
        new org.springframework.transaction.support.TransactionTemplate(
            new DataSourceTransactionManager(ds));
    outer.setReadOnly(true);
    outer.setIsolationLevelName("ISOLATION_REPEATABLE_READ");
    var receipt =
        outer.execute(
            status -> {
              return operation.adopt(
                  context(), plan(request, new Selection(null, shared(), normal("10"))));
            });
    assertThat(lost).isFalse();
    assertThat(operation.lookup(Set.of(request)).values()).isEqualTo(receipt.plan().selections());
    assertThat(count("lp_material_monthly_price")).isEqualTo(before + 1);
  }

  @Test
  void actualSeventeenRowHouseholdSourceAdoptsExactPHAndLaterSourceChangesDoNotReplaceIt()
      throws Exception {
    var bytes =
        getClass().getResourceAsStream("/household-inquiry/real-20261023.xls").readAllBytes();
    var preview =
        inquiries.preview("COMMERCIAL", "test", bytes, "real-source.xls", "2026-10");
    inquiries.publish(
        "COMMERCIAL",
        "test",
        new com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels
            .PublishRequest(
            preview.previewId(), preview.publishRequestId(), preview.previewFingerprint()));
    var requests = new LinkedHashSet<MonthlyPriceRequest>();
    for (var row : preview.content().rows())
      requests.add(request(row.source().materialCode(), "210"));
    preparedStatements.set(0);
    var candidates = householdSource.read(context(), requests);
    assertThat(preparedStatements.get()).isLessThanOrEqualTo(2);
    assertThat(candidates.problems()).isEmpty();
    assertThat(candidates.values()).hasSize(17);
    var selections = new LinkedHashMap<MonthlyPriceRequest, Selection>();
    candidates
        .values()
        .forEach((request, candidate) -> selections.put(request, candidate.selection()));
    var uses = new ArrayList<Use>();
    requests.forEach(
        request ->
            uses.add(new Use("root/" + request.family().materialCode(), request, BigDecimal.ONE)));
    var operation =
        new MonthlyMaterialPriceService(
            storage,
            new DataSourceTransactionManager(jdbc.getDataSource()),
            codec,
            (context, missing, dependencyPrices) -> householdSource.read(context, missing));
    long before = count("lp_material_monthly_price");
    var adopted = operation.adopt(context(), new MonthlyAdoptionPlan(selections, uses, Set.of()));
    for (var row : preview.content().rows()) {
      var saved = adopted.plan().selections().get(request(row.source().materialCode(), "210"));
      assertThat(saved.fact().type()).isEqualTo(PricingType.HOUSEHOLD_INQUIRY);
      assertThat(saved.fact().price()).isEqualByComparingTo(row.source().unitPrice());
      assertThat(saved.fact().householdRate()).isEqualByComparingTo(row.source().householdRate());
      var evidence = new ObjectMapper().readTree(saved.fact().evidence().json());
      assertThat(evidence.path("snapshotSchema").intValue()).isEqualTo(1);
      assertThat(evidence.has("consumerOrg")).isFalse();
      assertThat(
              evidence
                  .path("source")
                  .path("evidence")
                  .path("source")
                  .path("financialReference")
                  .size())
          .isEqualTo(14);
    }
    byte[] changed;
    try (var book =
            org.apache.poi.ss.usermodel.WorkbookFactory.create(
                new java.io.ByteArrayInputStream(bytes));
        var stream = new java.io.ByteArrayOutputStream()) {
      book.getSheet("10月线圈询价").getRow(2).getCell(4).setCellValue("21.123456789012");
      book.write(stream);
      changed = stream.toByteArray();
    }
    var update =
        inquiries.preview("COMMERCIAL", "test", changed, "updated-source.xls", "2026-10");
    inquiries.publish(
        "COMMERCIAL",
        "test",
        new com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels
            .PublishRequest(
            update.previewId(), update.publishRequestId(), update.previewFingerprint()));
    var other = request("208200507", "220");
    preparedStatements.set(0);
    var reused =
        operation.resolveCandidates(context(), Set.of(other)).values().get(other).selection();
    assertThat(reused.monthlyPriceId())
        .isEqualTo(adopted.plan().selections().get(request("208200507", "210")).monthlyPriceId());
    assertThat(reused.fact().price()).isEqualByComparingTo("20.9853");
    assertThat(count("lp_material_monthly_price")).isEqualTo(before + 17);
  }

  @Test
  void aHouseholdSourceCannotGuessUnitCurrencyTaxOrAnUnrecordedSupplier() throws Exception {
    var bytes =
        getClass().getResourceAsStream("/household-inquiry/real-20261023.xls").readAllBytes();
    var preview =
        inquiries.preview("HOUSEHOLD", "test", bytes, "scope-source.xls", "2026-10");
    inquiries.publish(
        "HOUSEHOLD",
        "test",
        new com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels
            .PublishRequest(
            preview.previewId(), preview.publishRequestId(), preview.previewFingerprint()));
    var c = context();
    var context =
        new Context(
            "HOUSEHOLD",
            c.oaNo(),
            c.oaFormItemId(),
            c.productCode(),
            c.month(),
            c.priceAsOfTime(),
            c.actor(),
            c.consumerOrg());
    for (var family :
        List.of(
            new MonthlyPriceFamilyKey(
                c.month(), "HOUSEHOLD", "208200507", null, "KG", "CNY", TaxBasis.EXCLUDED, null),
            new MonthlyPriceFamilyKey(
                c.month(), "HOUSEHOLD", "208200507", null, "件", "USD", TaxBasis.EXCLUDED, null),
            new MonthlyPriceFamilyKey(
                c.month(), "HOUSEHOLD", "208200507", null, "件", "CNY", TaxBasis.INCLUDED, null),
            new MonthlyPriceFamilyKey(
                c.month(),
                "HOUSEHOLD",
                "208200507",
                null,
                "件",
                "CNY",
                TaxBasis.EXCLUDED,
                "SUPPLIER"))) {
      var request = new MonthlyPriceRequest(family, "210", NonCuConditions.empty(), BigDecimal.ONE);
      var result = householdSource.read(context, Set.of(request));
      assertThat(result.values()).isEmpty();
      assertThat(result.problems().get(request).reason())
          .isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT);
    }
    assertThat(
            householdSource
                .read(
                    context,
                    Set.of(
                        new MonthlyPriceRequest(
                            new MonthlyPriceFamilyKey(
                                c.month(),
                                "HOUSEHOLD",
                                "unknown-code",
                                null,
                                "件",
                                "CNY",
                                TaxBasis.EXCLUDED,
                                null),
                            "210",
                            NonCuConditions.empty(),
                            BigDecimal.ONE)))
                .values())
        .isEmpty();
  }

  @Test
  void automaticReplanUsesTheCommittedWinnerAndKeepsTheOriginalRequestIdentity() {
    var request = request("MPP05-auto-" + UUID.randomUUID(), "210");
    var original =
        module.adopt(context(), plan(request, new Selection(null, shared(), normal("10"))));
    var requestId = UUID.randomUUID().toString();
    var plans = new AtomicInteger();
    long before = count("lp_material_monthly_price");
    var receipt =
        new MonthlyAdoptionExecutor(module)
            .execute(
                context(),
                remaining -> {
                  assertThat(remaining).isPositive();
                  return plan(
                      request,
                      plans.incrementAndGet() == 1
                          ? new Selection(null, shared(), normal("11"))
                          : module.lookup(List.of(request)).values().get(request));
                });
    assertThat(plans.get()).isEqualTo(2);
    assertThat(receipt.plan().selections().get(request).monthlyPriceId())
        .isEqualTo(original.plan().selections().get(request).monthlyPriceId());
    assertThat(count("lp_material_monthly_price")).isEqualTo(before);
  }

  @Test
  void automaticReplanStopsAtThreeAttemptsWithoutPartialPrices() {
    var request = request("MPP05-bounded-" + UUID.randomUUID(), "210");
    module.adopt(context(), plan(request, new Selection(null, shared(), normal("10"))));
    var plans = new AtomicInteger();
    long before = count("lp_material_monthly_price");
    assertThatThrownBy(
            () ->
                new MonthlyAdoptionExecutor(module)
                    .execute(
                        context(),
                        remaining -> {
                          plans.incrementAndGet();
                          return plan(request, new Selection(null, shared(), normal("11")));
                        }))
        .isInstanceOfSatisfying(
            MonthlyPriceProblem.class,
            error ->
                assertThat(error.reason())
                    .isEqualTo(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED));
    assertThat(plans.get()).isEqualTo(3);
    assertThat(count("lp_material_monthly_price")).isEqualTo(before);
  }

  @Test
  void insufficientRemainingBudgetDoesNotStartAnotherAdoptionTransaction() {
    var request = request("MPP05-budget-" + UUID.randomUUID(), "210");
    long prices = count("lp_material_monthly_price");
    assertThatThrownBy(
            () ->
                module.adopt(
                    context(),
                    plan(request, new Selection(null, shared(), normal("10"))),
                    Duration.ofMillis(250)))
        .isInstanceOfSatisfying(
            MonthlyPriceProblem.class,
            error ->
                assertThat(error.reason())
                    .isEqualTo(MonthlyAdoptionRetryPolicy.Failure.TRANSIENT_CONCURRENCY_FAILURE));
    assertThat(count("lp_material_monthly_price")).isEqualTo(prices);
  }

  @Test
  void aHeldFamilyLockUsesTheRemainingTransactionBudgetAndRollsBack() throws Exception {
    var request = request("MPP05-lock-" + UUID.randomUUID(), "210");
    module.adopt(context(), plan(request, new Selection(null, shared(), normal("10"))));
    try (var holder = jdbc.getDataSource().getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement(
              "SELECT id FROM lp_material_monthly_price WHERE family_hash=? FOR UPDATE")) {
        statement.setString(1, MonthlyPriceDocumentCodec.sha256(request.family().canonical()));
        assertThat(statement.executeQuery().next()).isTrue();
      }
      try {
        long before = count("lp_material_monthly_price"), started = System.nanoTime();
        assertThatThrownBy(
                () ->
                    module.adopt(
                        context(),
                        plan(request, new Selection(null, shared(), normal("10"))),
                        Duration.ofMillis(1500)))
            .isInstanceOfSatisfying(
                MonthlyPriceProblem.class,
                error ->
                    assertThat(error.reason())
                        .isEqualTo(
                            MonthlyAdoptionRetryPolicy.Failure.TRANSIENT_CONCURRENCY_FAILURE));
        assertThat(Duration.ofNanos(System.nanoTime() - started))
            .isLessThan(Duration.ofMillis(2500));
        assertThat(count("lp_material_monthly_price")).isEqualTo(before);
      } finally {
        holder.rollback();
      }
    }
  }

  @Test
  void twoAtomicBatchesOfFiveHundredReturnSavedIdsAndThousandLookupRequestsStayBatched() {
    var all = new ArrayList<MonthlyPriceRequest>();
    long before = count("lp_material_monthly_price");
    int calls = sourceCalls.get();
    for (int batch = 0; batch < 2; batch++) {
      var selections = new LinkedHashMap<MonthlyPriceRequest, Selection>();
      var uses = new ArrayList<Use>();
      for (int i = 0; i < 500; i++) {
        var request = request("MPP05-volume-" + UUID.randomUUID(), "210");
        all.add(request);
        var evidence =
            SourceEvidence.ofJson(
                "{\"snapshotSchema\":1,\"sourceKind\":\"FIXED\",\"rawRow\":{\"materialCode\":\""
                    + request.family().materialCode()
                    + "\",\"unit\":\"件\",\"taxIncluded\":false,\"currency\":\"CNY\",\"supplierCode\":\"SYNTHETIC-SUPPLIER\",\"price\":10,\"sourceRevision\":1},\"selection\":\"SINGLE\"}");
        selections.put(
            request,
            new Selection(null, shared(), PriceFact.normal(BigDecimal.TEN, evidence, Set.of())));
        uses.add(new Use("root/" + i, request, BigDecimal.ONE));
      }
      preparedStatements.set(0);
      long started = System.nanoTime();
      var receipt = module.adopt(context(), new MonthlyAdoptionPlan(selections, uses, Set.of()));
      assertThat(receipt.plan().selections()).hasSize(500);
      assertThat(
              receipt.plan().selections().values().stream()
                  .map(Selection::monthlyPriceId)
                  .distinct())
          .hasSize(500);
      System.out.println(
          "MPP05 atomic500 milliseconds="
              + Duration.ofNanos(System.nanoTime() - started).toMillis()
              + " preparedSQL="
              + preparedStatements.get());
    }
    var repeated = new ArrayList<>(all);
    repeated.addAll(all);
    preparedStatements.set(0);
    long started = System.nanoTime();
    var lookup = module.lookup(repeated);
    int statements = preparedStatements.get();
    assertThat(lookup.missing()).isEmpty();
    assertThat(lookup.values()).hasSize(1000);
    assertThat(statements).isLessThanOrEqualTo(12);
    assertThat(sourceCalls.get()).isEqualTo(calls);
    assertThat(count("lp_material_monthly_price")).isEqualTo(before + 1000);
    System.out.println(
        "MPP05 lookup1000 milliseconds="
            + Duration.ofNanos(System.nanoTime() - started).toMillis()
            + " preparedSQL="
            + statements);
  }

  @Test
  void resolverCannotOverwriteAnAlreadyLookedUpFactThroughAnExtraDependencyEntry() {
    var a = request("MPP05-protected-A-" + UUID.randomUUID(), "210");
    var b = request("MPP05-missing-B-" + UUID.randomUUID(), "210");
    var original = module.adopt(context(), plan(a, new Selection(null, shared(), normal("10"))));
    var bad =
        new MonthlyMaterialPriceService(
            storage,
            new DataSourceTransactionManager(jdbc.getDataSource()),
            codec,
            (context, missing, dependencyPrices) -> {
              assertThat(missing).containsExactly(b);
              return new Candidates(
                  Map.of(
                      a,
                      new Candidate(new Selection(null, shared(), normal("11")), null),
                      b,
                      new Candidate(new Selection(null, shared(), normal("20")), null)),
                  Map.of());
            });
    long before = count("lp_material_monthly_price");
    assertThatThrownBy(() -> bad.resolveCandidates(context(), Set.of(a, b)))
        .isInstanceOfSatisfying(
            MonthlyPriceProblem.class,
            error ->
                assertThat(error.reason())
                    .isEqualTo(MonthlyAdoptionRetryPolicy.Failure.PERSISTENCE_INCOMPLETE));
    assertThat(count("lp_material_monthly_price")).isEqualTo(before);
    assertThat(module.lookup(List.of(a)).values().get(a))
        .isEqualTo(original.plan().selections().get(a));
  }

  @Test
  void resolverMayReturnTheAuthoritativeSavedDependencyWithoutRepricingIt() {
    var a = request("MPP05-saved-A-" + UUID.randomUUID(), "210");
    var b = request("MPP05-derived-B-" + UUID.randomUUID(), "210");
    var saved =
        module
            .adopt(context(), plan(a, new Selection(null, shared(), normal("10"))))
            .plan()
            .selections()
            .get(a);
    var good =
        new MonthlyMaterialPriceService(
            storage,
            new DataSourceTransactionManager(jdbc.getDataSource()),
            codec,
            (context, missing, dependencyPrices) -> {
              assertThat(missing).containsExactly(b);
              var fact =
                  PriceFact.normal(
                      new BigDecimal("20"),
                      SourceEvidence.ofJson("{\"sourceKind\":\"LINKED_LEGACY\",\"coefficient\":2}"),
                      Set.of(a));
              return new Candidates(
                  Map.of(
                      a,
                      new Candidate(saved, null),
                      b,
                      new Candidate(new Selection(null, shared(), fact), null)),
                  Map.of());
            });
    for (var requested : List.of(Set.of(a, b), Set.of(b))) {
      var result = good.resolveCandidates(context(), requested);
      assertThat(result.values().get(a).selection()).isEqualTo(saved);
      assertThat(result.values().get(b).selection().fact().dependencies()).containsExactly(a);
    }
  }

  @Test
  void monthlyDatabaseBoundsPoolAcquisitionWithoutChangingTheExistingApplicationPool()
      throws Exception {
    var config = new com.zaxxer.hikari.HikariConfig();
    String url = MYSQL.getJdbcUrl();
    config.setJdbcUrl(
        url + (url.contains("?") ? "&" : "?") + "connectTimeout=12000&socketTimeout=30000");
    config.setUsername(MYSQL.getUsername());
    config.setPassword(MYSQL.getPassword());
    config.setPoolName("mpp05-legacy");
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(30000);
    try (var original = new com.zaxxer.hikari.HikariDataSource(config);
        var database = new MonthlyPriceDatabase(original, codec)) {
      assertThat(original.getConnectionTimeout()).isEqualTo(30000);
      assertThat(original.getJdbcUrl()).contains("socketTimeout=30000");
      assertThat(database.pool().getConnectionTimeout()).isEqualTo(250);
      assertThat(database.pool().getJdbcUrl())
          .contains("connectTimeout=250")
          .contains("socketTimeout=2000")
          .doesNotContain("socketTimeout=30000");
      var operation =
          database.createService(
              (context, requests, dependencyPrices) -> {
                throw new AssertionError("AdoptionResult recovery must not resolve sources");
              });
      try (var occupied = database.pool().getConnection()) {
        assertThat(occupied.getNetworkTimeout()).isEqualTo(2000);
        long started = System.nanoTime();
        assertThatThrownBy(
                () ->
                    operation.adopt(
                        context(),
                        plan(
                            request("POOL-TEST", "210"),
                            new Selection(null, shared(), normal("10"))),
                        Duration.ofMillis(1500)))
            .isInstanceOfSatisfying(
                MonthlyPriceProblem.class,
                error ->
                    assertThat(error.reason())
                        .isEqualTo(
                            MonthlyAdoptionRetryPolicy.Failure.TRANSIENT_CONCURRENCY_FAILURE));
        assertThat(Duration.ofNanos(System.nanoTime() - started))
            .isLessThan(Duration.ofMillis(1000));
      }
      assertThat(
              operation
                  .adopt(
                      context(),
                      plan(
                          request("POOL-TEST", "210"), new Selection(null, shared(), normal("10"))),
                      Duration.ofMillis(1500))
                  .plan()
                  .selections()
                  .values())
          .allSatisfy(value -> assertThat(value.monthlyPriceId()).isPositive());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void derivedSourcesBatchTheirSharedDependencyAndPreferItsAdoptedFact(
      boolean childAlreadyAdopted) {
    var child = request("MPP05-source-child-" + UUID.randomUUID(), "210");
    var left = request("MPP05-source-left-" + UUID.randomUUID(), "210");
    var right = request("MPP05-source-right-" + UUID.randomUUID(), "210");
    Long oldId =
        childAlreadyAdopted
            ? module
                .adopt(context(), plan(child, new Selection(null, shared(), normal("10"))))
                .plan()
                .selections()
                .get(child)
                .monthlyPriceId()
            : null;
    var childReads = new AtomicInteger();
    var rootReads = new AtomicInteger();
    var operation =
        new MonthlyMaterialPriceService(
            storage,
            new DataSourceTransactionManager(jdbc.getDataSource()),
            codec,
            (context, requests, dependencies) -> {
              if (requests.contains(child)) {
                assertThat(childAlreadyAdopted).isFalse();
                assertThat(requests).containsExactly(child);
                childReads.incrementAndGet();
                return new Candidates(
                    Map.of(child, new Candidate(new Selection(null, shared(), normal("10")), null)),
                    Map.of());
              }
              rootReads.incrementAndGet();
              assertThat(requests).containsExactlyInAnyOrder(left, right);
              var resolved = dependencies.apply(Set.of(child));
              var chosen = resolved.values().get(child).selection();
              var values = new LinkedHashMap<>(resolved.values());
              for (var parent : List.of(left, right)) {
                int coefficient = parent.equals(left) ? 2 : 3;
                var evidence =
                    SourceEvidence.ofJson(
                        "{\"sourceKind\":\"LINKED_LEGACY\",\"coefficient\":"
                            + coefficient
                            + ",\"dependencyEvidenceHash\":\""
                            + chosen.fact().evidence().sha256()
                            + "\"}");
                values.put(
                    parent,
                    new Candidate(
                        new Selection(
                            null,
                            shared(),
                            PriceFact.normal(
                                chosen.fact().price().multiply(BigDecimal.valueOf(coefficient)),
                                evidence,
                                Set.of(child))),
                        null));
              }
              return new Candidates(values, Map.of());
            });
    var candidates = operation.resolveCandidates(context(), Set.of(left, right));
    assertThat(rootReads.get()).isEqualTo(1);
    assertThat(childReads.get()).isEqualTo(childAlreadyAdopted ? 0 : 1);
    var selections = new LinkedHashMap<MonthlyPriceRequest, Selection>();
    candidates
        .values()
        .forEach((request, candidate) -> selections.put(request, candidate.selection()));
    var receipt =
        operation.adopt(
            context(),
            new MonthlyAdoptionPlan(
                selections,
                List.of(
                    new Use("root/left", left, BigDecimal.ONE),
                    new Use("root/right", right, BigDecimal.ONE)),
                Set.of()));
    assertThat(receipt.plan().selections().get(left).fact().price()).isEqualByComparingTo("20");
    assertThat(receipt.plan().selections().get(right).fact().price()).isEqualByComparingTo("30");
    if (oldId != null)
      assertThat(receipt.plan().selections().get(child).monthlyPriceId()).isEqualTo(oldId);
  }

  @Test
  void sourceDependencyCycleIsRejectedBeforeAdoptionAndCannotOverflowTheResolver() {
    var request = request("MPP05-source-cycle-" + UUID.randomUUID(), "210");
    long before = count("lp_material_monthly_price");
    var operation =
        new MonthlyMaterialPriceService(
            storage,
            new DataSourceTransactionManager(jdbc.getDataSource()),
            codec,
            (context, requests, dependencies) -> dependencies.apply(requests));
    assertThatThrownBy(() -> operation.resolveCandidates(context(), Set.of(request)))
        .isInstanceOfSatisfying(
            MonthlyPriceProblem.class,
            error ->
                assertThat(error.reason())
                    .isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT));
    assertThat(count("lp_material_monthly_price")).isEqualTo(before);
  }

  @Test
  void anIndependentRequestedRootCanAlsoBeAnotherRootsDependency() {
    var child = request("MPP05-source-sibling-" + UUID.randomUUID(), "210");
    var parent = request("MPP05-source-owner-" + UUID.randomUUID(), "210");
    var childReads = new AtomicInteger();
    var operation =
        new MonthlyMaterialPriceService(
            storage,
            new DataSourceTransactionManager(jdbc.getDataSource()),
            codec,
            (context, requests, dependencies) -> {
              if (requests.size() == 1 && requests.contains(child)) {
                childReads.incrementAndGet();
                return new Candidates(
                    Map.of(child, new Candidate(new Selection(null, shared(), normal("10")), null)),
                    Map.of());
              }
              var result = dependencies.apply(Set.of(child));
              var values = new LinkedHashMap<>(result.values());
              values.put(
                  parent,
                  new Candidate(
                      new Selection(
                          null,
                          shared(),
                          PriceFact.normal(
                              new BigDecimal("20"),
                              SourceEvidence.ofJson(
                                  "{\"sourceKind\":\"LINKED_LEGACY\",\"coefficient\":2}"),
                              Set.of(child))),
                      null));
              return new Candidates(values, Map.of());
            });
    var result = operation.resolveCandidates(context(), Set.of(parent, child));
    assertThat(result.problems()).isEmpty();
    assertThat(result.values()).hasSize(2);
    assertThat(childReads.get()).isEqualTo(1);
  }

  @Test
  void concurrentWinnerOfAnOriginallyMissingRootIsAcceptedWhenTheDependencyReadDiscoversIt() {
    var child = request("MPP05-concurrent-sibling-" + UUID.randomUUID(), "210");
    var parent = request("MPP05-concurrent-parent-" + UUID.randomUUID(), "210");
    var winnerId = new java.util.concurrent.atomic.AtomicReference<Long>();
    var operation =
        new MonthlyMaterialPriceService(
            storage,
            new DataSourceTransactionManager(jdbc.getDataSource()),
            codec,
            (context, requests, dependencies) -> {
              assertThat(requests).containsExactlyInAnyOrder(child, parent);
              winnerId.set(
                  module
                      .adopt(context(), plan(child, new Selection(null, shared(), normal("10"))))
                      .plan()
                      .selections()
                      .get(child)
                      .monthlyPriceId());
              var resolved = dependencies.apply(Set.of(child));
              var values = new LinkedHashMap<>(resolved.values());
              values.put(
                  parent,
                  new Candidate(
                      new Selection(
                          null,
                          shared(),
                          PriceFact.normal(
                              new BigDecimal("20"),
                              SourceEvidence.ofJson(
                                  "{\"sourceKind\":\"LINKED_LEGACY\",\"coefficient\":2}"),
                              Set.of(child))),
                      null));
              return new Candidates(values, Map.of());
            });
    var resolved = operation.resolveCandidates(context(), Set.of(child, parent));
    assertThat(resolved.problems()).isEmpty();
    assertThat(resolved.values().get(child).selection().monthlyPriceId()).isEqualTo(winnerId.get());
    var selections = new LinkedHashMap<MonthlyPriceRequest, Selection>();
    resolved
        .values()
        .forEach((request, candidate) -> selections.put(request, candidate.selection()));
    var adopted =
        operation.adopt(
            context(),
            new MonthlyAdoptionPlan(
                selections,
                List.of(
                    new Use("root", parent, BigDecimal.ONE),
                    new Use("root/independent", child, BigDecimal.ONE)),
                Set.of()));
    assertThat(adopted.plan().selections().get(child).monthlyPriceId()).isEqualTo(winnerId.get());
    assertThat(adopted.plan().selections().get(parent).fact().price()).isEqualByComparingTo("20");
  }

  static MonthlyMaterialPriceService.FirstUse firstUse(long id) {
    return codec.decode(
        codec.wrap(storage.readByIds("COMMERCIAL", Set.of(id)).getFirst().firstUseJson()),
        MonthlyMaterialPriceService.FirstUse.class);
  }

  @Test
  void aLaterPriceInsertFailureRollsBackTheEntireSet() {
    String prefix = "ROLLBACK-" + UUID.randomUUID();
    jdbc.execute(
        "CREATE TRIGGER mpp05_insert_fault BEFORE INSERT ON lp_material_monthly_price FOR EACH ROW"
            + " BEGIN IF NEW.material_code='"
            + prefix
            + "-parent' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled insert fault';"
            + " END IF; END");
    try {
      var child = request(prefix + "-child", "210");
      var parent = request(prefix + "-parent", "210");
      var fact =
          PriceFact.normal(
              new BigDecimal("30"),
              SourceEvidence.ofJson("{\"sourceKind\":\"LINKED_LEGACY\"}"),
              Set.of(child));
      var plan =
          new MonthlyAdoptionPlan(
              Map.of(
                  child,
                  new Selection(null, shared(), normal("10")),
                  parent,
                  new Selection(null, shared(), fact)),
              List.of(new Use("parent", parent, BigDecimal.ONE)),
              Set.of());
      assertThatThrownBy(() -> module.adopt(context(), plan))
          .isInstanceOf(MonthlyPriceProblem.class);
      assertThat(module.lookup(Set.of(child, parent)).missing())
          .containsExactlyInAnyOrder(child, parent);
    } finally {
      jdbc.execute("DROP TRIGGER mpp05_insert_fault");
    }
  }

  @Test
  void thirtySamplesMeetTheFrozenAdoptionAndLookupBudgets() throws Exception {
    var adoptionMs = new ArrayList<Double>();
    var lookupMs = new ArrayList<Double>();
    var adoptionSql = new ArrayList<Integer>();
    var lookupSql = new ArrayList<Integer>();
    var thousand = new LinkedHashSet<MonthlyPriceRequest>();
    int originalSourceCalls = sourceCalls.get();
    for (int sample = 0; sample < 35; sample++) {
      var selections = new LinkedHashMap<MonthlyPriceRequest, Selection>();
      var uses = new ArrayList<Use>();
      String prefix = "PERF-" + UUID.randomUUID() + "-";
      for (int i = 0; i < 500; i++) {
        var request = request(prefix + i, "210");
        selections.put(request, new Selection(null, shared(), normal("12.345678901234567890")));
        uses.add(new Use("root/" + i, request, BigDecimal.ONE));
      }
      var plan = new MonthlyAdoptionPlan(selections, uses, Set.of());
      preparedStatements.set(0);
      long start = System.nanoTime();
      var result = module.adopt(context(), plan);
      double elapsed = (System.nanoTime() - start) / 1_000_000.0;
      assertThat(result.plan().selections()).hasSize(500);
      assertThat(result.plan().selections().values())
          .allMatch(price -> price.monthlyPriceId() != null);
      if (sample >= 5) {
        adoptionMs.add(elapsed);
        adoptionSql.add(preparedStatements.get());
      }
      if (thousand.size() < 1000) thousand.addAll(selections.keySet());
    }
    for (int sample = 0; sample < 35; sample++) {
      preparedStatements.set(0);
      long start = System.nanoTime();
      var saved = module.lookup(thousand);
      double elapsed = (System.nanoTime() - start) / 1_000_000.0;
      assertThat(saved.missing()).isEmpty();
      assertThat(saved.values()).hasSize(1000);
      if (sample >= 5) {
        lookupMs.add(elapsed);
        lookupSql.add(preparedStatements.get());
      }
    }
    assertThat(sourceCalls.get()).isEqualTo(originalSourceCalls);
    var adoptionSorted = adoptionMs.stream().sorted().toList();
    var lookupSorted = lookupMs.stream().sorted().toList();
    var report =
        Map.of(
            "databaseVersion",
            jdbc.queryForObject("SELECT @@version", String.class),
            "warmups",
            5,
            "samples",
            30,
            "adoption500",
            Map.of(
                "elapsedMs",
                adoptionMs,
                "sql",
                adoptionSql,
                "p50",
                adoptionSorted.get(14),
                "p95",
                adoptionSorted.get(28),
                "max",
                adoptionSorted.getLast()),
            "lookup1000",
            Map.of(
                "elapsedMs",
                lookupMs,
                "sql",
                lookupSql,
                "p50",
                lookupSorted.get(14),
                "p95",
                lookupSorted.get(28),
                "max",
                lookupSorted.getLast(),
                "sourceCalls",
                0));
    new ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(
            java.nio.file.Path.of("target/mpp05-monthly-performance.json").toFile(), report);
    assertThat(adoptionSorted.get(28)).isLessThanOrEqualTo(4000);
    assertThat(lookupSorted.get(28)).isLessThanOrEqualTo(500);
    assertThat(lookupSql).allMatch(count -> count <= 12);
  }
}
