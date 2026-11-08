package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.mapper.MonthlyRepriceReuseMapper;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceInputDocument.Section;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real mapper/transaction/DDL contracts on an owned disposable database, not costing acceptance. */
@Tag("integration")
class MonthlyRepriceReusePersistenceIntegrationTest {
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName
      .parse(System.getProperty("mpp.mysql.image", "mysql:8.0.36")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("reprice_reuse_contract_test").withUsername("root").withPassword("test-only")
      .withLabel("task", System.getProperty("reprice.test.task", "monthly-reprice-reuse-integration"))
      .withConfigurationOverride("monthly-pricing/mysql")
      .withLabel("sales-cost.task","monthly-reprice-reuse-20261105-r02")
      .withLabel("sales-cost.run",java.util.UUID.randomUUID().toString())
      .withReuse(Boolean.getBoolean("mpp.mysql.retain"));
  static JdbcTemplate jdbc;
  static MonthlyRepriceReuseMapper mapper;
  static MonthlyRepriceComputeGroups groups;
  static MonthlyRepriceSharedNumbers shared;
  static MonthlyRepriceGroupLeases leases;
  static DataSourceTransactionManager transactions;
  static TransactionTemplate transaction;
  static Map<String, Object> legacy;

  @BeforeAll static void database() throws Exception {
    MYSQL.start();
    var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("SELECT @@version", String.class))
        .startsWith(System.getProperty("mpp.mysql.expected-version", "8.0.36"));
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");
    jdbc.execute("CREATE TABLE lp_material_monthly_price(id BIGINT PRIMARY KEY)");
    new ResourceDatabasePopulator(new ClassPathResource("monthly-reprice/pre-v348-schema.sql")).execute(dataSource);
    jdbc.update("""
        INSERT INTO lp_quote_cost_run_version(id,cost_run_no,oa_no,product_code,pricing_month,result_period,status,total_cost)
        VALUES(1,'SAVED','SAVED','00123','2026-10','2026-10','SUCCESS',54.282496),
          (2,'SAVED-2','SAVED-2','00123','2026-10','2026-10','SUCCESS',54.282496)
        """);
    jdbc.update("""
        INSERT INTO lp_monthly_reprice_batch(id,reprice_no,pricing_month,price_as_of_time,business_unit_type,status)
        VALUES(1,'LEGACY','2026-10','2026-10-01','COMMERCIAL','CONFIRMED')
        """);
    jdbc.update("""
        INSERT INTO lp_monthly_reprice_result(id,reprice_no,pricing_month,business_unit_type,oa_no,product_code,calc_object_key,total_cost)
        VALUES(1,'LEGACY','2026-10','COMMERCIAL','SAVED','00123','old-key',54.282496)
        """);
    new ResourceDatabasePopulator(new ClassPathResource("db/V348__monthly_reprice_1130_contract.sql")).execute(dataSource);
    legacy = jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_result WHERE id=1");
    new ResourceDatabasePopulator(new ClassPathResource("db/V361__monthly_reprice_reuse_contract.sql")).execute(dataSource);
    new ResourceDatabasePopulator(new ClassPathResource("db/V363__monthly_reprice_partial_publication.sql")).execute(dataSource);
    var configuration = new org.apache.ibatis.session.Configuration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setArgNameBasedConstructorAutoMapping(true);
    configuration.addMapper(MonthlyRepriceReuseMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(dataSource);
    factory.setConfiguration(configuration);
    mapper = new SqlSessionTemplate(factory.getObject()).getMapper(MonthlyRepriceReuseMapper.class);
    transactions = new DataSourceTransactionManager(dataSource);
    groups = new MonthlyRepriceComputeGroups(mapper, transactions);
    leases = new MonthlyRepriceGroupLeases(mapper, transactions, new MonthlyRepriceInstance("lease-tests"),30);
    shared = new MonthlyRepriceSharedNumbers(groups, mapper,
        new com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec(
            new com.fasterxml.jackson.databind.ObjectMapper()), transactions, leases, 5000);
    transaction = new TransactionTemplate(transactions);
  }

  @AfterAll static void stop() { if (!Boolean.getBoolean("mpp.mysql.retain")) MYSQL.stop(); }

  @BeforeEach void cleanOwnedRows() {
    jdbc.update("DELETE FROM lp_monthly_reprice_source");
    jdbc.update("DELETE FROM lp_monthly_reprice_result WHERE id<>1");
    jdbc.update("DELETE FROM lp_monthly_reprice_compute_group");
    jdbc.update("DELETE FROM lp_monthly_reprice_batch WHERE id<>1");
    batch("B1", "COMMERCIAL", "2026-11");
  }

  @Test void forwardPatchPreservesEveryLegacyColumnAndLeavesNewIdentityNull() {
    var row = jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_result WHERE id=1");
    assertThat(row).containsAllEntriesOf(legacy);
    for (var column : new String[]{"customer_identity_source", "customer_identity_value", "merged_source_count",
        "customer_object_scope", "compute_group_id", "reuse_batch_id"}) assertThat(row.get(column)).isNull();
    assertThat(jdbc.queryForObject("SELECT total_cost FROM lp_quote_cost_run_version WHERE id=1", java.math.BigDecimal.class))
        .isEqualByComparingTo("54.282496");
  }

  @Test void changedAlgorithmOrFeeContractPublishesSeparatelyAndPreservesPriorSuccess() {
    var original = input("00123", "1", false);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    java.util.function.Supplier<MonthlyRepriceSharedNumbers.Numbers> calculate = () -> {
      calls.incrementAndGet();
      return calculatedNumbers();
    };
    var first = shared.getOrCalculate("B1", original, calculate);
    var saved = jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_compute_group WHERE id=?", first.groupId());
    var sections = new EnumMap<Section, String>(Section.class);
    for (var section : Section.values()) sections.put(section, "{\"value\":1}");
    var scope = original.scope();
    var algorithm = MonthlyRepriceInputDocument.capture(new MonthlyRepriceInputDocument.Scope(
        scope.businessUnitType(), scope.month(), scope.productCode(), scope.organization(),
        "NEXT_ALGORITHM", scope.feePolicyVersion()), sections);
    var fees = MonthlyRepriceInputDocument.capture(new MonthlyRepriceInputDocument.Scope(
        scope.businessUnitType(), scope.month(), scope.productCode(), scope.organization(),
        scope.algorithmVersion(), "NEXT_FEE_CONTRACT"), sections);
    var nextAlgorithm = shared.getOrCalculate("B1", algorithm, calculate);
    var nextFees = shared.getOrCalculate("B1", fees, calculate);
    assertThat(List.of(first.groupId(), nextAlgorithm.groupId(), nextFees.groupId())).doesNotHaveDuplicates();
    for (var input : List.of(original, algorithm, fees)) {
      var reused = shared.getOrCalculate("B1", input, () -> { throw new AssertionError("already published"); });
      assertThat(reused.numbers()).isEqualTo(first.numbers());
      assertThat(jdbc.queryForObject("SELECT attempt_count FROM lp_monthly_reprice_compute_group WHERE id=?",
          Integer.class, reused.groupId())).isEqualTo(1);
    }
    assertThat(calls.get()).isEqualTo(3);
    assertThat(jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_compute_group WHERE id=?", first.groupId()))
        .usingRecursiveComparison().isEqualTo(saved);
  }

  @Test void eightIndependentConnectionsRegisterOneCompleteGroup() throws Exception {
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<java.util.concurrent.Future<Long>>();
      for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
        start.await();
        return groups.register("B1", input("00123", "1", false)).id();
      }));
      start.countDown();
      var ids = new ArrayList<Long>();
      for (var future : futures) ids.add(future.get(15, TimeUnit.SECONDS));
      assertThat(ids.stream().distinct().count()).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_compute_group", Integer.class)).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @CsvSource({"2,false", "4,false", "2,true", "4,true"})
  void registrationAndCustomerBindingCanCompleteWithoutALockCycle(int isolation, boolean queuedClaim) throws Exception {
    var input = input("00123", "1", false);
    var published = shared.getOrCalculate("B1", input, MonthlyRepriceReusePersistenceIntegrationTest::calculatedNumbers);
    var original = jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_compute_group WHERE id=?", published.groupId());
    object("OA-A", "00123", "CUSTOMER-A", "2026-11", "COMMERCIAL");
    object("OA-B", "00123", "CUSTOMER-B", "2026-11", "COMMERCIAL");
    var batchLocked = new CountDownLatch(1);
    var bindingTransaction = new TransactionTemplate(transactions);
    bindingTransaction.setIsolationLevel(isolation);
    bindingTransaction.setTimeout(15);
    // Schedule the actual database operations; no SQL result or transaction is mocked.
    var scheduledMapper = org.mockito.Mockito.mock(MonthlyRepriceReuseMapper.class,
        org.mockito.AdditionalAnswers.delegatesTo(mapper));
    org.mockito.Mockito.doAnswer(call -> {
      var batch = mapper.lockBatch("B1");
      assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("READ-COMMITTED");
      batchLocked.countDown();
      return batch;
    }).when(scheduledMapper).lockBatch("B1");
    var scheduledRegistration = new MonthlyRepriceComputeGroups(scheduledMapper, transactions);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var pending = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Long>>();
      var claimant = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<MonthlyRepriceGroupLeases.Claim>>();
      bindingTransaction.executeWithoutResult(tx -> {
        shared.bind("B1", "OA-A", published.groupId());
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM performance_schema.data_locks l
            JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID
            WHERE t.PROCESSLIST_ID=CONNECTION_ID() AND l.OBJECT_SCHEMA=DATABASE()
              AND l.OBJECT_NAME='lp_monthly_reprice_compute_group'
              AND l.LOCK_TYPE='RECORD' AND l.LOCK_MODE LIKE 'S%'
            """, Integer.class)).as("actual customer binding holds a shared group lock").isPositive();
        if (queuedClaim) {
          claimant.set(pool.submit(() -> leases.claim(published.groupId(), input)));
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (jdbc.queryForObject("""
              SELECT COUNT(*) FROM performance_schema.data_lock_waits w
              JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
              WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='lp_monthly_reprice_compute_group'
              """, Integer.class) == 0) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
          }
        }
        var registration = pool.submit(() -> scheduledRegistration.register("B1", input).id());
        pending.set(registration);
        try {
          assertThat(batchLocked.await(5, TimeUnit.SECONDS)).isTrue();
          // With the old exclusive candidate read, wait until the real database observes
          // its blocked group lock before requesting the completion lock on the batch.
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (!registration.isDone() && jdbc.queryForObject("""
              SELECT COUNT(*) FROM performance_schema.data_lock_waits w
              JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
              WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='lp_monthly_reprice_compute_group'
              """, Integer.class) == 0) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(5);
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
        assertThat(mapper.lockBatch("B1")).isNotNull();
        shared.bind("B1", "OA-B", published.groupId());
      });
      assertThat(pending.get().get(5, TimeUnit.SECONDS)).isEqualTo(published.groupId());
      if (queuedClaim) assertThat(claimant.get().get(5, TimeUnit.SECONDS))
          .isInstanceOf(MonthlyRepriceGroupLeases.Published.class);
    } finally {
      String evidence = System.getProperty("reprice.test.evidence");
      if (evidence != null) new com.fasterxml.jackson.databind.ObjectMapper().writeValue(
          java.nio.file.Path.of(evidence, "group-binding-locks-" + isolation + "-" + queuedClaim + ".json").toFile(),
          jdbc.queryForList("SHOW ENGINE INNODB STATUS"));
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_compute_group", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_compute_group WHERE id=?", published.groupId()))
        .usingRecursiveComparison().isEqualTo(original);
    assertThat(jdbc.queryForList("""
        SELECT oa_no,customer_identity_value,compute_group_id FROM lp_monthly_reprice_result
        WHERE oa_no IN ('OA-A','OA-B') ORDER BY oa_no
        """)).containsExactly(
            Map.of("oa_no", "OA-A", "customer_identity_value", "CUSTOMER-A", "compute_group_id", published.groupId()),
            Map.of("oa_no", "OA-B", "customer_identity_value", "CUSTOMER-B", "compute_group_id", published.groupId()));
  }

  @Test void registrationReusesAGroupCommittedAfterItsRepeatableReadSnapshot() throws Exception {
    var input = input("00123", "1", false);
    var repeatableRead = new TransactionTemplate(transactions);
    repeatableRead.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
    try (var pool = Executors.newSingleThreadExecutor()) {
      repeatableRead.executeWithoutResult(tx -> {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_compute_group", Integer.class)).isZero();
        try {
          long committed = pool.submit(() -> groups.register("B1", input).id()).get(5, TimeUnit.SECONDS);
          var reused = groups.register("B1", input);
          assertThat(reused.id()).isEqualTo(committed);
          assertThat(reused.inputDocument()).isEqualTo(input.canonical());
        } catch (Exception failure) {
          throw new AssertionError(failure);
        }
      });
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_compute_group", Integer.class)).isEqualTo(1);
  }

  @Test void largeCompletePriceCollectionRoundTripsAndStillChecksEveryLastDecimal() {
    var scope=input("00123","1",false).scope();
    var sections=new EnumMap<Section,String>(Section.class);
    for(var section:Section.values()) sections.put(section,"{\"fixture\":true}");
    String source="源依据".repeat(4000);
    String row="{\"source\":\""+source+"\",\"price\":0.123456789012345678}";
    String prices=String.join(",",java.util.Collections.nCopies(80,row));
    sections.put(Section.MATERIAL_PRICES,"{\"prices\":["+prices+"]}");
    var complete=MonthlyRepriceInputDocument.capture(scope,sections);
    assertThat(complete.canonical().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(2*1024*1024);
    var saved=groups.register("B1",complete);
    assertThat(saved.inputDocument()).isEqualTo(complete.canonical());
    assertThat(jdbc.queryForObject("SELECT OCTET_LENGTH(input_document) FROM lp_monthly_reprice_compute_group WHERE id=?",Integer.class,saved.id()))
        .as("immutable input should not be repeated in full for every lease-state binlog image")
        .isLessThan(complete.canonical().getBytes(java.nio.charset.StandardCharsets.UTF_8).length/2);
    assertThat(groups.register("B1",complete).id()).isEqualTo(saved.id());
    sections.put(Section.MATERIAL_PRICES,"{\"prices\":["+prices.replace("0.123456789012345678","0.123456789012345679")+"]}");
    var changed=groups.register("B1",MonthlyRepriceInputDocument.capture(scope,sections));
    assertThat(changed.id()).isNotEqualTo(saved.id());
    assertThat(mapper.readGroup(saved.id()).inputDocument()).isEqualTo(complete.canonical());
    jdbc.update("UPDATE lp_monthly_reprice_compute_group SET input_document=? WHERE id=?",complete.canonical(),saved.id());
    assertThat(groups.register("B1",complete).id()).as("saved plain input remains readable without a migration").isEqualTo(saved.id());
  }

  @Test void independentConnectionsPublishTheOriginalArithmeticOnceAndReloadEveryDecimalExactly() throws Exception {
    var start = new CountDownLatch(1);
    var calculations = new java.util.concurrent.atomic.AtomicInteger();
    var expected = calculatedNumbers();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<java.util.concurrent.Future<MonthlyRepriceSharedNumbers.Published>>();
      for (int i=0; i<8; i++) futures.add(pool.submit(() -> {
        start.await();
        return shared.getOrCalculate("B1", input("00123", "1.000", false), () -> {
          calculations.incrementAndGet(); return calculatedNumbers();
        });
      }));
      start.countDown();
      var results = new ArrayList<MonthlyRepriceSharedNumbers.Published>();
      for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
      assertThat(calculations).hasValue(1);
      assertThat(results).extracting(MonthlyRepriceSharedNumbers.Published::groupId).containsOnly(results.getFirst().groupId());
      assertThat(results).allSatisfy(row -> assertThat(row.numbers()).isEqualTo(expected));
      assertThat(jdbc.queryForMap("SELECT status,attempt_count FROM lp_monthly_reprice_compute_group WHERE id=?",results.getFirst().groupId()))
          .containsEntry("status","SUCCESS").containsEntry("attempt_count",1);
    }
  }

  @Test void calculationRunsWithACommittedLeaseAndWithoutHoldingADatabaseTransaction() {
    var input=input("00123","1",false);
    var result=shared.getOrCalculate("B1",input,()->{
      assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
          .as("pure arithmetic must not retain the group transaction").isFalse();
      var state=jdbc.queryForMap("SELECT status,worker_id,execution_no,attempt_count,lease_expires_at FROM lp_monthly_reprice_compute_group");
      assertThat(state).containsEntry("status","RUNNING").containsEntry("attempt_count",1);
      assertThat(state.get("worker_id")).isNotNull();
      assertThat(state.get("lease_expires_at")).isNotNull();
      return calculatedNumbers();
    });
    assertThat(mapper.readGroup(result.groupId()).status()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForMap("SELECT worker_id,lease_expires_at,attempt_count FROM lp_monthly_reprice_compute_group"))
        .containsEntry("worker_id",null).containsEntry("lease_expires_at",null).containsEntry("attempt_count",1);
  }

  @Test void expiredExecutionCannotPublishAndTakeoverKeepsExactlyOneFinalResult() {
    var input=input("00123","1",false);
    long id=groups.register("B1",input).id();
    var first=((MonthlyRepriceGroupLeases.Acquired)leases.claim(id,input)).token();
    var another=new MonthlyRepriceGroupLeases(mapper,transactions,new MonthlyRepriceInstance("lease-tests"),30);
    assertThat(another.claim(id,input)).isEqualTo(MonthlyRepriceGroupLeases.Busy.INSTANCE);
    jdbc.update("UPDATE lp_monthly_reprice_compute_group SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",id);
    assertThatThrownBy(()->leases.publish(first,encodedNumbers())).isInstanceOf(MonthlyRepriceGroupLeases.LeaseLost.class);
    var next=((MonthlyRepriceGroupLeases.Acquired)another.claim(id,input)).token();
    assertThat(next.owner().executionNo()).isEqualTo(first.owner().executionNo()+1);
    assertThat(next.owner().workerId()).isNotEqualTo(first.owner().workerId());
    assertThatThrownBy(()->leases.publish(first,encodedNumbers())).isInstanceOf(MonthlyRepriceGroupLeases.LeaseLost.class);
    assertThat(leases.fail(first,"late error")).isFalse();
    another.publish(next,encodedNumbers());
    assertThat(leases.claim(id,input)).isEqualTo(new MonthlyRepriceGroupLeases.Published(encodedNumbers()));
    assertThat(jdbc.queryForMap("SELECT status,attempt_count,worker_id,lease_expires_at FROM lp_monthly_reprice_compute_group WHERE id=?",id))
        .containsEntry("status","SUCCESS").containsEntry("attempt_count",2).containsEntry("worker_id",null).containsEntry("lease_expires_at",null);
    assertThatThrownBy(()->another.publish(next,encodedNumbers())).isInstanceOf(MonthlyRepriceGroupLeases.LeaseLost.class);
  }

  @Test void publicationChecksOwnerGenerationDigestSchemaAndBothAlgorithmPolicies() {
    var input=input("00123","1",false);
    long id=groups.register("B1",input).id();
    var token=((MonthlyRepriceGroupLeases.Acquired)leases.claim(id,input)).token();
    var owner=token.owner();
    for(var wrong:List.of(
        new MonthlyRepriceReuseMapper.Ownership(id,owner.workerId()+"x",owner.executionNo(),owner.inputDigest(),1,owner.algorithmVersion(),owner.feePolicyVersion()),
        new MonthlyRepriceReuseMapper.Ownership(id,owner.workerId(),owner.executionNo()+1,owner.inputDigest(),1,owner.algorithmVersion(),owner.feePolicyVersion()),
        new MonthlyRepriceReuseMapper.Ownership(id,owner.workerId(),owner.executionNo(),"0".repeat(64),1,owner.algorithmVersion(),owner.feePolicyVersion()),
        new MonthlyRepriceReuseMapper.Ownership(id,owner.workerId(),owner.executionNo(),owner.inputDigest(),2,owner.algorithmVersion(),owner.feePolicyVersion()),
        new MonthlyRepriceReuseMapper.Ownership(id,owner.workerId(),owner.executionNo(),owner.inputDigest(),1,"NEXT",owner.feePolicyVersion()),
        new MonthlyRepriceReuseMapper.Ownership(id,owner.workerId(),owner.executionNo(),owner.inputDigest(),1,owner.algorithmVersion(),"NEXT"))) {
      var invalid=new MonthlyRepriceGroupLeases.Token(wrong,input);
      assertThatThrownBy(()->leases.publish(invalid,encodedNumbers())).isInstanceOf(MonthlyRepriceGroupLeases.LeaseLost.class);
      assertThat(leases.fail(invalid,"not the owner")).isFalse();
    }
    jdbc.update("UPDATE lp_monthly_reprice_compute_group SET input_document=? WHERE id=?",input("00123","2",false).canonical(),id);
    assertThatThrownBy(()->leases.publish(token,encodedNumbers())).hasMessageContaining("完整输入");
    jdbc.update("UPDATE lp_monthly_reprice_compute_group SET input_document=?,algorithm_version='NEXT' WHERE id=?",input.canonical(),id);
    assertThatThrownBy(()->leases.publish(token,encodedNumbers())).hasMessageContaining("算法");
    jdbc.update("UPDATE lp_monthly_reprice_compute_group SET algorithm_version=? WHERE id=?",input.scope().algorithmVersion(),id);
    leases.publish(token,encodedNumbers());
    assertThat(mapper.readResult(id)).isEqualTo(encodedNumbers());
  }

  @Test void busyWaitIsBoundedAndNeverStealsAnUnexpiredExecution() {
    var input=input("00123","1",false);
    long id=groups.register("B1",input).id();
    var token=((MonthlyRepriceGroupLeases.Acquired)leases.claim(id,input)).token();
    var other=new MonthlyRepriceGroupLeases(mapper,transactions,new MonthlyRepriceInstance("other"),30);
    var waiter=new MonthlyRepriceSharedNumbers(groups,mapper,new com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec(
        new com.fasterxml.jackson.databind.ObjectMapper()),transactions,other,20);
    var attempts=new java.util.concurrent.atomic.AtomicInteger();
    assertThatThrownBy(()->waiter.getOrCalculate("B1",input,()->{attempts.incrementAndGet();return calculatedNumbers();}))
        .isInstanceOf(MonthlyRepriceSharedNumbers.GroupBusy.class);
    assertThat(attempts).hasValue(0);
    assertThat(mapper.readGroup(id).executionNo()).isEqualTo(token.owner().executionNo());
    assertThat(jdbc.queryForObject("SELECT attempt_count FROM lp_monthly_reprice_compute_group WHERE id=?",Integer.class,id)).isEqualTo(1);
    leases.publish(token,encodedNumbers());
    assertThat(waiter.getOrCalculate("B1",input,()->{throw new AssertionError("published result must be reused");}).groupId()).isEqualTo(id);
  }

  @Test void anEmptyOptionalDeploymentLabelStillProducesUniqueProcessOwners() {
    var firstInput=input("00123","1",false);
    var secondInput=input("OTHER","1",false);
    var first=new MonthlyRepriceGroupLeases(mapper,transactions,new MonthlyRepriceInstance(""),30);
    var second=new MonthlyRepriceGroupLeases(mapper,transactions,new MonthlyRepriceInstance(""),30);
    var a=((MonthlyRepriceGroupLeases.Acquired)first.claim(groups.register("B1",firstInput).id(),firstInput)).token();
    var b=((MonthlyRepriceGroupLeases.Acquired)second.claim(groups.register("B1",secondInput).id(),secondInput)).token();
    assertThat(a.owner().workerId()).startsWith("monthly-reprice-").isNotEqualTo(b.owner().workerId());
  }

  private static String encodedNumbers() {
    return new com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec(
        new com.fasterxml.jackson.databind.ObjectMapper()).encode(calculatedNumbers());
  }

  @Test void arithmeticFailureLeavesNoPartialPublicationAndSuccessfulPublicationSurvivesConsumerRollback() {
    var input = input("00123","1",false);
    assertThatThrownBy(() -> shared.getOrCalculate("B1",input,() -> {
      throw new IllegalArgumentException("incomplete input");
    })).hasMessage("incomplete input");
    long group = groups.register("B1",input).id();
    assertThat(mapper.readGroup(group).status()).isEqualTo("FAILED");
    assertThat(jdbc.queryForObject("SELECT result_document FROM lp_monthly_reprice_compute_group WHERE id=?",String.class,group)).isNull();
    object("A","00123","CUSTOMER-A","2026-11","COMMERCIAL");
    object("B","00123","CUSTOMER-B","2026-11","COMMERCIAL");
    assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
      var published=shared.getOrCalculate("B1",input,()->{
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return calculatedNumbers();
      });
      shared.bind("B1","A",published.groupId());
      throw new IllegalStateException("consumer save failed");
    })).hasMessage("consumer save failed");
    assertThat(mapper.readGroup(group).status()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("SELECT compute_group_id FROM lp_monthly_reprice_result WHERE oa_no='A'",Long.class)).isNull();
    var restored = shared.getOrCalculate("B1",input,() -> { throw new AssertionError("must not recalculate"); });
    transaction.executeWithoutResult(tx -> {
      shared.bind("B1","A",restored.groupId()); shared.bind("B1","B",restored.groupId());
    });
    assertThat(jdbc.queryForList("SELECT compute_group_id FROM lp_monthly_reprice_result WHERE oa_no IN ('A','B')",Long.class))
        .containsExactly(group,group);
    assertThatThrownBy(() -> shared.bind("OTHER","A",group)).hasMessageContaining("范围不一致");
    assertThatThrownBy(() -> shared.bind("B1","missing",group)).hasMessageContaining("范围不一致");
  }

  @Test void repeatedCustomerBindingIsIdempotentAfterSuccessAndCannotReplaceItsGroup() throws Exception {
    long first=shared.getOrCalculate("B1",input("00123","1",false),
        MonthlyRepriceReusePersistenceIntegrationTest::calculatedNumbers).groupId();
    long changed=shared.getOrCalculate("B1",input("00123","2",false),
        MonthlyRepriceReusePersistenceIntegrationTest::calculatedNumbers).groupId();
    object("A","00123","CUSTOMER-A","2026-11","COMMERCIAL");
    object("B","00123","CUSTOMER-B","2026-11","COMMERCIAL");
    shared.bind("B1","A",first); shared.bind("B1","A",first);
    shared.bind("B1","B",first);
    jdbc.update("UPDATE lp_monthly_reprice_result SET calc_status='SUCCESS',cost_run_version_id=1,total_cost=54.282496 WHERE oa_no='A'");
    var saved=jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_result WHERE oa_no='A'");
    try(var pool=Executors.newFixedThreadPool(8)) {
      var futures=new ArrayList<java.util.concurrent.Future<?>>();
      for(int i=0;i<8;i++) futures.add(pool.submit(()->shared.bind("B1","A",first)));
      for(var future:futures) future.get(10,TimeUnit.SECONDS);
    }
    assertThat(jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_result WHERE oa_no='A'"))
        .usingRecursiveComparison().isEqualTo(saved);
    for(String object:List.of("A","B"))
      assertThatThrownBy(()->shared.bind("B1",object,changed)).hasMessageContaining("范围不一致");
    assertThatThrownBy(()->shared.bind("OTHER","A",first)).hasMessageContaining("范围不一致");
    assertThat(jdbc.queryForList("SELECT compute_group_id FROM lp_monthly_reprice_result WHERE oa_no IN ('A','B')",Long.class))
        .containsExactly(first,first);
  }

  @Test void unchangedBindingAlsoSucceedsWhenJdbcReturnsOnlyChangedRowCounts() throws Exception {
    long group=shared.getOrCalculate("B1",input("00123","1",false),
        MonthlyRepriceReusePersistenceIntegrationTest::calculatedNumbers).groupId();
    object("A","00123","CUSTOMER-A","2026-11","COMMERCIAL");
    shared.bind("B1","A",group);
    String url=MYSQL.getJdbcUrl()+(MYSQL.getJdbcUrl().contains("?")?"&":"?")+"useAffectedRows=true";
    var source=new DriverManagerDataSource(url,MYSQL.getUsername(),MYSQL.getPassword());
    var configuration=new org.apache.ibatis.session.Configuration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setArgNameBasedConstructorAutoMapping(true);
    configuration.addMapper(MonthlyRepriceReuseMapper.class);
    var factory=new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
    var changedRows=new SqlSessionTemplate(factory.getObject()).getMapper(MonthlyRepriceReuseMapper.class);
    assertThat(changedRows.bindGroup("B1","A",group)).isZero();
    var binding=new MonthlyRepriceSharedNumbers(groups,changedRows,
        new com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper()),
        transactions,leases,5000);
    binding.bind("B1","A",group);
    jdbc.update("UPDATE lp_monthly_reprice_result SET calc_status='SUCCESS',cost_run_version_id=1,total_cost=54.282496 WHERE oa_no='A'");
    binding.bind("B1","A",group);
    assertThat(changedRows.alreadyBound("B1","A",group)).isTrue();
  }

  private static MonthlyRepriceSharedNumbers.Numbers calculatedNumbers() {
    var rows = new ArrayList<com.sanhua.marketingcost.dto.CostRunCostItemDto>();
    for (String code : List.of("MATERIAL","DIRECT_LABOR","INDIRECT_LABOR","LOSS","MANUFACTURE",
        "MANUFACTURE_COST","ADJUSTED_MANUFACTURE_COST","MGMT_EXP","SALES_EXP","FIN_EXP","TOTAL")) {
      var row = new com.sanhua.marketingcost.dto.CostRunCostItemDto(); row.setCostCode(code);
      row.setAmount(new java.math.BigDecimal("1.123456")); row.setRate(new java.math.BigDecimal("0.01234567890123456789"));
      if (code.equals("ADJUSTED_MANUFACTURE_COST")) row.setRate(java.math.BigDecimal.ONE);
      rows.add(row);
    }
    var input = new com.sanhua.marketingcost.service.costing.ResolvedOrdinaryCost(rows,java.math.BigDecimal.ZERO,true);
    var ordinary = com.sanhua.marketingcost.service.costing.ResolvedOrdinaryCost.calculate(input.inputs());
    var z=java.math.BigDecimal.ZERO;
    var monthlyInput = new com.sanhua.marketingcost.service.costing.MonthlyProductCost.Ordinary(List.of(),
        new java.math.BigDecimal("123.123456"),z,z,z,z,java.math.BigDecimal.ONE,z,z,z,z);
    var monthly = com.sanhua.marketingcost.service.costing.MonthlyProductCost.resolve(
        new com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan(Map.of(),List.of(),java.util.Set.of()),monthlyInput,null);
    return new MonthlyRepriceSharedNumbers.Numbers(1,ordinary,
        com.sanhua.marketingcost.service.costing.MonthlyProductCost.calculate(monthly.inputs()));
  }

  @Test void sameDigestDifferentFullInputIsStoredAsTwoGroupsAndEachFindsItsOwnResult() {
    var first = input("00123", "0.123456789012345678", true);
    var second = input("00123", "0.123456789012345679", true);
    var a = groups.register("B1", first);
    var b = groups.register("B1", second);
    assertThat(a.inputDigest()).isEqualTo(b.inputDigest());
    assertThat(a.id()).isNotEqualTo(b.id());
    assertThat(groups.register("B1", first).id()).isEqualTo(a.id());
    assertThat(groups.register("B1", second).id()).isEqualTo(b.id());
    assertThat(a.inputDocument()).isEqualTo(first.canonical());
    assertThat(b.inputDocument()).isEqualTo(second.canonical());
  }

  @Test void groupScopeAndTransactionFailureCannotLeaveAnInvalidRegistration() {
    batch("H1", "HOUSEHOLD", "2026-11");
    assertThatThrownBy(() -> groups.register("H1", input("00123", "1", false)))
        .isInstanceOf(IllegalArgumentException.class);
    var failingMapper = org.mockito.Mockito.mock(MonthlyRepriceReuseMapper.class,
        org.mockito.AdditionalAnswers.delegatesTo(mapper));
    org.mockito.Mockito.doAnswer(call -> {
      mapper.insertGroup(call.getArgument(0));
      throw new IllegalStateException("registration rollback");
    }).when(failingMapper).insertGroup(org.mockito.ArgumentMatchers.any());
    var failingRegistration = new MonthlyRepriceComputeGroups(failingMapper, transactions);
    assertThatThrownBy(() -> failingRegistration.register("B1", input("00123", "1", false)))
        .hasMessage("registration rollback");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_compute_group", Integer.class)).isZero();
  }

  @Test void registrationRetainsItsCommittedInputWhenTheCustomerTransactionFails() {
    var input = input("00123", "1", false);
    var registered = new java.util.concurrent.atomic.AtomicReference<MonthlyRepriceReuseMapper.Group>();
    assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
      registered.set(groups.register("B1", input));
      throw new IllegalStateException("customer rollback");
    })).hasMessage("customer rollback");
    assertThat(mapper.readGroup(registered.get().id())).isEqualTo(registered.get());
    assertThat(groups.register("B1", input).id()).isEqualTo(registered.get().id());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_compute_group", Integer.class)).isEqualTo(1);
  }

  @Test void databaseBusinessUniquenessUsesFullCaseSensitiveIdentity() {
    object("OA-1", "00123", "Customer01", "2026-11", "COMMERCIAL");
    assertThatThrownBy(() -> object("OA-2", "00123", "Customer01", "2026-11", "COMMERCIAL"))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    object("OA-2", "00123", "customer01", "2026-11", "COMMERCIAL");
    object("OA-3", "123", "Customer01", "2026-11", "COMMERCIAL");
    jdbc.update("""
        UPDATE lp_monthly_reprice_batch SET status='CONFIRMED',check_status='PASSED',checked_revision=0,
          checked_at=NOW(),check_result_json='{}',confirmed_by='contract-test',confirmed_at=NOW()
        WHERE reprice_no='B1'
        """);
    batch("B2", "COMMERCIAL", "2026-12");
    batch("H1", "HOUSEHOLD", "2026-11");
    object("OA-4", "00123", "Customer01", "2026-12", "COMMERCIAL");
    object("OA-5", "00123", "Customer01", "2026-11", "HOUSEHOLD");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_result WHERE customer_identity_source IS NOT NULL", Integer.class))
        .isEqualTo(5);
    assertConstraint(() -> jdbc.update("""
        UPDATE lp_monthly_reprice_result SET contract_version=NULL WHERE oa_no='OA-1'
        """), "ck_reprice_customer_identity");
  }

  @Test void unresolvedSourcesStaySeparateAndSameSourceCannotBeCountedTwice() {
    long batchId = jdbc.queryForObject("SELECT id FROM lp_monthly_reprice_batch WHERE reprice_no='B1'", Long.class);
    jdbc.update("""
        INSERT INTO lp_monthly_reprice_source(batch_id,source_cost_version_id,oa_no,oa_form_item_id,issue_code)
        VALUES(?,1,'OA-1',1,'MISSING_CUSTOMER')
        """, batchId);
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO lp_monthly_reprice_source(batch_id,source_cost_version_id,oa_no,oa_form_item_id,issue_code)
        VALUES(?,1,'OA-2',2,'MISSING_CUSTOMER')
        """, batchId)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    jdbc.update("""
        INSERT INTO lp_monthly_reprice_source(batch_id,source_cost_version_id,oa_no,oa_form_item_id,issue_code)
        VALUES(?,2,'OA-2',2,'MISSING_CUSTOMER')
        """, batchId);
    assertConstraint(() -> jdbc.update("UPDATE lp_monthly_reprice_source SET issue_code=NULL"),
        "ck_reprice_source_resolution");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_source WHERE object_id IS NULL AND issue_code='MISSING_CUSTOMER'", Integer.class))
        .isEqualTo(2);
  }

  @Test void twoCustomerObjectsCanReferenceOneGroupWithoutSharingTheirOwnership() {
    var shared = groups.register("B1", input("00123", "1", false));
    object("OA-A", "00123", "CUSTOMER-A", "2026-11", "COMMERCIAL");
    object("OA-B", "00123", "CUSTOMER-B", "2026-11", "COMMERCIAL");
    jdbc.update("UPDATE lp_monthly_reprice_result SET source_cost_version_id=2 WHERE oa_no='OA-B'");
    jdbc.update("UPDATE lp_monthly_reprice_result SET compute_group_id=? WHERE oa_no IN ('OA-A','OA-B')", shared.id());
    var rows = jdbc.queryForList("""
        SELECT customer_identity_value,oa_no,source_cost_version_id,compute_group_id
        FROM lp_monthly_reprice_result WHERE compute_group_id=? ORDER BY oa_no
        """, shared.id());
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("customer_identity_value", "CUSTOMER-A")
        .containsEntry("oa_no", "OA-A").containsEntry("source_cost_version_id", 1L);
    assertThat(rows.get(1)).containsEntry("customer_identity_value", "CUSTOMER-B")
        .containsEntry("oa_no", "OA-B").containsEntry("source_cost_version_id", 2L);
  }

  @Test void rejectsInconsistentSavedAlgorithmMetadataAndClosedBatch() {
    var input = input("00123", "1", false);
    var registered = groups.register("B1", input);
    jdbc.update("UPDATE lp_monthly_reprice_compute_group SET algorithm_version='OTHER' WHERE id=?", registered.id());
    assertThatThrownBy(() -> groups.register("B1", input)).hasMessageContaining("算法与输入文档不一致");
    jdbc.update("UPDATE lp_monthly_reprice_compute_group SET algorithm_version=? WHERE id=?",
        input.scope().algorithmVersion(), registered.id());
    jdbc.update("UPDATE lp_monthly_reprice_batch SET status='CHECKING' WHERE reprice_no='B1'");
    assertThatThrownBy(() -> groups.register("B1", input)).hasMessageContaining("不能登记计算组");
  }

  @Test void databaseRejectsCrossBatchGroupAndSourceBindings() {
    batch("H1", "HOUSEHOLD", "2026-11");
    var group = groups.register("B1", input("00123", "1", false));
    object("OTHER-UNIT", "00123", "C", "2026-11", "HOUSEHOLD");
    long object = jdbc.queryForObject("SELECT id FROM lp_monthly_reprice_result WHERE oa_no='OTHER-UNIT'", Long.class);
    assertThatThrownBy(() -> jdbc.update("UPDATE lp_monthly_reprice_result SET compute_group_id=? WHERE id=?", group.id(), object))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO lp_monthly_reprice_source(batch_id,object_id,source_cost_version_id,oa_no,oa_form_item_id)
        VALUES(?,?,1,'SOURCE',1)
        """, group.batchId(), object)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }


  @Test void fullCanonicalTextRoundTripsWithoutMysqlJsonNumberConversion() {
    var sections=new EnumMap<Section,String>(Section.class);
    for(var section:Section.values()) sections.put(section,"{\"required\":true}");
    sections.put(Section.TECHNICAL_INPUTS,"{\"fee\":999999999999.999999,\"optional\":null,\"supplier\":\"供应商\"}");
    sections.put(Section.BOM,"{\"lines\":[{\"part\":\"001\",\"qty\":0.123456789012345678},{\"part\":\"001\",\"qty\":0.123456789012345678}]}");
    var input=MonthlyRepriceInputDocument.capture(new MonthlyRepriceInputDocument.Scope("COMMERCIAL",
        YearMonth.of(2026,11),"00123",new QuoteDataOrganization("210","COMMERCIAL"),
        "MONTHLY_PRODUCT_COST_V1","FEE_PROFILE_OPTIONAL_V2"),sections);
    var saved=groups.register("B1",input);
    var reloaded=mapper.readGroup(saved.id());
    assertThat(reloaded.inputDocument()).isEqualTo(input.canonical());
    assertThat(reloaded.inputDocument()).contains("999999999999.999999","0.123456789012345678","供应商");
    assertThat(groups.register("B1",input).id()).isEqualTo(saved.id());
    assertThat(jdbc.queryForObject("SELECT JSON_LENGTH(JSON_EXTRACT(input_document,'$.BOM.lines')) FROM lp_monthly_reprice_compute_group WHERE id=?",Integer.class,saved.id())).isEqualTo(2);
  }


  @Test void sourceCountersCannotBePartialNegativeOrLoseKnownObjects() {
    for(String assignments:List.of("source_count=1","scope_version=1",
        "scope_version=1,source_count=1,unresolved_source_count=-1",
        "scope_version=1,source_count=1,unresolved_source_count=2",
        "scope_version=1,source_count=1,unresolved_source_count=1,total_count=1")) {
      assertConstraint(()->jdbc.update("UPDATE lp_monthly_reprice_batch SET "+assignments+" WHERE reprice_no='B1'"),"ck_reprice_batch_source_counts");
    }
    jdbc.update("UPDATE lp_monthly_reprice_batch SET scope_version=1,source_count=4,unresolved_source_count=1,total_count=2 WHERE reprice_no='B1'");
    assertThat(jdbc.queryForObject("SELECT source_count FROM lp_monthly_reprice_batch WHERE reprice_no='B1'",Integer.class)).isEqualTo(4);
  }

  @Test void customerObjectCannotNameADifferentBatchWhileKeepingItsNumericOwner() {
    object("OA-1","00123","C","2026-11","COMMERCIAL");
    assertThatThrownBy(()->jdbc.update("UPDATE lp_monthly_reprice_result SET reprice_no='UNRELATED' WHERE oa_no='OA-1'"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasStackTraceContaining("fk_reprice_result_batch_scope");
  }

  @Test void savedGroupRejectsWrongDocumentShapeOrSchemaEvenWhenJsonIsValid() {
    var group=groups.register("B1",input("00123","1",false));
    for(String document:List.of("[]","{}","{\"schemaVersion\":2}","{\"schemaVersion\":\"1\"}","{\"schemaVersion\":null}")) {
      assertConstraint(()->jdbc.update("UPDATE lp_monthly_reprice_compute_group SET input_document=? WHERE id=?",document,group.id()),"ck_reprice_group_input");
    }
    assertConstraint(()->jdbc.update("UPDATE lp_monthly_reprice_compute_group SET execution_no=0 WHERE id=?",group.id()),"ck_reprice_group_input");
  }

  @Test void runningAndPublishedGroupStatesRequireCompleteOwnedEvidence() {
    var group=groups.register("B1",input("00123","1",false));
    assertConstraint(()->jdbc.update("UPDATE lp_monthly_reprice_compute_group SET status='RUNNING' WHERE id=?",group.id()),"ck_reprice_group_state");
    jdbc.update("UPDATE lp_monthly_reprice_compute_group SET status='RUNNING',worker_id='worker-a',lease_expires_at=DATE_ADD(NOW(),INTERVAL 2 MINUTE) WHERE id=?",group.id());
    assertConstraint(()->jdbc.update("UPDATE lp_monthly_reprice_compute_group SET status='SUCCESS' WHERE id=?",group.id()),"ck_reprice_group_state");
    assertConstraint(()->jdbc.update("UPDATE lp_monthly_reprice_compute_group SET status='SUCCESS',worker_id=NULL,lease_expires_at=NULL,result_document='[]',published_at=NOW() WHERE id=?",group.id()),"ck_reprice_group_state");
    jdbc.update("UPDATE lp_monthly_reprice_compute_group SET status='SUCCESS',worker_id=NULL,lease_expires_at=NULL,result_document='{\"schemaVersion\":1,\"total\":54.282496}',published_at=NOW() WHERE id=?",group.id());
    assertThat(mapper.readGroup(group.id()).status()).isEqualTo("SUCCESS");
    assertConstraint(()->jdbc.update("UPDATE lp_monthly_reprice_compute_group SET status='PENDING' WHERE id=?",group.id()),"ck_reprice_group_state");
  }


  @Test void reverseLookupIndexesAlsoSupportForeignKeysWithoutDuplicateAutoIndexes() {
    assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='lp_monthly_reprice_source' AND INDEX_NAME='idx_reprice_source_object' ORDER BY SEQ_IN_INDEX",String.class))
        .containsExactly("object_id","batch_id","id");
    assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='lp_monthly_reprice_result' AND INDEX_NAME='idx_reprice_result_group' ORDER BY SEQ_IN_INDEX",String.class))
        .containsExactly("compute_group_id","reuse_batch_id","id");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND ((TABLE_NAME='lp_monthly_reprice_source' AND INDEX_NAME='fk_reprice_source_result') OR (TABLE_NAME='lp_monthly_reprice_result' AND INDEX_NAME='fk_reprice_result_group'))",Integer.class)).isZero();
  }

  private void object(String oa, String product, String customer, String month, String unit) {
    var batch = jdbc.queryForMap("""
        SELECT id,reprice_no FROM lp_monthly_reprice_batch
        WHERE business_unit_type=? AND pricing_month=? AND contract_version=1
        """, unit, month);
    jdbc.update("""
        INSERT INTO lp_monthly_reprice_result(reprice_no,reuse_batch_id,pricing_month,business_unit_type,oa_no,oa_form_item_id,
          product_code,calc_object_key,source_cost_version_id,contract_version,calc_status,
          customer_identity_source,customer_identity_value,merged_source_count)
        VALUES(?,?,?,?,?,1,?,?,1,1,'PENDING','OA_HEADER_CUSTOMER',?,4)
        """, batch.get("reprice_no"), batch.get("id"), month, unit, oa, product, oa, customer);
  }

  private static void assertConstraint(Runnable action, String constraint) {
    assertThatThrownBy(action::run).isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasRootCauseInstanceOf(java.sql.SQLException.class).satisfies(failure -> {
          var sql = (java.sql.SQLException) org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure);
          assertThat(sql.getErrorCode()).isEqualTo(3819);
          assertThat(sql.getMessage()).contains(constraint);
        });
  }

  private void batch(String no, String unit, String month) {
    jdbc.update("""
        INSERT INTO lp_monthly_reprice_batch(reprice_no,pricing_month,price_as_of_time,business_unit_type,
          status,contract_version,go_live_month,result_revision,check_status)
        VALUES(?,?,'2026-11-01',?,'QUEUED',1,'2026-10',0,'PENDING')
        """, no, month, unit);
  }

  private static MonthlyRepriceInputDocument input(String product, String number, boolean collision) {
    var sections = new EnumMap<Section, String>(Section.class);
    for (var section : Section.values()) sections.put(section, "{\"value\":" + number + "}");
    var scope = new MonthlyRepriceInputDocument.Scope("COMMERCIAL", YearMonth.of(2026, 11), product,
        new QuoteDataOrganization("210", "COMMERCIAL"), "MONTHLY_PRODUCT_COST_V1", "FEE_PROFILE_OPTIONAL_V2");
    return collision ? MonthlyRepriceInputDocument.capture(scope, sections, ignored -> "0".repeat(64))
        : MonthlyRepriceInputDocument.capture(scope, sections);
  }
}
