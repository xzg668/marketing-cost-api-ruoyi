package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceContract.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real production Mapper + transaction path, on disposable MySQL only. Fixtures are contract data,
 * not claimed as an end-to-end quotation/repricing acceptance. */
@Tag("integration")
class MonthlyRepriceContractIntegrationTest {
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName
      .parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("mrp1130_contract_test").withUsername("root").withPassword("mrp-contract-test-only")
      .withConfigurationOverride("monthly-pricing/mysql");
  static JdbcTemplate jdbc;
  static MonthlyRepriceContractMapper mapper;
  static MonthlyRepriceBatchMapper history;
  static MonthlyRepriceResultMapper historicalResults;
  static MonthlyRepriceContractService service;
  static TransactionTemplate tx;
  static MonthlyQuotationControl control;
  static MonthlyRepriceWorkflowMapper workflow;
  static MonthlyRepriceDispatchService dispatch;
  static com.sanhua.marketingcost.config.MonthlyRepriceProperties properties;
  static MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
  static Map<String,Object> oldBatch, oldResult, oldVersion;
  static final YearMonth MONTH = YearMonth.of(2026,1), LIVE = YearMonth.of(2025,11);

  @BeforeAll static void database() throws Exception {
    MYSQL.start();
    var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
    jdbc = new JdbcTemplate(ds);
    assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    jdbc.execute("CREATE TABLE oa_form(id BIGINT PRIMARY KEY,oa_no VARCHAR(64),business_unit_type VARCHAR(32),calc_status VARCHAR(32))");
    jdbc.execute("CREATE TABLE oa_form_item(id BIGINT PRIMARY KEY,oa_form_id BIGINT,business_unit_type VARCHAR(32),calc_status VARCHAR(32))");
    jdbc.update("INSERT INTO oa_form(id,oa_no,business_unit_type) VALUES(1,'MRP1130-TEST','COMMERCIAL'),(2,'MRP1130-TEST','HOUSEHOLD')");
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");
    jdbc.execute("CREATE TABLE lp_material_monthly_price(id BIGINT PRIMARY KEY)");
    new ResourceDatabasePopulator(new ClassPathResource("monthly-reprice/pre-v348-schema.sql")).execute(ds);
    jdbc.update("INSERT INTO lp_monthly_reprice_batch(id,reprice_no,pricing_month,price_as_of_time,business_unit_type,status,remark) VALUES(1,'LEGACY','2025-10','2025-10-01','COMMERCIAL','CONFIRMED','原备注')");
    jdbc.update("INSERT INTO lp_monthly_reprice_result(id,reprice_no,pricing_month,business_unit_type,oa_no,product_code,calc_object_key,total_cost) VALUES(1,'LEGACY','2025-10','COMMERCIAL','OLD','0001','original-key',12.123456)");
    jdbc.update("INSERT INTO lp_quote_cost_run_version(id,cost_run_no,oa_no,product_code,pricing_month,result_period,status,total_cost) VALUES(1,'LEGACY','OLD','0001','2025-10','2025-10','SUCCESS',12.123456)");
    oldBatch=jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_batch WHERE id=1");
    oldResult=jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_result WHERE id=1");
    oldVersion=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=1");
    new ResourceDatabasePopulator(new ClassPathResource("db/V348__monthly_reprice_1130_contract.sql")).execute(ds);
    var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    for (var type : List.of(MonthlyRepriceContractMapper.class,MonthlyRepriceBatchMapper.class,MonthlyRepriceResultMapper.class,MonthlyRepriceWorkflowMapper.class)) config.addMapper(type);
    var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
    factory.setConfiguration(config); factory.setDataSource(ds);
    var sql = new SqlSessionTemplate(factory.getObject());
    mapper=sql.getMapper(MonthlyRepriceContractMapper.class);
    history=sql.getMapper(MonthlyRepriceBatchMapper.class); historicalResults=sql.getMapper(MonthlyRepriceResultMapper.class);
    var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
    properties=new com.sanhua.marketingcost.config.MonthlyRepriceProperties();
    service=new MonthlyRepriceContractService(mapper,manager,new ObjectMapper(),codec,properties);
    control=new MonthlyQuotationControl(ds);workflow=sql.getMapper(MonthlyRepriceWorkflowMapper.class);
    dispatch=new MonthlyRepriceDispatchService(mapper,workflow,service,
        org.mockito.Mockito.mock(MonthlyRepriceCheckService.class),manager);
  }
  @AfterAll static void stop() { MYSQL.stop(); }
  @BeforeEach void clean() {
    for (String table : List.of("lp_cost_run_part_item","lp_cost_run_cost_item","lp_cost_run_task","lp_cost_run_batch")) jdbc.update("DELETE FROM "+table);
    for (String table : List.of("lp_monthly_reprice_result","lp_monthly_reprice_batch","lp_quote_cost_run_version")) jdbc.update("DELETE FROM "+table+" WHERE id<>1");
    properties.setGoLiveMonths(Map.of("COMMERCIAL",LIVE.toString(),"HOUSEHOLD",LIVE.toString()));
    auth("COMMERCIAL","ROLE_BU_DIRECTOR");
  }
  @AfterEach void logout() { SecurityContextHolder.clearContext(); }
  static void auth(String unit,String... roles) {
    var a=new UsernamePasswordAuthenticationToken("mrp-director",null,Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    a.setDetails(Map.of("businessUnitType",unit));SecurityContextHolder.getContext().setAuthentication(a);
  }
  static Source source(String unit,long item) {
    jdbc.update("INSERT IGNORE INTO oa_form_item(id,oa_form_id,business_unit_type) VALUES(?,?,?)",item,unit.equals("COMMERCIAL")?1:2,unit);
    String no=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO lp_quote_cost_run_version(cost_run_no,oa_no,oa_form_item_id,product_code,pricing_month,result_period,status,total_cost,trial_finished_at,business_unit_type) VALUES(?,'MRP1130-TEST',?,'TOP','2025-12','2025-12','SUCCESS',12.123456,'2025-12-15',?)",no,item,unit);
    return new Source(new ObjectIdentity(unit,"MRP1130-TEST",item),jdbc.queryForObject("SELECT id FROM lp_quote_cost_run_version WHERE cost_run_no=?",Long.class,no));
  }
  static MonthlyRepriceBatch start(Source... sources) { return service.enqueue("COMMERCIAL",MONTH,LIVE,List.of(sources)); }
  static CostRunTask task(MonthlyRepriceBatch b) { return mapper.tasks(b.getRepriceNo()).getFirst(); }
  static long version(MonthlyRepriceBatch b,CostRunTask t) {
    String no=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO lp_quote_cost_run_version(cost_run_no,oa_no,oa_form_item_id,product_code,pricing_month,result_period,status,total_cost,trial_finished_at,business_unit_type,reprice_no,algorithm_version,fee_policy_version,finance_material_cost,cu_material_adjustment,final_quote_amount,part_item_count,cost_item_count) VALUES(?,?,?,?,?,?,'SUCCESS',12.123456,NOW(),?,?,'MONTHLY_PRODUCT_COST_V1','fixture-policy',12.123456,0,12.123456,1,1)",no,t.getOaNo(),t.getOaFormItemId(),t.getProductCode(),b.getPricingMonth(),b.getPricingMonth(),b.getBusinessUnitType(),b.getRepriceNo());
    long id=jdbc.queryForObject("SELECT id FROM lp_quote_cost_run_version WHERE cost_run_no=?",Long.class,no);
    var amount=new BigDecimal("12.123456");
    var result=new MonthlyProductCost.Result("MONTHLY_PRODUCT_COST_V1","fixture-policy",null,null,List.of(),List.of(),Set.of(),List.of(),List.of(),amount,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,amount,BigDecimal.ZERO,amount,null);
    var snapshot=new MonthlyCostVersionService.Snapshot(1,b.getBusinessUnitType(),id,no,t.getOaNo(),t.getOaFormItemId(),t.getProductCode(),b.getPricingMonth(),result,List.of(),Map.of());
    jdbc.update("UPDATE lp_quote_cost_run_version SET jianji_snapshot_version=1,jianji_snapshot_json=? WHERE id=?",codec.encode(snapshot),id);
    jdbc.update("INSERT INTO lp_cost_run_part_item(oa_no,product_code,cost_run_version_id,part_code,qty,amount) VALUES(?,?,?,'00001',1,12.123456)",t.getOaNo(),t.getProductCode(),id);
    jdbc.update("INSERT INTO lp_cost_run_cost_item(oa_no,product_code,cost_run_version_id,cost_code,cost_name,amount) VALUES(?,?,?,'TOTAL','合计',12.123456)",t.getOaNo(),t.getProductCode(),id);
    return id;
  }
  static MonthlyRepriceBatch ready(MonthlyRepriceBatch b) {
    for(var t:mapper.tasks(b.getRepriceNo())) {
      int run=service.begin(b.getRepriceNo(),t.getId());
      service.complete(b.getRepriceNo(),t.getId(),run,version(b,t));
    }
    var checking=mapper.lock(b.getRepriceNo());
    return service.recordCheck(b.getRepriceNo(),checking.getResultRevision(),true,Map.of("contractCompleteness","PASSED"));
  }


  @Test void everyUnconfirmedStateBlocksAllRolesAndOnlyThisBusinessUnit() {
    var b=start(source("COMMERCIAL",901));
    for(String status:List.of("QUEUED","RUNNING","CHECKING","FAILED","WAIT_CONFIRM")) {
      jdbc.update("UPDATE lp_monthly_reprice_batch SET status=?,check_status='PASSED',checked_revision=result_revision,checked_at=NOW(),check_result_json='{}' WHERE reprice_no=?",status,b.getRepriceNo());
      for(String role:List.of("ROLE_ADMIN","ROLE_BU_DIRECTOR","price:monthly-reprice:operate","ingest:quote:cost-run:execute")) {
        auth("COMMERCIAL",role);
        assertThatThrownBy(()->control.ordinaryWrite("COMMERCIAL",()->"published"))
            .isInstanceOf(MonthlyQuotationControl.QuotationBlocked.class);
      }
      assertThat(control.ordinaryWrite("HOUSEHOLD",()->"allowed")).isEqualTo("allowed");
    }
    jdbc.update("UPDATE lp_monthly_reprice_batch SET status='CONFIRMED',confirmed_by='fixture-director',confirmed_at=NOW() WHERE reprice_no=?",b.getRepriceNo());
    assertThat(control.ordinaryWrite("COMMERCIAL",()->"allowed")).isEqualTo("allowed");
  }

  @Test void acceptanceWaitsForInFlightPublicationCommitAndBlocksLaterPublication() throws Exception {
    var src=source("COMMERCIAL",902);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    var pool=Executors.newFixedThreadPool(2);
    try {
      var write=pool.submit(()->tx.execute(status->{
        control.ordinaryWrite("COMMERCIAL",()->{entered.countDown();return true;});
        try { if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
        catch(InterruptedException ex){throw new RuntimeException(ex);}
        return "committed";
      }));
      assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
      var accepted=pool.submit(()->{auth("COMMERCIAL","ROLE_BU_DIRECTOR");
        return control.serialized("COMMERCIAL",()->start(src));});
      assertThatThrownBy(()->accepted.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
      release.countDown();assertThat(write.get(5,TimeUnit.SECONDS)).isEqualTo("committed");
      var batch=accepted.get(5,TimeUnit.SECONDS);
      assertThat(batch.getStatus()).isEqualTo("QUEUED");
      assertThatThrownBy(()->control.ordinaryWrite("COMMERCIAL",()->true)).isInstanceOf(MonthlyQuotationControl.QuotationBlocked.class);
    } finally {release.countDown();pool.shutdownNow();}
  }

  @Test void rejectedAcceptanceRollsBackTasksAndControlTogether() {
    var src=source("COMMERCIAL",903);
    assertThatThrownBy(()->control.serialized("COMMERCIAL",()->tx.execute(status->{
      start(src);throw new IllegalStateException("controlled rollback");
    }))).hasMessage("controlled rollback");
    assertThat(control.blocked("COMMERCIAL")).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_task",Integer.class)).isZero();
  }

  @Test void durableLeaseReclaimsExpiredRunAndFencesOldWorkerFailure() {
    var batch=start(source("COMMERCIAL",904));
    var a=dispatch.claim("worker-a");assertThat(a).isNotNull();
    assertThat(dispatch.claim("worker-b")).isNull();
    jdbc.update("UPDATE lp_cost_run_task SET lock_expire_time=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE id=?",a.getId());
    var b=dispatch.claim("worker-b");assertThat(b.getId()).isEqualTo(a.getId());
    assertThat(b.getExecutionNo()).isEqualTo(a.getExecutionNo()+1);
    dispatch.fail(a,"worker-a",new IllegalStateException("stale failure"));
    assertThat(mapper.readTask(batch.getRepriceNo(),a.getId()).getStatus()).isEqualTo("RUNNING");
    dispatch.fail(b,"worker-b",new IllegalStateException("缺资料"));
    assertThat(mapper.readBatch(batch.getRepriceNo()).getStatus()).isEqualTo("FAILED");
    assertThat(control.blocked("COMMERCIAL")).isTrue();
    assertThat(dispatch.claim("worker-c")).isNull();
    service.retry(batch.getRepriceNo());var retry=dispatch.claim("worker-c");
    assertThat(retry.getId()).isEqualTo(a.getId());assertThat(retry.getExecutionNo()).isEqualTo(b.getExecutionNo()+1);
    assertThat(mapper.results(batch.getRepriceNo())).hasSize(1);
  }

  @Test void cumulativeScopeUsesLatestSuccessfulVersionPerOaProductAcrossYears() {
    var before=dated(201,"2025-10-31 23:59:59","SUCCESS");
    var launch=dated(202,"2025-11-01 00:00:00","HISTORY");
    var old=dated(203,"2025-12-01 00:00:00","STALE");
    var latest=dated(203,"2026-01-31 23:59:59","SUCCESS");
    var failed=dated(203,"2026-01-31 23:59:59","FAILED");
    var never=dated(204,"2026-01-15 00:00:00","FAILED");
    var future=dated(205,"2026-02-01 00:00:00","SUCCESS");
    var household=source("HOUSEHOLD",206);
    var sameProductOtherOa=dated(207,"2026-01-15 00:00:00","CONFIRMED");
    jdbc.update("INSERT IGNORE INTO oa_form(id,oa_no,business_unit_type) VALUES(3,'MRP1130-OTHER','COMMERCIAL')");
    jdbc.update("UPDATE oa_form_item SET oa_form_id=3 WHERE id=207");
    jdbc.update("UPDATE lp_quote_cost_run_version SET oa_no='MRP1130-OTHER' WHERE id=?",sameProductOtherOa.sourceCostVersionId());
    jdbc.update("UPDATE oa_form SET calc_status='未核算'");
    jdbc.update("UPDATE oa_form_item SET calc_status='核算失败'");
    // Different current product states and old pricing labels do not erase completed-success evidence.
    jdbc.update("UPDATE lp_quote_cost_run_version SET pricing_month='2025-10' WHERE id=?",launch.sourceCostVersionId());
    var found=mapper.cumulativeSources("COMMERCIAL",LIVE.atDay(1).atStartOfDay(),MONTH.plusMonths(1).atDay(1).atStartOfDay());
    assertThat(found).extracting(QuoteCostRunVersion::getId).containsExactly(
        sameProductOtherOa.sourceCostVersionId(),launch.sourceCostVersionId(),latest.sourceCostVersionId());
    var batch=service.enqueue("COMMERCIAL",MONTH);
    assertThat(mapper.results(batch.getRepriceNo())).extracting(MonthlyRepriceResult::getSourceCostVersionId)
        .containsExactlyElementsOf(found.stream().map(QuoteCostRunVersion::getId).toList());
    assertThat(mapper.tasks(batch.getRepriceNo())).extracting(CostRunTask::getOaNo)
        .containsExactly("MRP1130-OTHER","MRP1130-TEST","MRP1130-TEST");
    assertThat(mapper.tasks(batch.getRepriceNo())).extracting(CostRunTask::getCalcObjectKey).doesNotHaveDuplicates();
  }

  @Test void savedCumulativeTargetsDoNotDriftOnDuplicateOrFailedRetry() {
    var original=dated(210,"2025-12-10 00:00:00","SUCCESS");
    var batch=service.enqueue("COMMERCIAL",MONTH); var task=task(batch);
    service.fail(batch.getRepriceNo(),task.getId(),service.begin(batch.getRepriceNo(),task.getId()),"BOM待确认");
    dated(210,"2026-01-20 00:00:00","SUCCESS"); dated(211,"2026-01-25 00:00:00","SUCCESS");
    assertThat(service.enqueue("COMMERCIAL",MONTH).getRepriceNo()).isEqualTo(batch.getRepriceNo());
    service.retry(batch.getRepriceNo());
    assertThat(mapper.tasks(batch.getRepriceNo())).hasSize(1);
    assertThat(mapper.results(batch.getRepriceNo()).getFirst().getSourceCostVersionId()).isEqualTo(original.sourceCostVersionId());
    assertThat(task(batch).getId()).isEqualTo(task.getId());
  }

  @Test void missingLaunchConfigurationAndEmptyScopeNeverRegisterABatch() {
    properties.setGoLiveMonths(Map.of());
    assertThatThrownBy(()->service.enqueue("COMMERCIAL",MONTH)).hasMessageContaining("实际上线月份");
    properties.setGoLiveMonths(Map.of("COMMERCIAL","2025-13"));
    assertThatThrownBy(()->service.enqueue("COMMERCIAL",MONTH)).hasMessageContaining("无效");
    properties.setGoLiveMonths(Map.of("COMMERCIAL",LIVE.toString()));
    assertThatThrownBy(()->service.enqueue("COMMERCIAL",LIVE.minusMonths(1))).hasMessageContaining("早于");
    assertThatThrownBy(()->service.enqueue("COMMERCIAL",MONTH)).hasMessageContaining("没有");
    assertThat(service.blocksQuotation("COMMERCIAL")).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_task",Integer.class)).isZero();
  }

  @Test void concurrentCumulativeExpansionPersistsOneCompleteSet() throws Exception {
    dated(220,"2025-12-10 00:00:00","SUCCESS");dated(221,"2026-01-10 00:00:00","SUCCESS");
    var barrier=new CyclicBarrier(4);
    try(var pool=Executors.newFixedThreadPool(4)) {
      var jobs=new ArrayList<Future<String>>();
      for(int i=0;i<4;i++) jobs.add(pool.submit(()->{
        auth("COMMERCIAL","ROLE_BU_DIRECTOR");
        try {barrier.await(10,TimeUnit.SECONDS);return service.enqueue("COMMERCIAL",MONTH).getRepriceNo();}
        finally {SecurityContextHolder.clearContext();}
      }));
      var numbers=new HashSet<String>();for(var job:jobs) numbers.add(job.get(20,TimeUnit.SECONDS));
      assertThat(numbers).hasSize(1);
      assertThat(mapper.tasks(numbers.iterator().next())).hasSize(2);
    }
  }

  private static Source dated(long item,String finished,String status) {
    var value=source("COMMERCIAL",item);
    jdbc.update("UPDATE lp_quote_cost_run_version SET trial_finished_at=?,status=? WHERE id=?",finished,status,value.sourceCostVersionId());
    return value;
  }

  @Test void migrationPreservesEveryOldFieldAndExistingHistoryMappers() {
    for(var entry:Map.of("lp_monthly_reprice_batch",oldBatch,"lp_monthly_reprice_result",oldResult,"lp_quote_cost_run_version",oldVersion).entrySet()) {
      var after=jdbc.queryForMap("SELECT * FROM "+entry.getKey()+" WHERE id=1");
      entry.getValue().forEach((key,value)->assertThat(after.get(key)).as(entry.getKey()+"."+key).isEqualTo(value));
    }
    assertThat(history.selectById(1).getRemark()).isEqualTo("原备注");
    assertThat(history.selectById(1).getContractVersion()).isNull();
    assertThat(historicalResults.selectById(1).getTotalCost()).isEqualByComparingTo("12.123456");
  }
  @Test void eightConcurrentSubmissionsPersistOneBatchOneQueueAndOneTask() throws Exception {
    var source=source("COMMERCIAL",10);var barrier=new CyclicBarrier(8);
    try(var pool=Executors.newFixedThreadPool(8)) {
      var futures=new ArrayList<Future<String>>();
      for(int i=0;i<8;i++) futures.add(pool.submit(()->{auth("COMMERCIAL","ROLE_BU_DIRECTOR");try{barrier.await(10,TimeUnit.SECONDS);return start(source).getRepriceNo();}finally{SecurityContextHolder.clearContext();}}));
      var identities=new HashSet<String>();for(var f:futures) identities.add(f.get(20,TimeUnit.SECONDS));
      assertThat(identities).hasSize(1);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_task",Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_batch",Integer.class)).isEqualTo(1);
  }
  @Test void failureRetainsControlAndCannotStartAnotherMonth() {
    var source=source("COMMERCIAL",11);var b=start(source);var t=task(b);
    service.fail(b.getRepriceNo(),t.getId(),service.begin(b.getRepriceNo(),t.getId()),"missing data");
    assertThat(service.blocksQuotation("COMMERCIAL")).isTrue();
    assertThat(jdbc.queryForObject("SELECT active_lock_key FROM lp_monthly_reprice_batch WHERE reprice_no=?",String.class,b.getRepriceNo())).isEqualTo("COMMERCIAL");
    assertThat(start(source).getRepriceNo()).isEqualTo(b.getRepriceNo());
    assertThatThrownBy(()->service.enqueue("COMMERCIAL",MONTH.plusMonths(1),LIVE,List.of(source))).hasMessageContaining("未确认");
  }
  @Test void twoMonthsHaveDifferentIdentityOnlyAfterValidConfirmation() {
    var source=source("COMMERCIAL",12);var b=ready(start(source));
    service.confirm(b.getRepriceNo(),b.getCheckedRevision());
    assertThat(service.blocksQuotation("COMMERCIAL")).isFalse();
    assertThat(start(source).getRepriceNo()).isEqualTo(b.getRepriceNo());
    var next=service.enqueue("COMMERCIAL",MONTH.plusMonths(1),LIVE,List.of(source));
    assertThat(next.getRepriceNo()).isNotEqualTo(b.getRepriceNo());
    assertThat(task(next).getCalcObjectKey()).isEqualTo(task(b).getCalcObjectKey());
  }
  @Test void distinctUnitsAndActualRowsWithSameProductAreNotMerged() {
    var b=start(source("COMMERCIAL",20),source("COMMERCIAL",21));
    assertThat(mapper.tasks(b.getRepriceNo())).hasSize(2);
    auth("HOUSEHOLD","ROLE_BU_DIRECTOR");
    var other=service.enqueue("HOUSEHOLD",MONTH,LIVE,List.of(source("HOUSEHOLD",120)));
    assertThat(other.getRepriceNo()).isNotEqualTo(b.getRepriceNo());
  }
  @Test void oldVersionOrSourceUnitRollsBackAllRegistration() {
    var first=source("COMMERCIAL",30);var later=source("COMMERCIAL",30);
    assertThatThrownBy(()->start(first)).hasMessageContaining("最近成功");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_batch",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_batch WHERE contract_version=1",Integer.class)).isZero();
    assertThatThrownBy(()->start(source("HOUSEHOLD",31))).hasMessageContaining("跨业务单元");
    assertThat(start(later).getTotalCount()).isEqualTo(1);
  }
  @Test void historicalSuccessfulSourceRemainsEligibleAfterStatusBecomesStale() {
    var first=source("COMMERCIAL",32);jdbc.update("UPDATE lp_quote_cost_run_version SET status='STALE' WHERE id=?",first.sourceCostVersionId());
    assertThat(start(first).getTotalCount()).isEqualTo(1);
  }
  @Test void taskInsertFailureRollsBackBatchQueueAndPriorObject() {
    var a=source("COMMERCIAL",40);var b=source("COMMERCIAL",41);
    jdbc.execute("CREATE TRIGGER mrp_fail_task BEFORE INSERT ON lp_cost_run_task FOR EACH ROW BEGIN IF NEW.oa_form_item_id=41 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MRP_TASK_ROLLBACK'; END IF; END");
    try { assertThatThrownBy(()->start(a,b)).hasStackTraceContaining("MRP_TASK_ROLLBACK"); }
    finally { jdbc.execute("DROP TRIGGER mrp_fail_task"); }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_task",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_batch",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_result WHERE contract_version=1",Integer.class)).isZero();
    assertThat(service.blocksQuotation("COMMERCIAL")).isFalse();
  }
  @Test void resultAndTaskPublicationRollBackTogetherOnLateFailure() {
    var b=start(source("COMMERCIAL",50));var t=task(b);int run=service.begin(b.getRepriceNo(),t.getId());long id=version(b,t);
    jdbc.execute("CREATE TRIGGER mrp_fail_publish BEFORE UPDATE ON lp_cost_run_task FOR EACH ROW BEGIN IF NEW.status='SUCCESS' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MRP_LINK_ROLLBACK'; END IF; END");
    try { assertThatThrownBy(()->service.complete(b.getRepriceNo(),t.getId(),run,id)).hasStackTraceContaining("MRP_LINK_ROLLBACK"); }
    finally { jdbc.execute("DROP TRIGGER mrp_fail_publish"); }
    assertThat(mapper.results(b.getRepriceNo()).getFirst().getCostRunVersionId()).isNull();
    assertThat(task(b).getStatus()).isEqualTo("RUNNING");
    service.complete(b.getRepriceNo(),t.getId(),run,id);
    assertThat(task(b).getCostRunVersionId()).isEqualTo(id);
  }
  @Test void outerCalculationTransactionAlsoRollsBackVersionAndLinks() {
    var b=start(source("COMMERCIAL",51));var t=task(b);int run=service.begin(b.getRepriceNo(),t.getId());
    assertThatThrownBy(()->tx.executeWithoutResult(status->{long id=version(b,t);service.complete(b.getRepriceNo(),t.getId(),run,id);throw new IllegalStateException("calculation failed");})).hasMessage("calculation failed");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE reprice_no=?",Integer.class,b.getRepriceNo())).isZero();
    assertThat(task(b).getStatus()).isEqualTo("RUNNING");
    assertThat(mapper.results(b.getRepriceNo()).getFirst().getCostRunVersionId()).isNull();
  }
  @Test void partialOrWrongOwnershipVersionCannotBeAssociated() {
    var b=start(source("COMMERCIAL",60));var t=task(b);int run=service.begin(b.getRepriceNo(),t.getId());long id=version(b,t);
    for(String mutation:List.of("status='FAILED'","reprice_no=NULL","business_unit_type='HOUSEHOLD'","pricing_month='2026-02'","oa_form_item_id=999","part_item_count=2","total_cost=99")) {
      tx.executeWithoutResult(status->{jdbc.update("UPDATE lp_quote_cost_run_version SET "+mutation+" WHERE id=?",id);assertThatThrownBy(()->service.complete(b.getRepriceNo(),t.getId(),run,id)).isInstanceOf(RuntimeException.class);status.setRollbackOnly();});
      assertThat(mapper.results(b.getRepriceNo()).getFirst().getCostRunVersionId()).isNull();
    }
    service.complete(b.getRepriceNo(),t.getId(),run,id);
  }
  @Test void duplicateCompletionAndConcurrentConfirmationAreIdempotent() throws Exception {
    var b=start(source("COMMERCIAL",70));var t=task(b);int run=service.begin(b.getRepriceNo(),t.getId());long id=version(b,t);
    service.complete(b.getRepriceNo(),t.getId(),run,id);long revision=mapper.lock(b.getRepriceNo()).getResultRevision();
    service.complete(b.getRepriceNo(),t.getId(),run,id);
    assertThat(mapper.lock(b.getRepriceNo()).getResultRevision()).isEqualTo(revision);
    var ready=service.recordCheck(b.getRepriceNo(),revision,true,Map.of("complete",true));
    try(var pool=Executors.newFixedThreadPool(2)) {
      var a=pool.submit(()->{auth("COMMERCIAL","ROLE_BU_DIRECTOR");return service.confirm(b.getRepriceNo(),ready.getCheckedRevision());});
      var c=pool.submit(()->{auth("COMMERCIAL","ROLE_BU_DIRECTOR");return service.confirm(b.getRepriceNo(),ready.getCheckedRevision());});
      assertThat(a.get(10,TimeUnit.SECONDS).getConfirmedAt()).isEqualTo(c.get(10,TimeUnit.SECONDS).getConfirmedAt());
    }
    assertThatThrownBy(()->service.confirm(b.getRepriceNo(),revision-1)).hasMessageContaining("当前检查");
  }
  @Test void failedRetryPreservesSuccessfulVersionAndFencesLateOldExecution() {
    var b=start(source("COMMERCIAL",80),source("COMMERCIAL",81));var tasks=mapper.tasks(b.getRepriceNo());
    var good=tasks.get(0);var bad=tasks.get(1);int goodRun=service.begin(b.getRepriceNo(),good.getId());
    long goodVersion=version(b,good);service.complete(b.getRepriceNo(),good.getId(),goodRun,goodVersion);
    int oldRun=service.begin(b.getRepriceNo(),bad.getId());service.fail(b.getRepriceNo(),bad.getId(),oldRun,"missing");
    service.retry(b.getRepriceNo());int newRun=service.begin(b.getRepriceNo(),bad.getId());long id=version(b,bad);
    assertThat(newRun).isEqualTo(oldRun+1);
    assertThatThrownBy(()->service.complete(b.getRepriceNo(),bad.getId(),oldRun,id)).hasMessageContaining("轮次");
    service.complete(b.getRepriceNo(),bad.getId(),newRun,id);
    assertThat(mapper.tasks(b.getRepriceNo()).getFirst().getCostRunVersionId()).isEqualTo(goodVersion);
  }
  @Test void staleCheckOrChangedSavedVersionCannotBeConfirmed() {
    var b=ready(start(source("COMMERCIAL",90)));
    assertThatThrownBy(()->service.recordCheck(b.getRepriceNo(),b.getResultRevision()-1,true,Map.of("stale",true))).hasMessageContaining("已变化");
    jdbc.update("UPDATE lp_quote_cost_run_version SET final_quote_amount=99 WHERE id=?",task(b).getCostRunVersionId());
    assertThatThrownBy(()->service.confirm(b.getRepriceNo(),b.getCheckedRevision())).hasMessageContaining("Stored total2");
    assertThat(service.blocksQuotation("COMMERCIAL")).isTrue();
  }
  @Test void failedCheckCanBeRetriedWithoutRecalculatingSuccessfulTasks() {
    var b=start(source("COMMERCIAL",91));var t=task(b);int run=service.begin(b.getRepriceNo(),t.getId());long id=version(b,t);service.complete(b.getRepriceNo(),t.getId(),run,id);
    var checking=mapper.lock(b.getRepriceNo());service.recordCheck(b.getRepriceNo(),checking.getResultRevision(),false,Map.of("issue","business check"));
    var retry=service.retry(b.getRepriceNo());assertThat(retry.getStatus()).isEqualTo("CHECKING");
    assertThat(task(b).getCostRunVersionId()).isEqualTo(id);
    assertThat(retry.getCheckedRevision()).isNull();
  }
  @Test void genericPermissionsAndOtherUnitCannotStartOrConfirm() {
    var source=source("COMMERCIAL",100);
    for(String role:List.of("*:*:*","price:monthly-reprice:operate","ROLE_BU_STAFF")) {
      auth("COMMERCIAL",role);assertThatThrownBy(()->start(source)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    auth("COMMERCIAL","ROLE_BU_DIRECTOR");var b=ready(start(source));
    auth("HOUSEHOLD","ROLE_BU_DIRECTOR");assertThatThrownBy(()->service.confirm(b.getRepriceNo(),b.getCheckedRevision())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    auth("COMMERCIAL","*:*:*");assertThatThrownBy(()->service.confirm(b.getRepriceNo(),b.getCheckedRevision())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"ROLE_ADMIN","ROLE_admin","ROLE_BU_DIRECTOR","ROLE_bu_director"})
  void adminAndDirectorCanStartRetryAndConfirmWithinTheirCurrentBusinessUnit(String role) {
    var input=source("COMMERCIAL",101);
    auth("COMMERCIAL",role);var batch=start(input);var task=task(batch);
    assertThat(service.blocksQuotation("COMMERCIAL")).isTrue();
    int execution=service.begin(batch.getRepriceNo(),task.getId());
    service.fail(batch.getRepriceNo(),task.getId(),execution,"controlled missing input");
    var checked=ready(service.retry(batch.getRepriceNo()));
    auth("HOUSEHOLD",role);
    assertThatThrownBy(()->service.retry(checked.getRepriceNo())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(()->service.confirm(checked.getRepriceNo(),checked.getCheckedRevision())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(service.blocksQuotation("COMMERCIAL")).isTrue();
    auth("COMMERCIAL",role);
    assertThat(service.confirm(checked.getRepriceNo(),checked.getCheckedRevision()).getStatus()).isEqualTo("CONFIRMED");
    assertThat(service.blocksQuotation("COMMERCIAL")).isFalse();
  }
  @Test void databaseRejectsIllegalTerminalStateAndUnbackedSuccess() {
    var b=start(source("COMMERCIAL",110));
    for(String state:List.of("CANCELLED","CONFIRMED","WAIT_CONFIRM"))
      assertThatThrownBy(()->jdbc.update("UPDATE lp_monthly_reprice_batch SET status=? WHERE reprice_no=?",state,b.getRepriceNo())).hasStackTraceContaining("ck_reprice_1130_batch");
    assertThatThrownBy(()->jdbc.update("UPDATE lp_monthly_reprice_result SET calc_status='SUCCESS' WHERE reprice_no=?",b.getRepriceNo())).hasStackTraceContaining("ck_reprice_1130_result");
  }
  @Test void existingRatioAndGenericLogNeedNoManualEffectiveDatesOrNewTable() {
    jdbc.update("INSERT INTO lp_supplier_supply_ratio(material_code,material_name,spec_model,supplier_name,supplier_code,supply_ratio,source_batch_no) VALUES('00001','fixture','spec','supplier','000S',0,?)",UUID.randomUUID().toString());
    assertThat(jdbc.queryForMap("SELECT effective_from,effective_to FROM lp_supplier_supply_ratio ORDER BY id DESC LIMIT 1").values()).containsOnlyNulls();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='lp_business_change_log'",Integer.class)).isEqualTo(1);
    String key="MRP1130-RATIO-"+UUID.randomUUID();
    tx.executeWithoutResult(status->{
      jdbc.update("INSERT INTO lp_business_change_log(biz_domain,biz_type,field_name,before_value,after_value,idempotency_key) VALUES('SUPPLIER_SUPPLY_RATIO','SUPPLIER_RELATION','supply_ratio',NULL,'0',?)",key);
      var log=jdbc.queryForMap("SELECT before_value,after_value FROM lp_business_change_log WHERE idempotency_key=?",key);
      assertThat(log.get("before_value")).isNull();assertThat(log.get("after_value")).isEqualTo("0");
      status.setRollbackOnly();
    });
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_business_change_log WHERE idempotency_key=?",Integer.class,key)).isZero();
  }

  @Test void sourceMustBelongToTheActualOaProductRow() {
    var s=source("COMMERCIAL",130);
    jdbc.update("UPDATE oa_form_item SET business_unit_type='HOUSEHOLD' WHERE id=130");
    try { assertThatThrownBy(()->start(s)).hasMessageContaining("最近成功"); }
    finally { jdbc.update("UPDATE oa_form_item SET business_unit_type='COMMERCIAL' WHERE id=130"); }
    assertThat(service.blocksQuotation("COMMERCIAL")).isFalse();
  }
  @Test void versionForeignKeyAndMonthFormatAreEnforcedByRealMysql() {
    var b=start(source("COMMERCIAL",131));
    assertThatThrownBy(()->jdbc.update("UPDATE lp_monthly_reprice_result SET calc_status='SUCCESS',total_cost=1,cost_run_version_id=999999999 WHERE reprice_no=?",b.getRepriceNo())).hasStackTraceContaining("fk_reprice_1130_result_version");
    assertThatThrownBy(()->jdbc.update("UPDATE lp_monthly_reprice_batch SET pricing_month='2026-13' WHERE reprice_no=?",b.getRepriceNo())).hasStackTraceContaining("ck_reprice_1130_batch");
  }
  @Test void changedFirstSourceIdentityCannotPassConfirmation() {
    var b=ready(start(source("COMMERCIAL",132)));
    jdbc.update("UPDATE lp_monthly_reprice_result SET source_cost_version_id=1 WHERE reprice_no=?",b.getRepriceNo());
    assertThatThrownBy(()->service.confirm(b.getRepriceNo(),b.getCheckedRevision())).hasMessageContaining("不一致");
    assertThat(service.blocksQuotation("COMMERCIAL")).isTrue();
  }
  @Test void pendingOrRunningBatchCannotBeConfirmedOrCheckedAndMissingTaskCannotWrite() {
    var b=start(source("COMMERCIAL",133));
    assertThatThrownBy(()->service.confirm(b.getRepriceNo(),0)).hasMessageContaining("当前检查");
    assertThatThrownBy(()->service.recordCheck(b.getRepriceNo(),0,true,Map.of("complete",true))).hasMessageContaining("已变化");
    assertThatThrownBy(()->service.begin(b.getRepriceNo(),9999999)).hasMessageContaining("不属于");
    var t=task(b);service.begin(b.getRepriceNo(),t.getId());
    assertThatThrownBy(()->service.begin(b.getRepriceNo(),t.getId())).hasMessageContaining("领取");
    assertThat(service.blocksQuotation("COMMERCIAL")).isTrue();
  }
  @Test void eightWorkersClaimEveryTaskOnceAcrossIndependentTransactions() throws Exception {
    var sources=new ArrayList<Source>();for(int n=0;n<32;n++)sources.add(source("COMMERCIAL",2000+n));
    var batch=start(sources.toArray(Source[]::new));var barrier=new CyclicBarrier(8);
    var claimed=ConcurrentHashMap.<Long>newKeySet();var duplicate=new java.util.concurrent.atomic.AtomicBoolean();
    try(var pool=Executors.newFixedThreadPool(8)) {
      var jobs=new ArrayList<Future<?>>();
      for(int n=0;n<8;n++){String worker="parallel-worker-"+n;jobs.add(pool.submit(()->{
        barrier.await(10,TimeUnit.SECONDS);
        for(int attempt=0;attempt<64;attempt++){var task=dispatch.claim(worker);if(task==null)break;if(!claimed.add(task.getId()))duplicate.set(true);}
        return null;
      }));}
      for(var job:jobs)job.get(30,TimeUnit.SECONDS);
    }
    assertThat(duplicate).isFalse();assertThat(claimed).hasSize(32);
    assertThat(mapper.tasks(batch.getRepriceNo())).allSatisfy(t->{assertThat(t.getStatus()).isEqualTo("RUNNING");assertThat(t.getWorkerId()).startsWith("parallel-worker-");});
    assertThat(dispatch.claim("duplicate-delivery")).isNull();assertThat(control.blocked("COMMERCIAL")).isTrue();
  }

}
