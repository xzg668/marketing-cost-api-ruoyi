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
      .withConfigurationOverride("monthly-pricing/mysql")
      .withLabel("sales-cost.task","monthly-reprice-reuse-20261105-r02")
      .withLabel("sales-cost.run",UUID.randomUUID().toString())
      .withReuse(Boolean.getBoolean("mpp.mysql.retain"));
  static JdbcTemplate jdbc;
  static MonthlyRepriceContractMapper mapper;
  static MonthlyRepriceReuseMapper reuseMapper;
  static MonthlyRepriceCheckMapper checkQuery;
  static MonthlyRepriceUncalculatedQuery uncalculated;
  static MonthlyRepriceBatchMapper history;
  static MonthlyRepriceResultMapper historicalResults;
  static MonthlyRepriceContractService service;
  static MonthlyRepricePublicationService publications;
  static MonthlyRepricePublicationMapper publicationQuery;
  static MonthlyRepriceCheckService fixtureChecks;
  static TransactionTemplate tx;
  static MonthlyQuotationControl control;
  static MonthlyRepriceWorkflowMapper workflow;
  static MonthlyRepriceDispatchService dispatch;
  static MonthlyRepriceCapacity capacity;
  static MonthlyRepriceAdmission admission;
  static ConfirmedQuotePricingPeriod pricingPeriods;
  static com.sanhua.marketingcost.config.MonthlyRepriceProperties properties;
  static MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
  static Map<String,Object> oldBatch, oldResult, oldVersion;
  static final YearMonth MONTH = YearMonth.of(2026,1), LIVE = YearMonth.of(2025,11);

  static final ThreadLocal<ProgressReads> progressReads=new ThreadLocal<>();
  static class ProgressReads {
    int returnedTaskRows, taskQueries, taskAggregateStatements;
  }
  @org.apache.ibatis.plugin.Intercepts({
      @org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="query",
          args={org.apache.ibatis.mapping.MappedStatement.class,Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class}),
      @org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="update",
          args={org.apache.ibatis.mapping.MappedStatement.class,Object.class})})
  static class ProgressMeter implements org.apache.ibatis.plugin.Interceptor {
    @Override public Object intercept(org.apache.ibatis.plugin.Invocation call) throws Throwable {
      var meter=progressReads.get();
      if(meter==null)return call.proceed();
      var statement=(org.apache.ibatis.mapping.MappedStatement)call.getArgs()[0];
      String sql=statement.getBoundSql(call.getArgs()[1]).getSql().toLowerCase(java.util.Locale.ROOT);
      if(sql.contains("lp_cost_run_task") && (sql.contains("count(") || sql.contains("sum(")))
        meter.taskAggregateStatements++;
      Object result=call.proceed();
      if(result instanceof List<?> rows && !rows.isEmpty() && rows.getFirst() instanceof CostRunTask) {
        meter.taskQueries++;meter.returnedTaskRows+=rows.size();
      }
      return result;
    }
  }

  @BeforeAll static void database() throws Exception {
    MYSQL.start();
    // Real JDBC affected-row semantics also expose idempotent diagnostics updates returning zero.
    var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl()+"?useAffectedRows=true",MYSQL.getUsername(),MYSQL.getPassword());
    jdbc = new JdbcTemplate(ds);
    assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    jdbc.execute("CREATE TABLE oa_form(id BIGINT PRIMARY KEY,oa_no VARCHAR(64),business_unit_type VARCHAR(32),calc_status VARCHAR(32),customer VARCHAR(255))");
    jdbc.execute("CREATE TABLE oa_form_item(id BIGINT PRIMARY KEY,oa_form_id BIGINT,business_unit_type VARCHAR(32),calc_status VARCHAR(32),material_no VARCHAR(64),customer_code VARCHAR(64),package_method VARCHAR(64))");
    jdbc.update("INSERT INTO oa_form(id,oa_no,business_unit_type) VALUES(1,'MRP1130-TEST','COMMERCIAL'),(2,'MRP1130-TEST','HOUSEHOLD')");
    jdbc.execute("CREATE TABLE lp_oa_form_item_extra_field(oa_form_item_id BIGINT,field_code VARCHAR(64),field_value VARCHAR(64))");
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
    new ResourceDatabasePopulator(new ClassPathResource("db/V359__monthly_price_folder_import.sql")).execute(ds);
    new ResourceDatabasePopulator(new ClassPathResource("db/V361__monthly_reprice_reuse_contract.sql")).execute(ds);
    new ResourceDatabasePopulator(new ClassPathResource("db/V363__monthly_reprice_partial_publication.sql")).execute(ds);
    var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    var pagination = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
    pagination.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(
        com.baomidou.mybatisplus.annotation.DbType.MYSQL));
    config.addInterceptor(pagination);
    config.addInterceptor(new ProgressMeter());
    for (var type : List.of(MonthlyRepriceReuseMapper.class,MonthlyRepricePublicationMapper.class,MonthlyRepriceUncalculatedMapper.class,MonthlyRepriceCheckMapper.class,MonthlyRepriceScopeMapper.class,MonthlyRepriceContractMapper.class,MonthlyRepriceBatchMapper.class,MonthlyRepriceResultMapper.class,MonthlyRepriceWorkflowMapper.class,QuotePricingPeriodMapper.class,MonthlyPriceImportMapper.class)) config.addMapper(type);
    var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
    factory.setConfiguration(config); factory.setDataSource(ds);
    var sql = new SqlSessionTemplate(factory.getObject());
    mapper=sql.getMapper(MonthlyRepriceContractMapper.class);
    reuseMapper=sql.getMapper(MonthlyRepriceReuseMapper.class);
    checkQuery=sql.getMapper(MonthlyRepriceCheckMapper.class);
    history=sql.getMapper(MonthlyRepriceBatchMapper.class); historicalResults=sql.getMapper(MonthlyRepriceResultMapper.class);
    var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
    properties=new com.sanhua.marketingcost.config.MonthlyRepriceProperties();
    pricingPeriods=new ConfirmedQuotePricingPeriod(sql.getMapper(QuotePricingPeriodMapper.class),properties);
    service=new MonthlyRepriceContractService(mapper,manager,new ObjectMapper(),
        new com.sanhua.marketingcost.service.costing.MonthlyCostSnapshotDocuments(codec,
            org.mockito.Mockito.mock(com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.class)),properties,
        org.mockito.Mockito.mock(com.sanhua.marketingcost.service.pricing.inquiry.InquiryPriceRefreshService.class,
            org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE)),
        sql.getMapper(MonthlyPriceImportMapper.class),
        new MonthlyRepriceScopeRegistration(sql.getMapper(MonthlyRepriceScopeMapper.class),mapper,
            new com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver(null,null),new ObjectMapper()),
        sql.getMapper(MonthlyRepricePublicationMapper.class));
    control=new MonthlyQuotationControl(ds);workflow=sql.getMapper(MonthlyRepriceWorkflowMapper.class);
    // Synthetic contract costs do not contain a real BOM. Real full checking is exercised in the costing-chain suite.
    fixtureChecks=org.mockito.Mockito.mock(MonthlyRepriceCheckService.class);
    org.mockito.Mockito.when(fixtureChecks.verify(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
      MonthlyRepriceBatch batch=call.getArgument(0);
      assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      if(checkQuery.inconsistentSources(batch.getId())!=0)
        throw new IllegalStateException("fixture fixed sources incomplete");
      var documents=new com.sanhua.marketingcost.service.costing.MonthlyCostSnapshotDocuments(codec,
          org.mockito.Mockito.mock(com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.class));
      for(var task:mapper.tasks(batch.getRepriceNo())) if("SUCCESS".equals(task.getStatus())) {
        var version=mapper.version(task.getCostRunVersionId());
        MonthlyCostVersionService.validateSavedSnapshot(version,documents.read(batch.getBusinessUnitType(),mapper.snapshot(version.getId())));
      }
      return fixtureReport(batch);
    });
    publicationQuery=sql.getMapper(MonthlyRepricePublicationMapper.class);
    publications=new MonthlyRepricePublicationService(mapper,publicationQuery,
        fixtureChecks,control,new ObjectMapper(),manager);
    uncalculated=new MonthlyRepriceUncalculatedQuery(sql.getMapper(MonthlyRepriceUncalculatedMapper.class),
        org.mockito.Mockito.mock(com.sanhua.marketingcost.security.PermissionService.class),new ObjectMapper());
    jdbc.execute("CREATE TABLE lp_monthly_reprice_audit_log(id BIGINT PRIMARY KEY AUTO_INCREMENT,reprice_no VARCHAR(64),pricing_month VARCHAR(7),business_unit_type VARCHAR(32),operation_type VARCHAR(64),operation_name VARCHAR(128),operator_id VARCHAR(64),operation_time DATETIME DEFAULT CURRENT_TIMESTAMP,target_type VARCHAR(64),target_key VARCHAR(255),before_json JSON,after_json JSON,change_summary VARCHAR(1000))");
    capacity=new MonthlyRepriceCapacity();admission=new MonthlyRepriceAdmission(ds,manager,capacity);
    dispatch=new MonthlyRepriceDispatchService(mapper,workflow,service,
        org.mockito.Mockito.mock(MonthlyRepriceCheckCoordinator.class),manager,admission);
  }
  @AfterAll static void stop() { if (!Boolean.getBoolean("mpp.mysql.retain")) MYSQL.stop(); }
  @BeforeEach void clean() {
    capacity.setMaxActive(4);capacity.setClaimsPerSecond(100);capacity.setReservedConnections(32);
    jdbc.update("DELETE FROM lp_monthly_price_import");
    jdbc.update("DELETE FROM lp_monthly_reprice_audit_log");
    jdbc.update("DELETE FROM lp_monthly_reprice_source");
    for (String table : List.of("lp_cost_run_part_item","lp_cost_run_cost_item","lp_cost_run_task","lp_cost_run_batch")) jdbc.update("DELETE FROM "+table);
    for (String table : List.of("lp_monthly_reprice_result","lp_monthly_reprice_batch","lp_quote_cost_run_version")) jdbc.update("DELETE FROM "+table+" WHERE id<>1");
    jdbc.update("DELETE FROM lp_oa_form_item_extra_field");
    jdbc.update("DELETE FROM oa_form_item");
    jdbc.update("DELETE FROM oa_form WHERE id NOT IN (1,2)");
    jdbc.update("UPDATE oa_form SET customer='fixture-customer'");
    properties.setGoLiveMonths(Map.of("COMMERCIAL",LIVE.toString(),"HOUSEHOLD",LIVE.toString()));
    auth("COMMERCIAL","ROLE_BU_DIRECTOR");
  }
  @AfterEach void logout() { SecurityContextHolder.clearContext();progressReads.remove(); }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"contract,READ_COMMITTED,true","reuse,READ_COMMITTED,true",
      "contract,REPEATABLE_READ,true","reuse,REPEATABLE_READ,true",
      "contract,READ_COMMITTED,false","reuse,READ_COMMITTED,false",
      "contract,REPEATABLE_READ,false","reuse,REPEATABLE_READ,false"})
  void batchReadersAndDispatcherKeepOneIndexLockOrder(String reader,String isolation,boolean singleRow) throws Exception {
    for(int index=0;index<20;index++)
      customerSource(6810+index,"lock-customer-"+index,"LOCK-P","2026-01-01 00:00:00");
    var batch=service.enqueue("COMMERCIAL",MONTH);
    // The scale fixture has one batch row; retain the original legacy fixture in its own schema.
    var fixture=batchLockFixture(batch,singleRow);
    var jdbc=fixture.jdbc();var mapper=fixture.contracts();var reuseMapper=fixture.reuse();
    var workflow=fixture.workflow();var tx=fixture.transaction();
    Class<?> mapperType=reader.equals("reuse")?MonthlyRepriceReuseMapper.class:MonthlyRepriceContractMapper.class;
    String methodName=reader.equals("reuse")?"lockBatch":"lock";
    var query=Arrays.stream(mapperType.getMethods()).filter(m->m.getName().equals(methodName)).findFirst().orElseThrow()
        .getAnnotation(org.apache.ibatis.annotations.Select.class).value();
    var arguments=new ArrayList<Object>();
    var values=Map.of("no",batch.getRepriceNo(),"unit","COMMERCIAL","month",MONTH.toString());
    String sql=java.util.regex.Pattern.compile("#\\{(\\w+)\\}").matcher(String.join(" ",query))
        .replaceAll(match->{arguments.add(values.get(match.group(1)));return "?";});
    var outerPlan=jdbc.queryForList("EXPLAIN FORMAT=TRADITIONAL "+sql,arguments.toArray()).stream()
        .filter(row->((Number)row.get("id")).intValue()==1).findFirst().orElseThrow();
    assertThat(outerPlan.get("key")).as("writers must use the same unique index as the dispatcher").isEqualTo("uk_monthly_reprice_no");
    assertThat(((Number)outerPlan.get("rows")).longValue()).as("no full batch-table locking scan").isLessThanOrEqualTo(1);
    var connection=new java.util.concurrent.atomic.AtomicLong();
    var waitingLocks=new ArrayList<Map<String,Object>>();
    var future=new java.util.concurrent.atomic.AtomicReference<Future<String>>();
    var dispatchTx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    dispatchTx.setIsolationLevelName("ISOLATION_"+isolation);
    try(var pool=Executors.newSingleThreadExecutor()) {
      dispatchTx.executeWithoutResult(status->{
        // Exercise the actual dispatcher and task write before the competing batch writer.
        var leased=workflow.leaseBatch();
        assertThat(leased.getId()).isEqualTo(batch.getId());
        assertThat(jdbc.queryForList("""
            SELECT DISTINCT l.INDEX_NAME FROM performance_schema.data_locks l
            JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID
            WHERE t.PROCESSLIST_ID=CONNECTION_ID() AND l.OBJECT_SCHEMA=DATABASE()
              AND l.OBJECT_NAME='lp_monthly_reprice_batch' AND l.LOCK_TYPE='RECORD'
            """,String.class)).containsOnly("PRIMARY","uk_monthly_reprice_no");
        var candidate=workflow.candidate(batch.getRepriceNo());
        assertThat(workflow.lease(candidate.getId(),"lock-order-test","lock-order-policy")).isEqualTo(1);
        future.set(pool.submit(()->tx.execute(other->{
          connection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
          return switch(reader) {
            case "contract" -> mapper.lock(batch.getRepriceNo()).getStatus();
            case "reuse" -> reuseMapper.lockBatch(batch.getRepriceNo()).status();
            default -> throw new AssertionError(reader);
          };
        })));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        boolean waiting=false;
        while(System.nanoTime()<deadline) {
          waiting=connection.get()!=0 && jdbc.queryForObject("""
              SELECT COUNT(*) FROM performance_schema.data_lock_waits w
              JOIN performance_schema.threads t ON t.THREAD_ID=w.REQUESTING_THREAD_ID
              WHERE t.PROCESSLIST_ID=?
              """,Integer.class,connection.get())>0;
          if(waiting)break;
          assertThat(future.get().isDone()).as("reader must wait for the dispatcher row lock").isFalse();
          try {Thread.sleep(10);} catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        }
        assertThat(waiting).as("observed real InnoDB wait, not an assumed scheduling delay").isTrue();
        waitingLocks.addAll(jdbc.queryForList("""
            SELECT l.INDEX_NAME,l.LOCK_DATA,l.LOCK_STATUS FROM performance_schema.data_locks l
            JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID
            WHERE t.PROCESSLIST_ID=? AND l.OBJECT_SCHEMA=DATABASE()
              AND l.OBJECT_NAME='lp_monthly_reprice_batch' AND l.LOCK_TYPE='RECORD'
              AND l.LOCK_STATUS='GRANTED'
            """,connection.get()));
        // An inverted reader would prevent this actual dispatcher progress update.
        assertThat(mapper.state(batch.getRepriceNo(),"RUNNING")).isEqualTo(1);
      });
      assertThat(future.get().get(10,TimeUnit.SECONDS)).isEqualTo("RUNNING");
    }
    assertThat(waitingLocks).as("a waiting batch reader must not hold another index of the same row").isEmpty();
    assertThat(mapper.readBatch(batch.getRepriceNo()).getResultRevision()).isEqualTo(1L);
  }

  private record BatchLockFixture(JdbcTemplate jdbc,MonthlyRepriceContractMapper contracts,
      MonthlyRepriceReuseMapper reuse,MonthlyRepriceWorkflowMapper workflow,TransactionTemplate transaction) {}

  private static BatchLockFixture batchLockFixture(MonthlyRepriceBatch batch,boolean singleRow) throws Exception {
    String schema="lock_shape_"+UUID.randomUUID().toString().replace("-","");
    jdbc.execute("CREATE DATABASE "+schema);
    for(String table:List.of("lp_monthly_reprice_batch","lp_cost_run_task")) {
      jdbc.execute("CREATE TABLE "+schema+"."+table+" LIKE "+table);
      var columns=jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND GENERATION_EXPRESSION='' ORDER BY ORDINAL_POSITION",String.class,table);
      String fields=columns.stream().map(c->"`"+c.replace("`","``")+"`").collect(java.util.stream.Collectors.joining(","));
      String predicate=table.equals("lp_cost_run_task")?"source_no=?":singleRow?"reprice_no=?":"reprice_no=? OR id=1";
      jdbc.update("INSERT INTO "+schema+"."+table+"("+fields+") SELECT "+fields+" FROM "+table+" WHERE "+predicate,batch.getRepriceNo());
    }
    var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl().replace("/mrp1130_contract_test","/"+schema),MYSQL.getUsername(),MYSQL.getPassword());
    var query=new JdbcTemplate(ds);
    assertThat(query.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_batch",Integer.class)).isEqualTo(singleRow?1:2);
    var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);
    config.addMapper(MonthlyRepriceContractMapper.class);config.addMapper(MonthlyRepriceReuseMapper.class);
    config.addMapper(MonthlyRepriceWorkflowMapper.class);
    var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
    var sql=new SqlSessionTemplate(factory.getObject());
    return new BatchLockFixture(query,sql.getMapper(MonthlyRepriceContractMapper.class),
        sql.getMapper(MonthlyRepriceReuseMapper.class),sql.getMapper(MonthlyRepriceWorkflowMapper.class),
        new TransactionTemplate(new DataSourceTransactionManager(ds)));
  }

  @Test void dispatcherSkipsABusyEarlierBatchAndReturnsToItAfterRelease() throws Exception {
    var older=start(source("COMMERCIAL",6831));
    auth("HOUSEHOLD","ROLE_BU_DIRECTOR");
    var other=service.enqueue("HOUSEHOLD",MONTH,LIVE,List.of(source("HOUSEHOLD",6832)));
    var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
    try(var pool=Executors.newSingleThreadExecutor()) {
      var holder=pool.submit(()->tx.executeWithoutResult(status->{
        assertThat(mapper.lock(older.getRepriceNo())).isNotNull();locked.countDown();
        try {assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
      }));
      try {
        assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
        var claimed=dispatch.claim("skip-busy-batch");
        assertThat(claimed).isNotNull();assertThat(claimed.getSourceNo()).isEqualTo(other.getRepriceNo());
        assertThat(task(older).getStatus()).isEqualTo("PENDING");
      } finally {release.countDown();}
      holder.get(10,TimeUnit.SECONDS);
      var claimed=dispatch.claim("return-to-older-batch");
      assertThat(claimed).isNotNull();assertThat(claimed.getSourceNo()).isEqualTo(older.getRepriceNo());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"contract","reuse","month"})
  void batchLockingReadsSeeAnIdentityCommittedAfterTheTransactionSnapshot(String reader) {
    try(var pool=Executors.newSingleThreadExecutor()) {
      tx.executeWithoutResult(status->{
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_batch WHERE contract_version=1",Integer.class)).isZero();
        try {
          pool.submit(()->tx.executeWithoutResult(other->
              mapper.reserve("late-visible-batch","COMMERCIAL",MONTH.toString(),LIVE.toString(),"test")))
              .get(10,TimeUnit.SECONDS);
        } catch(Exception e) {throw new IllegalStateException(e);}
        // Match duplicate concurrent submission after an earlier ordinary consistent read.
        if(reader.equals("month"))mapper.reserve("duplicate-new-number","COMMERCIAL",MONTH.toString(),LIVE.toString(),"test");
        switch(reader) {
          case "contract" -> assertThat(mapper.lock("late-visible-batch")).isNotNull();
          case "reuse" -> assertThat(reuseMapper.lockBatch("late-visible-batch")).isNotNull();
          case "month" -> assertThat(mapper.month("COMMERCIAL",MONTH.toString())).isNotNull();
          default -> throw new AssertionError(reader);
        }
      });
    }
  }

  private static MonthlyRepriceBatch retryFailed(String no) {
    var b=mapper.readBatch(no);MonthlyRepriceContractService.requireOperator(b.getBusinessUnitType());
    var ids=mapper.tasks(no).stream().filter(t->"FAILED".equals(t.getStatus())).map(CostRunTask::getId).toList();
    var sources=jdbc.queryForList("SELECT id FROM lp_monthly_reprice_source WHERE batch_id=? AND object_id IS NULL",Long.class,b.getId());
    return ids.isEmpty() && sources.isEmpty()?service.recheck(no):service.retry(no,new com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.RetrySelection(ids,sources));
  }

  private static int begin(String no, long id) {
    var leased=dispatch.claim("contract-test-"+java.util.UUID.randomUUID());
    assertThat(leased).isNotNull();
    assertThat(leased.getSourceNo()).isEqualTo(no); assertThat(leased.getId()).isEqualTo(id);
    return leased.getExecutionNo();
  }
  private static void complete(String no, long id, int execution, long version) {
    service.complete(no,id,execution,mapper.readTask(no,id).getWorkerId(),version);
  }
  private static void fail(String no, long id, int execution, String reason) {
    service.fail(no,id,execution,mapper.readTask(no,id).getWorkerId(),reason);
  }

  @Test void completingOneCustomerNeverLoadsOrRecountsAllOtherCustomers() throws Exception {
    var batch=start(java.util.stream.LongStream.range(1960,1972)
        .mapToObj(id->source("COMMERCIAL",id)).toArray(Source[]::new));
    var task=dispatch.claim("progress-owner");long version=version(batch,task);
    var observation=new ProgressReads();progressReads.set(observation);
    try {service.complete(batch.getRepriceNo(),task.getId(),task.getExecutionNo(),task.getWorkerId(),version);}
    finally {progressReads.remove();}
    var saved=mapper.readBatch(batch.getRepriceNo());
    assertThat(saved.getTotalCount()).isEqualTo(12);assertThat(saved.getSuccessCount()).isEqualTo(1);
    assertThat(saved.getFailedCount()).isZero();
    assertThat(jdbc.queryForMap("SELECT success_count,failed_count,progress FROM lp_cost_run_batch WHERE batch_no=?",batch.getRepriceNo()))
        .containsEntry("success_count",1).containsEntry("failed_count",0).containsEntry("progress",8);
    var evidence=Map.of("objects",12,"completed",1,"returnedTaskRows",observation.returnedTaskRows,
        "taskQueries",observation.taskQueries,"taskAggregateStatements",observation.taskAggregateStatements);
    String folder=System.getProperty("reprice.test.evidence");
    if(folder!=null)new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
        java.nio.file.Path.of(folder,"per-object-progress-reads.json").toFile(),evidence);
    assertThat(observation.returnedTaskRows).as("a single completion needs only its own saved task").isEqualTo(1);
    assertThat(observation.taskQueries).isEqualTo(1);
    assertThat(observation.taskAggregateStatements).as("progress cannot rescan the batch after each object").isZero();
  }

  @Test void concurrentCompletionsAndRepeatedDeliveryCountEachCustomerExactlyOnce() throws Exception {
    var batch=start(source("COMMERCIAL",1980),source("COMMERCIAL",1981),source("COMMERCIAL",1982),source("COMMERCIAL",1983));
    var claimed=dispatch.claimBatch("progress-concurrent",4);assertThat(claimed).hasSize(4);
    var versions=new HashMap<Long,Long>();
    for(var task:claimed)versions.put(task.getId(),version(batch,task));
    var barrier=new CyclicBarrier(4);
    try(var pool=Executors.newFixedThreadPool(4)) {
      var futures=new ArrayList<Future<?>>();
      for(var task:claimed)futures.add(pool.submit(()->{
        barrier.await(10,TimeUnit.SECONDS);
        service.complete(batch.getRepriceNo(),task.getId(),task.getExecutionNo(),task.getWorkerId(),versions.get(task.getId()));
        service.complete(batch.getRepriceNo(),task.getId(),task.getExecutionNo(),task.getWorkerId(),versions.get(task.getId()));
        return null;
      }));
      for(var future:futures)future.get(20,TimeUnit.SECONDS);
    }
    assertProgressMatchesTasks(batch.getRepriceNo(),4,0,100);
    assertThat(mapper.readBatch(batch.getRepriceNo()).getStatus()).isEqualTo("CHECKING");
  }

  @Test void terminalFailureAutomaticDeferralAndManualRetryKeepProgressExact() {
    var batch=start(source("COMMERCIAL",1984),source("COMMERCIAL",1985),source("COMMERCIAL",1986));
    var claims=dispatch.claimBatch("progress-mixed",3);assertThat(claims).hasSize(3);String no=batch.getRepriceNo();
    var a=claims.get(0);service.complete(no,a.getId(),a.getExecutionNo(),a.getWorkerId(),version(batch,a));
    var savedTask=jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",a.getId());
    dispatch.fail(claims.get(1),"progress-mixed",new MonthlyRepriceInputNotReady("隔离样本缺人工"));
    dispatch.fail(claims.get(1),"progress-mixed",new MonthlyRepriceInputNotReady("重复失败通知"));
    dispatch.fail(claims.get(2),"progress-mixed",new MonthlyRepriceSharedNumbers.GroupBusy("controlled wait",null));
    assertProgressMatchesTasks(no,1,1,33);
    retryFailed(no);
    assertProgressMatchesTasks(no,1,0,33);
    assertThat(jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",a.getId())).isEqualTo(savedTask);
    jdbc.update("UPDATE lp_cost_run_task SET result_summary_json=JSON_REMOVE(result_summary_json,'$.retryNotBefore') WHERE id=?",claims.get(2).getId());
    var retried=dispatch.claimBatch("progress-repaired",3);assertThat(retried).hasSize(2);
    for(var task:retried)service.complete(no,task.getId(),task.getExecutionNo(),task.getWorkerId(),version(batch,task));
    assertProgressMatchesTasks(no,3,0,100);
    assertThat(jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",a.getId())).isEqualTo(savedTask);
  }

  @Test void failedProgressWriteRollsBackCustomerResultAndDoesNotLoseOrDuplicateItsCounter() {
    var batch=start(source("COMMERCIAL",1987),source("COMMERCIAL",1988));String no=batch.getRepriceNo();
    var task=dispatch.claim("progress-rollback");long version=version(batch,task);
    var beforeTasks=jdbc.queryForList("SELECT * FROM lp_cost_run_task WHERE source_no=? ORDER BY id",no);
    var beforeResults=jdbc.queryForList("SELECT * FROM lp_monthly_reprice_result WHERE reprice_no=? ORDER BY id",no);
    jdbc.execute("CREATE TRIGGER reject_completion_progress BEFORE UPDATE ON lp_monthly_reprice_batch FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='COMPLETION_PROGRESS_ROLLBACK'");
    try {assertThatThrownBy(()->service.complete(no,task.getId(),task.getExecutionNo(),task.getWorkerId(),version))
        .hasStackTraceContaining("COMPLETION_PROGRESS_ROLLBACK");}
    finally {jdbc.execute("DROP TRIGGER reject_completion_progress");}
    assertThat(jdbc.queryForList("SELECT * FROM lp_cost_run_task WHERE source_no=? ORDER BY id",no)).usingRecursiveComparison().isEqualTo(beforeTasks);
    assertThat(jdbc.queryForList("SELECT * FROM lp_monthly_reprice_result WHERE reprice_no=? ORDER BY id",no)).usingRecursiveComparison().isEqualTo(beforeResults);
    assertProgressMatchesTasks(no,0,0,0);
    service.complete(no,task.getId(),task.getExecutionNo(),task.getWorkerId(),version);
    service.complete(no,task.getId(),task.getExecutionNo(),task.getWorkerId(),version);
    assertProgressMatchesTasks(no,1,0,50);
  }

  @Test void rejectedProgressInvariantCannotLeaveACommittedCustomerLease() {
    var batch=start(source("COMMERCIAL",1989));String no=batch.getRepriceNo();
    jdbc.update("UPDATE lp_monthly_reprice_batch SET success_count=total_count+1 WHERE reprice_no=?",no);
    var original=jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE source_no=?",no);
    assertThatThrownBy(()->dispatch.claim("bad-progress")).hasMessageContaining("调价进度");
    assertThat(jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE source_no=?",no)).isEqualTo(original);
    assertThat(mapper.readBatch(no).getStatus()).isEqualTo("QUEUED");
  }

  private void assertProgressMatchesTasks(String no,int success,int failed,int percent) {
    var batch=mapper.readBatch(no);
    assertThat(batch.getSuccessCount()).isEqualTo(success);assertThat(batch.getFailedCount()).isEqualTo(failed);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_task WHERE source_no=? AND status='SUCCESS'",Integer.class,no)).isEqualTo(success);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_task WHERE source_no=? AND status='FAILED'",Integer.class,no)).isEqualTo(failed);
    assertThat(jdbc.queryForMap("SELECT success_count,failed_count,progress FROM lp_cost_run_batch WHERE batch_no=?",no))
        .containsEntry("success_count",success).containsEntry("failed_count",failed).containsEntry("progress",percent);
  }

  @Test void annualUsageUnitIsCapturedWithSelectedWholeSourceAndNeverReloadedOnRetry() throws Exception {
    var original=source("COMMERCIAL",3989);
    jdbc.update("INSERT INTO lp_oa_form_item_extra_field VALUES(?,'ANNUAL_VOLUME_UNIT','TEN_THOUSAND_PIECES')",original.object().oaFormItemId());
    var batch=start(original);
    var mapper=new ObjectMapper();
    var fixed=mapper.readTree(task(batch).getRequestSnapshotJson()).path("selectedInput").path("annualUsage");
    assertThat(fixed.path("unit").asText()).isEqualTo("TEN_THOUSAND_PIECES");
    jdbc.update("UPDATE lp_oa_form_item_extra_field SET field_value='PIECES'");
    assertThat(mapper.readTree(task(service.enqueue("COMMERCIAL",MONTH)).getRequestSnapshotJson())
        .path("selectedInput").path("annualUsage")).isEqualTo(fixed);
  }

  @Test void unfinishedFolderStopsRepricingUntilAllFilesAreCompleted() {
    var source=source("COMMERCIAL",3990);
    jdbc.update("INSERT INTO lp_monthly_price_import(business_unit_type,pricing_month,request_key,status,revision,created_by,created_at,updated_at) VALUES('COMMERCIAL',?,'folder-guard','PARTIAL',1,'test',NOW(),NOW())",MONTH.toString());
    assertThatThrownBy(()->start(source)).hasMessageContaining("价格文件夹尚未处理完成");
    assertThat(service.blocksQuotation("COMMERCIAL")).isFalse();
    jdbc.update("UPDATE lp_monthly_price_import SET status='COMPLETE'");
    assertThat(start(source).getStatus()).isEqualTo("QUEUED");
  }

  @Test void ordinaryPricingMonthChangesOnlyAfterDirectorConfirmsTheWholeBatch() {
    var source=source("COMMERCIAL",3010);
    assertThat(pricingPeriods.currentMonth("COMMERCIAL")).isEqualTo("2025-12");
    var batch=start(source);
    assertThat(control.blocked("COMMERCIAL")).isTrue();
    assertThat(pricingPeriods.currentMonth("COMMERCIAL")).isEqualTo("2025-12");
    var checked=ready(batch);
    // Even complete target-month cost/price/BOM results are still awaiting publication.
    assertThat(pricingPeriods.currentMonth("COMMERCIAL")).isEqualTo("2025-12");
    assertThatThrownBy(() -> pricingPeriods.requireActiveMonth("2026-01", "COMMERCIAL"))
        .hasMessageContaining("2025-12");
    publications.confirm(checked.getRepriceNo(),checked.getCheckedRevision());
    assertThat(pricingPeriods.currentMonth("COMMERCIAL")).isEqualTo("2026-01");
    assertThat(pricingPeriods.currentMonth("HOUSEHOLD")).isEqualTo("2025-11");
    assertThat(control.blocked("COMMERCIAL")).isFalse();
    assertThat(jdbc.queryForObject("SELECT pricing_month FROM lp_quote_cost_run_version WHERE id=?",
        String.class,source.sourceCostVersionId())).isEqualTo("2025-12");
    assertThatThrownBy(() -> pricingPeriods.requireActiveMonth("2025-12", "COMMERCIAL"))
        .hasMessageContaining("请刷新");
  }

  @Test void failedRepricingAndRolledBackConfirmationNeverPublishANewMonth() {
    var batch=start(source("COMMERCIAL",3011));
    var task=task(batch);
    int execution=begin(batch.getRepriceNo(),task.getId());
    fail(batch.getRepriceNo(),task.getId(),execution,"图库暂不可用");
    assertThat(pricingPeriods.currentMonth("COMMERCIAL")).isEqualTo("2025-12");
    assertThat(control.blocked("COMMERCIAL")).isTrue();
    var checked=ready(retryFailed(batch.getRepriceNo()));
    jdbc.execute("CREATE TRIGGER reject_publication_audit BEFORE INSERT ON lp_monthly_reprice_audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PUBLICATION_ROLLBACK'");
    try {assertThatThrownBy(()->publications.confirm(checked.getRepriceNo(),checked.getCheckedRevision()))
        .hasStackTraceContaining("PUBLICATION_ROLLBACK");}
    finally {jdbc.execute("DROP TRIGGER reject_publication_audit");}
    assertThat(pricingPeriods.currentMonth("COMMERCIAL")).isEqualTo("2025-12");
    assertThat(control.blocked("COMMERCIAL")).isTrue();
    publications.confirm(checked.getRepriceNo(),checked.getCheckedRevision());
    assertThat(pricingPeriods.currentMonth("COMMERCIAL")).isEqualTo("2026-01");
  }
  static void auth(String unit,String... roles) {
    var a=new UsernamePasswordAuthenticationToken("mrp-director",null,Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    a.setDetails(Map.of("businessUnitType",unit));SecurityContextHolder.getContext().setAuthentication(a);
  }
  static Source source(String unit,long item) {
    jdbc.update("INSERT IGNORE INTO oa_form_item(id,oa_form_id,business_unit_type) VALUES(?,?,?)",item,unit.equals("COMMERCIAL")?1:2,unit);
    jdbc.update("UPDATE oa_form_item SET material_no=? WHERE id=?","TOP-"+item,item);
    String no=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO lp_quote_cost_run_version(cost_run_no,oa_no,oa_form_item_id,product_code,pricing_month,result_period,status,total_cost,trial_finished_at,business_unit_type) VALUES(?,'MRP1130-TEST',?,?,'2025-12','2025-12','SUCCESS',12.123456,'2025-12-15',?)",no,item,"TOP-"+item,unit);
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
      int run=begin(b.getRepriceNo(),t.getId());
      complete(b.getRepriceNo(),t.getId(),run,version(b,t));
    }
    var checking=mapper.lock(b.getRepriceNo());
    return recordFixtureCheck(b.getRepriceNo(),checking.getResultRevision(),true,Map.of("contractCompleteness","PASSED"));
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
    retryFailed(batch.getRepriceNo());var retry=dispatch.claim("worker-c");
    assertThat(retry.getId()).isEqualTo(a.getId());assertThat(retry.getExecutionNo()).isEqualTo(b.getExecutionNo()+1);
    assertThat(mapper.results(batch.getRepriceNo())).hasSize(1);
  }

  @Test void boundedClaimBatchLeasesDistinctObjectsAndLeavesRemainderForAnotherInstance() {
    var batch=start(source("COMMERCIAL",1912),source("COMMERCIAL",1913),source("COMMERCIAL",1914),source("COMMERCIAL",1915));
    var first=dispatch.claimBatch("batch-worker",3);
    assertThat(first).hasSize(3);assertThat(first).extracting(CostRunTask::getId).doesNotHaveDuplicates();
    assertThat(first).allSatisfy(task->{assertThat(task.getWorkerId()).isEqualTo("batch-worker");assertThat(task.getStatus()).isEqualTo("RUNNING");});
    var second=dispatch.claimBatch("other-worker",3);assertThat(second).hasSize(1);
    assertThat(first).extracting(CostRunTask::getId).doesNotContain(second.getFirst().getId());
    assertThat(dispatch.claimBatch("no-overclaim",64)).isEmpty();
    assertThatThrownBy(()->dispatch.claimBatch("invalid",0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->dispatch.claimBatch("invalid",65)).isInstanceOf(IllegalArgumentException.class);
    assertThat(mapper.tasks(batch.getRepriceNo())).hasSize(4);
  }

  @Test void independentWorkersShareOneDatabaseWideActiveBudget() {
    var batch=start(source("COMMERCIAL",1920),source("COMMERCIAL",1921),source("COMMERCIAL",1922),
        source("COMMERCIAL",1923),source("COMMERCIAL",1924),source("COMMERCIAL",1925));
    var first=dispatch.claimBatch("capacity-a",3);
    assertThat(first).hasSize(3);
    var second=dispatch.claimBatch("capacity-b",3);
    assertThat(second).hasSize(1);
    assertThat(dispatch.claimBatch("capacity-c",3)).isEmpty();
    assertThat(mapper.tasks(batch.getRepriceNo()).stream().filter(t->"RUNNING".equals(t.getStatus())))
        .hasSize(4);
    jdbc.update("UPDATE lp_cost_run_task SET lock_expire_time=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?",first.getFirst().getId());
    var takeover=dispatch.claimBatch("capacity-replacement",3);
    assertThat(takeover).hasSize(1);
    assertThat(takeover.getFirst().getId()).isEqualTo(first.getFirst().getId());
    assertThat(takeover.getFirst().getExecutionNo()).isEqualTo(first.getFirst().getExecutionNo()+1);
  }

  @Test void fourSimultaneousDispatchersCannotMultiplyTheGlobalBudget() throws Exception {
    capacity.setMaxActive(2);
    start(java.util.stream.LongStream.range(1930,1938).mapToObj(id->source("COMMERCIAL",id)).toArray(Source[]::new));
    var go=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(4)) {
      var futures=new ArrayList<Future<List<CostRunTask>>>();
      for(int i=0;i<4;i++) {
        String owner="independent-"+i;
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());
        var independent=new MonthlyRepriceDispatchService(mapper,workflow,service,
            org.mockito.Mockito.mock(MonthlyRepriceCheckCoordinator.class),manager,
            new MonthlyRepriceAdmission(jdbc.getDataSource(),manager,capacity));
        futures.add(pool.submit(()->{go.await();return independent.claimBatch(owner,8);}));
      }
      go.countDown();var all=new ArrayList<CostRunTask>();
      for(var future:futures)all.addAll(future.get(10,TimeUnit.SECONDS));
      assertThat(all).hasSize(2);assertThat(all).extracting(CostRunTask::getId).doesNotHaveDuplicates();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_task WHERE status='RUNNING'",Integer.class)).isEqualTo(2);
      assertThat(dispatch.claimBatch("still-full",8)).isEmpty();
    }
  }

  @Test void incompatiblePoliciesDrainExistingWorkWithoutFailingOrOverclaimingCustomers() {
    var batch=start(source("COMMERCIAL",1940),source("COMMERCIAL",1941));
    var first=dispatch.claim("old-policy");
    var original=jdbc.queryForList("SELECT * FROM lp_cost_run_task ORDER BY id");
    capacity.setMaxActive(8);
    assertThat(dispatch.claimBatch("new-policy",8)).isEmpty();
    assertThat(jdbc.queryForList("SELECT * FROM lp_cost_run_task ORDER BY id")).isEqualTo(original);
    service.complete(batch.getRepriceNo(),first.getId(),first.getExecutionNo(),first.getWorkerId(),version(batch,first));
    var next=dispatch.claim("new-policy");assertThat(next).isNotNull();
    assertThat(next.getResultSummaryJson()).contains(capacity.rules().identity());
  }

  @Test void insufficientServerHeadroomLeavesAllObjectsPendingAndRecoversWithoutRetry() {
    var batch=start(source("COMMERCIAL",1942));
    var original=jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE source_no=?",batch.getRepriceNo());
    capacity.setReservedConnections(Integer.MAX_VALUE);
    assertThat(dispatch.claim("no-headroom")).isNull();
    assertThat(jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE source_no=?",batch.getRepriceNo())).isEqualTo(original);
    capacity.setReservedConnections(32);
    assertThat(dispatch.claim("headroom-restored")).isNotNull();
  }

  @Test void admissionMutexRemainsHeldUntilTheLeasesAreCommitted() throws Exception {
    var batch=start(source("COMMERCIAL",1943),source("COMMERCIAL",1944));
    var first=task(batch);var beforeCommit=new CountDownLatch(1);var commit=new CountDownLatch(1);
    try(var pool=Executors.newSingleThreadExecutor()) {
      var future=pool.submit(()->admission.claim(1,(limit,policy)->{
        assertThat(workflow.lease(first.getId(),"commit-owner",policy)).isEqualTo(1);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override public void beforeCommit(boolean readOnly) {
                beforeCommit.countDown();
                try {if(!commit.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Commit probe timed out");}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
              }
            });
        return List.of(mapper.readTask(batch.getRepriceNo(),first.getId()));
      }));
      try {
        assertThat(beforeCommit.await(5,TimeUnit.SECONDS)).isTrue();
        assertThat(mapper.readTask(batch.getRepriceNo(),first.getId()).getStatus()).isEqualTo("PENDING");
        assertThat(dispatch.claim("must-not-read-an-uncommitted-budget")).isNull();
      } finally {commit.countDown();}
      assertThat(future.get(5,TimeUnit.SECONDS)).hasSize(1);
      assertThat(dispatch.claim("after-commit")).isNotNull();
    }
  }

  @Test void globalPacingCoversDifferentDispatcherConnectionsAndCommittedTasksSurviveInterrupt() {
    capacity.setClaimsPerSecond(2);
    var batch=start(source("COMMERCIAL",1945),source("COMMERCIAL",1946),source("COMMERCIAL",1947));
    long started=System.nanoTime();
    assertThat(dispatch.claimBatch("paced-one",1)).hasSize(1);
    assertThat(dispatch.claimBatch("paced-two",1)).hasSize(1);
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isGreaterThanOrEqualTo(990);
    // Interrupt only after the lease has committed. Queue shutdown must receive the committed task.
    var result=admission.claim(1,(limit,policy)->{
      var pending=workflow.candidate(batch.getRepriceNo());
      assertThat(workflow.lease(pending.getId(),"shutdown-owner",policy)).isEqualTo(1);
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
          new org.springframework.transaction.support.TransactionSynchronization() {
            @Override public void afterCommit(){Thread.currentThread().interrupt();}
          });
      return List.of(mapper.readTask(pending.getSourceNo(),pending.getId()));
    });
    try {
      assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(result).hasSize(1);
      Thread.interrupted();
      assertThat(mapper.readTask(result.getFirst().getSourceNo(),result.getFirst().getId()).getStatus()).isEqualTo("RUNNING");
    } finally {Thread.interrupted();}
  }

  @Test void capacityQueriesNeedOnlyAnApplicationAccountWithoutGlobalAdministrationPrivileges() {
    jdbc.execute("CREATE USER 'reprice_capacity_probe'@'%' IDENTIFIED BY 'isolated-capacity-probe-only'");
    jdbc.execute("GRANT SELECT ON `"+MYSQL.getDatabaseName()+"`.* TO 'reprice_capacity_probe'@'%'");
    var restricted=new DriverManagerDataSource(MYSQL.getJdbcUrl(),"reprice_capacity_probe","isolated-capacity-probe-only");
    var checked=new java.util.concurrent.atomic.AtomicBoolean();
    var gate=new MonthlyRepriceAdmission(restricted,new DataSourceTransactionManager(restricted),capacity);
    assertThat(gate.claim(1,(allowed,policy)->{
      assertThat(allowed).isEqualTo(1);checked.set(true);return List.of();
    })).isEmpty();
    assertThat(checked).isTrue();
  }

  @Test void failureHalfwayThroughBatchClaimRollsBackAllItsLeases() {
    var batch=start(source("COMMERCIAL",1916),source("COMMERCIAL",1917));String no=batch.getRepriceNo();
    var original=jdbc.queryForList("SELECT * FROM lp_cost_run_task WHERE source_no=? ORDER BY id",no);
    long second=((Number)original.get(1).get("id")).longValue();
    jdbc.execute("CREATE TRIGGER reject_batch_claim BEFORE UPDATE ON lp_cost_run_task FOR EACH ROW BEGIN IF NEW.id="+second+" AND NEW.status='RUNNING' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CLAIM_BATCH_ROLLBACK'; END IF; END");
    try {assertThatThrownBy(()->dispatch.claimBatch("rolled-back",2)).hasStackTraceContaining("CLAIM_BATCH_ROLLBACK");}
    finally {jdbc.execute("DROP TRIGGER reject_batch_claim");}
    assertThat(jdbc.queryForList("SELECT * FROM lp_cost_run_task WHERE source_no=? ORDER BY id",no)).isEqualTo(original);
    assertThat(mapper.readBatch(no).getStatus()).isEqualTo("QUEUED");
    assertThat(dispatch.claimBatch("next-owner",2)).hasSize(2);
  }

  @Test void sharedGroupWaitSchedulesDurableRetryWithoutBlockingOtherCustomers() throws Exception {
    var batch=start(source("COMMERCIAL",1904),source("COMMERCIAL",1905));
    String no=batch.getRepriceNo();
    var first=dispatch.claim("worker-first");
    dispatch.fail(first,"worker-first",new MonthlyRepriceSharedNumbers.GroupBusy("共享成本仍在计算",null));
    var pending=mapper.readTask(no,first.getId());
    assertThat(pending.getStatus()).isEqualTo("PENDING");
    assertThat(pending.getRetryCount()).isEqualTo(1);
    assertThat(pending.getErrorMessage()).contains("共享成本仍在计算");
    assertThat(new ObjectMapper().readTree(pending.getResultSummaryJson()).path("failure").path("code").asText())
        .isEqualTo("SHARED_GROUP_BUSY");
    assertThat(mapper.readBatch(no).getStatus()).isEqualTo("RUNNING");
    assertThat(control.blocked("COMMERCIAL")).isTrue();
    assertThatThrownBy(()->publications.confirm(no,mapper.readBatch(no).getResultRevision())).isInstanceOf(IllegalStateException.class);
    var other=dispatch.claim("worker-other");
    assertThat(other).isNotNull();assertThat(other.getId()).isNotEqualTo(first.getId());
    long saved=version(batch,other);service.complete(no,other.getId(),other.getExecutionNo(),"worker-other",saved);
    CostRunTask resumed=null;long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
    while(resumed==null && System.nanoTime()<limit) {
      resumed=dispatch.claim("worker-retry");if(resumed==null)Thread.sleep(25);
    }
    assertThat(resumed).isNotNull();assertThat(resumed.getId()).isEqualTo(first.getId());
    assertThat(resumed.getExecutionNo()).isGreaterThan(first.getExecutionNo());
    var before=jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",resumed.getId());
    dispatch.fail(first,"worker-first",new IllegalStateException("late old failure"));
    assertThat(jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",resumed.getId())).isEqualTo(before);
    assertThat(mapper.readTask(no,other.getId()).getCostRunVersionId()).isEqualTo(saved);
  }

  @Test void missingInputIsActionableAndDoesNotSkipOtherPendingCustomers() {
    var batch=start(source("COMMERCIAL",1906),source("COMMERCIAL",1907));String no=batch.getRepriceNo();
    var missing=dispatch.claim("missing-owner");
    dispatch.fail(missing,"missing-owner",new IllegalStateException("缺少适用人工资料"));
    var failed=mapper.readTask(no,missing.getId());
    assertThat(failed.getStatus()).isEqualTo("FAILED");assertThat(failed.getRetryCount()).isZero();
    assertThat(failed.getErrorMessage()).contains("缺少适用人工资料");
    assertThat(mapper.readBatch(no).getStatus()).isEqualTo("FAILED");
    var other=dispatch.claim("another-owner");assertThat(other).isNotNull();
    assertThat(other.getId()).isNotEqualTo(missing.getId());
    service.complete(no,other.getId(),other.getExecutionNo(),"another-owner",version(batch,other));
    assertThat(mapper.readBatch(no).getStatus()).isEqualTo("FAILED");
    assertThat(control.blocked("COMMERCIAL")).isTrue();
    assertThatThrownBy(()->publications.confirm(no,mapper.readBatch(no).getResultRevision())).isInstanceOf(IllegalStateException.class);
    assertThat(dispatch.claim("no-unbounded-business-retry")).isNull();
  }

  @Test void automaticRetryBudgetSurvivesDispatcherReplacementAndExplicitRetryStartsANewBudget() throws Exception {
    var batch=start(source("COMMERCIAL",1908));String no=batch.getRepriceNo();
    var current=dispatch;
    for(int attempt=0;attempt<4;attempt++) {
      String owner="replacement-"+attempt;var task=current.claim(owner);assertThat(task).isNotNull();
      current.fail(task,owner,new org.springframework.dao.CannotAcquireLockException("controlled deadlock"));
      var stored=mapper.readTask(no,task.getId());
      assertThat(stored.getRetryCount()).isEqualTo(Math.min(attempt+1,3));
      assertThat(stored.getStatus()).isEqualTo(attempt<3?"PENDING":"FAILED");
      var failure=new ObjectMapper().readTree(stored.getResultSummaryJson()).path("failure");
      assertThat(failure.path("automaticRetries").asInt()).isEqualTo(Math.min(attempt+1,3));
      assertThat(failure.path("exhausted").asBoolean()).isEqualTo(attempt==3);
      if(attempt<3) {
        // The real two-second deadline is exercised above. Advance only this test's saved due
        // time here, so budget/fencing assertions do not depend on fourteen seconds of sleeps.
        jdbc.update("UPDATE lp_cost_run_task SET result_summary_json=JSON_SET(result_summary_json,'$.retryNotBefore','2000-01-01 00:00:00.000000') WHERE id=?",task.getId());
      }
      current=new MonthlyRepriceDispatchService(mapper,workflow,service,
          org.mockito.Mockito.mock(MonthlyRepriceCheckCoordinator.class),new DataSourceTransactionManager(jdbc.getDataSource()),admission);
    }
    assertThat(current.claim("exhausted")).isNull();assertThat(control.blocked("COMMERCIAL")).isTrue();
    retryFailed(no);var manual=current.claim("manual-retry");assertThat(manual).isNotNull();
    assertThat(manual.getRetryCount()).isEqualTo(4);
    current.fail(manual,"manual-retry",new org.springframework.dao.CannotAcquireLockException("new transient error"));
    var reset=mapper.readTask(no,manual.getId());assertThat(reset.getStatus()).isEqualTo("PENDING");
    assertThat(reset.getRetryCount()).isEqualTo(5);
    assertThat(new ObjectMapper().readTree(reset.getResultSummaryJson()).path("failure").path("automaticRetries").asInt()).isEqualTo(1);
  }

  @Test void retryRegistrationRollsBackTogetherWhenBatchProgressCannotBeUpdated() {
    var batch=start(source("COMMERCIAL",1909));String no=batch.getRepriceNo();var task=dispatch.claim("retry-owner");
    var before=jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",task.getId());
    var originalBatch=jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_batch WHERE reprice_no=?",no);
    jdbc.execute("CREATE TRIGGER reject_retry_progress BEFORE UPDATE ON lp_monthly_reprice_batch FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RETRY_PROGRESS_ROLLBACK'");
    try {
      assertThatThrownBy(()->dispatch.fail(task,"retry-owner",new MonthlyRepriceSharedNumbers.GroupBusy("busy",null)))
          .hasStackTraceContaining("RETRY_PROGRESS_ROLLBACK");
    } finally {jdbc.execute("DROP TRIGGER reject_retry_progress");}
    assertThat(jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",task.getId())).isEqualTo(before);
    assertThat(jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_batch WHERE reprice_no=?",no)).isEqualTo(originalBatch);
  }

  @Test void repeatedIdenticalBusinessFailureStillStopsAfterAnExplicitManualRetry() {
    var batch=start(source("COMMERCIAL",1910));String no=batch.getRepriceNo();
    for(int attempt=0;attempt<2;attempt++) {
      var task=dispatch.claim("same-failure");assertThat(task).isNotNull();
      dispatch.fail(task,"same-failure",new MonthlyRepriceInputNotReady("仍缺人工资料"));
      assertThat(mapper.readTask(no,task.getId()).getStatus()).isEqualTo("FAILED");
      assertThat(dispatch.claim("do-not-spin")).isNull();
      if(attempt==0)retryFailed(no);
    }
  }

  @Test void lostCompletionReplyCannotRequeueAnAlreadySuccessfulCustomer() {
    var batch=start(source("COMMERCIAL",1911));String no=batch.getRepriceNo();var task=dispatch.claim("completed-owner");
    long saved=version(batch,task);service.complete(no,task.getId(),task.getExecutionNo(),"completed-owner",saved);
    var before=jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",task.getId());
    dispatch.fail(task,"completed-owner",new org.springframework.dao.RecoverableDataAccessException("completion reply lost"));
    assertThat(jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",task.getId())).isEqualTo(before);
    assertThat(mapper.readBatch(no).getStatus()).isEqualTo("CHECKING");assertThat(dispatch.claim("no-double-cost")).isNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE reprice_no=?",Integer.class,no)).isEqualTo(1);
  }

  @Test void expiredCustomerLeaseCannotBeResurrectedByALateHeartbeat() {
    var batch=start(source("COMMERCIAL",905));
    var task=dispatch.claim("old-worker");
    jdbc.update("UPDATE lp_cost_run_task SET lock_expire_time=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?",task.getId());
    var expired=mapper.readTask(batch.getRepriceNo(),task.getId()).getLockExpireTime();
    dispatch.heartbeat(task,"old-worker");
    assertThat(mapper.readTask(batch.getRepriceNo(),task.getId()).getLockExpireTime()).isEqualTo(expired);
    var next=dispatch.claim("new-worker");
    assertThat(next.getId()).isEqualTo(task.getId());
    assertThat(next.getExecutionNo()).isEqualTo(task.getExecutionNo()+1);
  }

  @Test void expiredCustomerLeaseCannotCompleteEvenBeforeAnotherWorkerTakesOver() {
    var batch=start(source("COMMERCIAL",906));
    var task=dispatch.claim("old-worker");
    long saved=version(batch,task);
    jdbc.update("UPDATE lp_cost_run_task SET lock_expire_time=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?",task.getId());
    assertThatThrownBy(()->complete(batch.getRepriceNo(),task.getId(),task.getExecutionNo(),saved))
        .isInstanceOf(IllegalStateException.class);
    assertThat(mapper.readTask(batch.getRepriceNo(),task.getId()).getStatus()).isEqualTo("RUNNING");
    assertThat(mapper.results(batch.getRepriceNo()).getFirst().getCostRunVersionId()).isNull();
  }

  @Test void expiredCustomerFailureCannotStopTheBatchBeforeTakeover() {
    var batch=start(source("COMMERCIAL",907));
    var task=dispatch.claim("old-worker");
    jdbc.update("UPDATE lp_cost_run_task SET lock_expire_time=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?",task.getId());
    dispatch.fail(task,"old-worker",new IllegalStateException("late failure"));
    assertThat(mapper.readTask(batch.getRepriceNo(),task.getId()).getStatus()).isEqualTo("RUNNING");
    assertThat(mapper.readBatch(batch.getRepriceNo()).getStatus()).isEqualTo("RUNNING");
  }

  @Test void anotherWorkerCannotCompleteOrFailALiveCustomerExecution() {
    var batch=start(source("COMMERCIAL",908));
    var task=dispatch.claim("owner"); long saved=version(batch,task);
    assertThatThrownBy(()->service.complete(batch.getRepriceNo(),task.getId(),task.getExecutionNo(),"other",saved))
        .hasMessageContaining("持有者");
    assertThatThrownBy(()->service.fail(batch.getRepriceNo(),task.getId(),task.getExecutionNo(),"other","wrong owner"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(mapper.readTask(batch.getRepriceNo(),task.getId()).getWorkerId()).isEqualTo("owner");
    complete(batch.getRepriceNo(),task.getId(),task.getExecutionNo(),saved);
    assertThat(mapper.readTask(batch.getRepriceNo(),task.getId()).getStatus()).isEqualTo("SUCCESS");
  }

  @Test void leaseExpirationDuringCustomerLinkRollsBackTheLinkBeforeSuccess() {
    var batch=start(source("COMMERCIAL",909));
    var task=dispatch.claim("owner"); long saved=version(batch,task);
    jdbc.update("UPDATE lp_cost_run_task SET lock_expire_time=DATE_ADD(NOW(6),INTERVAL 5 SECOND) WHERE id=?",task.getId());
    jdbc.execute("CREATE TRIGGER expire_during_customer_link BEFORE UPDATE ON lp_monthly_reprice_result FOR EACH ROW BEGIN "
        +"IF NEW.calc_status='SUCCESS' THEN DO SLEEP(6); END IF; END");
    try { assertThatThrownBy(()->complete(batch.getRepriceNo(),task.getId(),task.getExecutionNo(),saved)).hasMessageContaining("关联冲突"); }
    finally { jdbc.execute("DROP TRIGGER expire_during_customer_link"); }
    assertThat(mapper.results(batch.getRepriceNo()).getFirst().getCostRunVersionId()).isNull();
    assertThat(mapper.readTask(batch.getRepriceNo(),task.getId()).getStatus()).isEqualTo("RUNNING");
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
    jdbc.update("UPDATE oa_form SET customer='other-customer' WHERE id=3");
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
    fail(batch.getRepriceNo(),task.getId(),begin(batch.getRepriceNo(),task.getId()),"BOM待确认");
    dated(210,"2026-01-20 00:00:00","SUCCESS"); dated(211,"2026-01-25 00:00:00","SUCCESS");
    assertThat(service.enqueue("COMMERCIAL",MONTH).getRepriceNo()).isEqualTo(batch.getRepriceNo());
    retryFailed(batch.getRepriceNo());
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
    fail(b.getRepriceNo(),t.getId(),begin(b.getRepriceNo(),t.getId()),"missing data");
    assertThat(service.blocksQuotation("COMMERCIAL")).isTrue();
    assertThat(jdbc.queryForObject("SELECT active_lock_key FROM lp_monthly_reprice_batch WHERE reprice_no=?",String.class,b.getRepriceNo())).isEqualTo("COMMERCIAL");
    assertThat(start(source).getRepriceNo()).isEqualTo(b.getRepriceNo());
    assertThatThrownBy(()->service.enqueue("COMMERCIAL",MONTH.plusMonths(1),LIVE,List.of(source))).hasMessageContaining("未确认");
  }
  @Test void twoMonthsHaveDifferentIdentityOnlyAfterValidConfirmation() {
    var source=source("COMMERCIAL",12);var b=ready(start(source));
    publications.confirm(b.getRepriceNo(),b.getCheckedRevision());
    assertThat(service.blocksQuotation("COMMERCIAL")).isFalse();
    assertThat(start(source).getRepriceNo()).isEqualTo(b.getRepriceNo());
    var next=service.enqueue("COMMERCIAL",MONTH.plusMonths(1),LIVE,List.of(source));
    assertThat(next.getRepriceNo()).isNotEqualTo(b.getRepriceNo());
    assertThat(task(next).getCalcObjectKey()).isNotEqualTo(task(b).getCalcObjectKey());
  }
  @Test void distinctUnitsAndDistinctProductsAreNotMerged() {
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
    var b=start(source("COMMERCIAL",50));var t=task(b);int run=begin(b.getRepriceNo(),t.getId());long id=version(b,t);
    jdbc.execute("CREATE TRIGGER mrp_fail_publish BEFORE UPDATE ON lp_cost_run_task FOR EACH ROW BEGIN IF NEW.status='SUCCESS' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MRP_LINK_ROLLBACK'; END IF; END");
    try { assertThatThrownBy(()->complete(b.getRepriceNo(),t.getId(),run,id)).hasStackTraceContaining("MRP_LINK_ROLLBACK"); }
    finally { jdbc.execute("DROP TRIGGER mrp_fail_publish"); }
    assertThat(mapper.results(b.getRepriceNo()).getFirst().getCostRunVersionId()).isNull();
    assertThat(task(b).getStatus()).isEqualTo("RUNNING");
    complete(b.getRepriceNo(),t.getId(),run,id);
    assertThat(task(b).getCostRunVersionId()).isEqualTo(id);
  }
  @Test void outerCalculationTransactionAlsoRollsBackVersionAndLinks() {
    var b=start(source("COMMERCIAL",51));var t=task(b);int run=begin(b.getRepriceNo(),t.getId());
    assertThatThrownBy(()->tx.executeWithoutResult(status->{long id=version(b,t);complete(b.getRepriceNo(),t.getId(),run,id);throw new IllegalStateException("calculation failed");})).hasMessage("calculation failed");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE reprice_no=?",Integer.class,b.getRepriceNo())).isZero();
    assertThat(task(b).getStatus()).isEqualTo("RUNNING");
    assertThat(mapper.results(b.getRepriceNo()).getFirst().getCostRunVersionId()).isNull();
  }
  @Test void partialOrWrongOwnershipVersionCannotBeAssociated() {
    var b=start(source("COMMERCIAL",60));var t=task(b);int run=begin(b.getRepriceNo(),t.getId());long id=version(b,t);
    for(String mutation:List.of("status='FAILED'","reprice_no=NULL","business_unit_type='HOUSEHOLD'","pricing_month='2026-02'","oa_form_item_id=999","part_item_count=2","total_cost=99")) {
      tx.executeWithoutResult(status->{jdbc.update("UPDATE lp_quote_cost_run_version SET "+mutation+" WHERE id=?",id);assertThatThrownBy(()->complete(b.getRepriceNo(),t.getId(),run,id)).isInstanceOf(RuntimeException.class);status.setRollbackOnly();});
      assertThat(mapper.results(b.getRepriceNo()).getFirst().getCostRunVersionId()).isNull();
    }
    complete(b.getRepriceNo(),t.getId(),run,id);
  }
  @Test void duplicateCompletionAndConcurrentConfirmationAreIdempotent() throws Exception {
    var b=start(source("COMMERCIAL",70));var t=task(b);int run=begin(b.getRepriceNo(),t.getId());long id=version(b,t);
    complete(b.getRepriceNo(),t.getId(),run,id);long revision=mapper.lock(b.getRepriceNo()).getResultRevision();
    complete(b.getRepriceNo(),t.getId(),run,id);
    assertThat(mapper.lock(b.getRepriceNo()).getResultRevision()).isEqualTo(revision);
    var ready=recordFixtureCheck(b.getRepriceNo(),revision,true,Map.of("complete",true));
    try(var pool=Executors.newFixedThreadPool(2)) {
      var a=pool.submit(()->{auth("COMMERCIAL","ROLE_BU_DIRECTOR");return publications.confirm(b.getRepriceNo(),ready.getCheckedRevision());});
      var c=pool.submit(()->{auth("COMMERCIAL","ROLE_BU_DIRECTOR");return publications.confirm(b.getRepriceNo(),ready.getCheckedRevision());});
      assertThat(a.get(10,TimeUnit.SECONDS).getConfirmedAt()).isEqualTo(c.get(10,TimeUnit.SECONDS).getConfirmedAt());
    }
    assertThatThrownBy(()->publications.confirm(b.getRepriceNo(),revision-1)).hasMessageContaining("当前检查");
  }
  @Test void failedRetryPreservesSuccessfulVersionAndFencesLateOldExecution() {
    var b=start(source("COMMERCIAL",80),source("COMMERCIAL",81));var tasks=mapper.tasks(b.getRepriceNo());
    var good=tasks.get(0);var bad=tasks.get(1);int goodRun=begin(b.getRepriceNo(),good.getId());
    long goodVersion=version(b,good);complete(b.getRepriceNo(),good.getId(),goodRun,goodVersion);
    int oldRun=begin(b.getRepriceNo(),bad.getId());fail(b.getRepriceNo(),bad.getId(),oldRun,"missing");
    retryFailed(b.getRepriceNo());int newRun=begin(b.getRepriceNo(),bad.getId());long id=version(b,bad);
    assertThat(newRun).isEqualTo(oldRun+1);
    assertThatThrownBy(()->complete(b.getRepriceNo(),bad.getId(),oldRun,id)).hasMessageContaining("轮次");
    complete(b.getRepriceNo(),bad.getId(),newRun,id);
    assertThat(mapper.tasks(b.getRepriceNo()).getFirst().getCostRunVersionId()).isEqualTo(goodVersion);
  }
  @Test void checkedSuccessfulSubsetCanBeConfirmedWhileFailedCustomerRemainsRetryable() {
    var b=start(source("COMMERCIAL",8101),source("COMMERCIAL",8102));String no=b.getRepriceNo();
    var tasks=mapper.tasks(no);var good=tasks.getFirst();var bad=tasks.getLast();
    int execution=begin(no,good.getId());complete(no,good.getId(),execution,version(b,good));
    int failure=begin(no,bad.getId());fail(no,bad.getId(),failure,"待补齐物料价格");
    var checking=service.recheck(no);
    var checked=recordFixtureCheck(no,checking.getResultRevision(),true,Map.of("fixture","checked subset contract"));
    var confirmed=publications.confirm(no,checked.getResultRevision());
    assertThat(confirmed.getConfirmedAt()).isNotNull();assertThat(control.blocked("COMMERCIAL")).isFalse();
    assertThat(uncalculated.page(MONTH.toString(),null,null,null,1,20).list()).singleElement().satisfies(row->{
      assertThat(row.id()).isEqualTo(bad.getId());assertThat(row.canRetry()).isTrue();
    });
  }
  @Test void releasedQuotationControlRemainsOpenDuringLaterRepairState() {
    var checked=ready(start(source("COMMERCIAL",8111)));publications.confirm(checked.getRepriceNo(),checked.getCheckedRevision());
    // State-only contract fixture: the subsequent repair must retain the already recorded release.
    jdbc.update("UPDATE lp_monthly_reprice_batch SET status='RUNNING' WHERE id=?",checked.getId());
    assertThat(control.blocked("COMMERCIAL")).isFalse();assertThat(service.blocksQuotation("COMMERCIAL")).isFalse();
    assertThat(jdbc.queryForObject("SELECT active_lock_key FROM lp_monthly_reprice_batch WHERE id=?",String.class,checked.getId())).isNull();
  }
  @Test void pendingClaimsAndLiveLeasesSurviveSuccessfulSubsetPublication() {
    var batch=start(source("COMMERCIAL",8131),source("COMMERCIAL",8132),source("COMMERCIAL",8133));
    String no=batch.getRepriceNo();var claims=dispatch.claimBatch("publication-live",2);
    var first=claims.getFirst();var live=claims.getLast();
    complete(no,first.getId(),first.getExecutionNo(),version(batch,first));
    var checking=service.recheck(no);
    var checked=recordFixtureCheck(no,checking.getResultRevision(),true,Map.of("fixture","live leases"));
    var released=publications.confirm(no,checked.getResultRevision());
    assertThat(control.blocked("COMMERCIAL")).isFalse();
    var remaining=dispatch.claim("publication-pending");assertThat(remaining).isNotNull();
    assertThat(remaining.getId()).isNotEqualTo(live.getId());
    complete(no,live.getId(),live.getExecutionNo(),version(batch,live));
    checking=service.recheck(no);
    checked=recordFixtureCheck(no,checking.getResultRevision(),true,Map.of("fixture","second subset"));
    publications.confirm(no,checked.getResultRevision());
    complete(no,remaining.getId(),remaining.getExecutionNo(),version(batch,remaining));
    assertThat(mapper.readBatch(no).getStatus()).isEqualTo("CHECKING");
    assertThat(mapper.readBatch(no).getConfirmedAt()).isEqualTo(released.getConfirmedAt());
    assertThat(control.blocked("COMMERCIAL")).isFalse();
    assertProgressMatchesTasks(no,3,0,100);
  }
  @Test void noSuccessfulCostCannotReleaseTheMonth() {
    var batch=start(source("COMMERCIAL",8141));var task=dispatch.claim("no-success");
    fail(batch.getRepriceNo(),task.getId(),task.getExecutionNo(),"missing input");
    var checking=service.recheck(batch.getRepriceNo());
    var checked=recordFixtureCheck(batch.getRepriceNo(),checking.getResultRevision(),true,Map.of("fixture","no success"));
    assertThatThrownBy(()->publications.confirm(batch.getRepriceNo(),checked.getResultRevision()))
        .hasMessageContaining("尚无可确认");
    assertThat(control.blocked("COMMERCIAL")).isTrue();
    assertThat(mapper.readBatch(batch.getRepriceNo()).getPublishedRevision()).isNull();
  }
  @Test void publicationCanLocateTasksByTheUniqueBatchObjectIndex() throws Exception {
    var batch=ready(start(source("COMMERCIAL",8146),source("COMMERCIAL",8147)));
    var plan=jdbc.queryForList("EXPLAIN FORMAT=TRADITIONAL SELECT COUNT(*) "+MonthlyRepricePublicationMapper.READY.replace("#{no}","?"),batch.getRepriceNo());
    var taskPlan=plan.stream().filter(row->"t".equals(row.get("table"))).findFirst().orElseThrow();
    String folder=System.getProperty("reprice.test.evidence");
    if(folder!=null)new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
        java.nio.file.Path.of(folder,"publication-query-plan.json").toFile(),plan);
    assertThat(Objects.toString(taskPlan.get("possible_keys"),"")).contains("uk_cost_run_task_batch_object");
  }
  @Test void confirmationCannotRunItsFullCheckInsideAnOuterTransaction() {
    var checked=ready(start(source("COMMERCIAL",8142)));
    assertThatThrownBy(()->tx.execute(status->publications.confirm(checked.getRepriceNo(),checked.getResultRevision())))
        .hasMessageContaining("业务事务");
    assertThat(control.blocked("COMMERCIAL")).isTrue();
  }
  @Test void freshConfirmationCheckDoesNotHoldQuotationMutexAndRejectsAChangedRevision() {
    var checked=ready(start(source("COMMERCIAL",8143)));String no=checked.getRepriceNo();
    var checker=org.mockito.Mockito.mock(MonthlyRepriceCheckService.class);
    org.mockito.Mockito.when(checker.verify(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
      assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      try(var pool=Executors.newSingleThreadExecutor()) {
        assertThat(pool.submit(()->control.serialized("COMMERCIAL",()->true)).get(2,TimeUnit.SECONDS)).isTrue();
      }
      var report=fixtureReport(call.getArgument(0));
      mapper.state(no,"RUNNING"); // A concurrent worker's committed progress invalidates the inspected revision.
      return report;
    });
    var publisher=new MonthlyRepricePublicationService(mapper,publicationQuery,checker,control,new ObjectMapper(),tx.getTransactionManager());
    assertThatThrownBy(()->publisher.confirm(no,checked.getResultRevision())).hasMessageContaining("当前检查");
    assertThat(mapper.readBatch(no).getConfirmedAt()).isNull();
    assertThat(mapper.results(no)).allSatisfy(row->assertThat(row.getPublishedCostVersionId()).isNull());
    assertThat(control.blocked("COMMERCIAL")).isTrue();
  }
  @Test void firstRepairOfLegacyConfirmedBatchPinsItsApprovedVersionsBeforeChangingTasks() {
    var batch=start(source("COMMERCIAL",8144),source("COMMERCIAL",8145));String no=batch.getRepriceNo();
    var first=dispatch.claim("legacy-success");long saved=version(batch,first);
    complete(no,first.getId(),first.getExecutionNo(),saved);
    var second=dispatch.claim("legacy-failed");fail(no,second.getId(),second.getExecutionNo(),"missing input");
    var checking=service.recheck(no);
    var checked=recordFixtureCheck(no,checking.getResultRevision(),true,Map.of("fixture","legacy release"));
    var released=publications.confirm(no,checked.getResultRevision());
    jdbc.update("UPDATE lp_monthly_reprice_result SET published_cost_version_id=NULL,published_source_cost_version_id=NULL WHERE reprice_no=?",no);
    jdbc.update("UPDATE lp_monthly_reprice_batch SET published_revision=NULL WHERE reprice_no=?",no);
    jdbc.update("UPDATE lp_quote_cost_run_version SET confirmed_at=NULL,confirmed_by=NULL,confirm_message='legacy batch confirmation' WHERE id=?",saved);
    var costBefore=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",saved);
    service.retry(no,new com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.RetrySelection(List.of(second.getId()),List.of()));
    var current=mapper.readBatch(no);
    assertThat(current.getPublishedRevision()).isEqualTo(checked.getResultRevision());
    assertThat(current.getConfirmedAt()).isEqualTo(released.getConfirmedAt());
    assertThat(mapper.results(no).getFirst().getPublishedCostVersionId()).isEqualTo(saved);
    assertThat(historicalResults.publishedResult(no,mapper.results(no).getFirst().getId()).getCostRunVersionId()).isEqualTo(saved);
    var repair=dispatch.claim("legacy-repair");complete(no,repair.getId(),repair.getExecutionNo(),version(batch,repair));
    var finished=mapper.readBatch(no);
    recordFixtureCheck(no,finished.getResultRevision(),true,Map.of("fixture","legacy repaired"));
    publications.confirm(no,finished.getResultRevision());
    assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",saved)).isEqualTo(costBefore);
    assertThat(control.blocked("COMMERCIAL")).isFalse();
  }
  @Test void aReleasedMonthDoesNotMoveBackwardsWhenItsUncalculatedRowsAreBeingRepaired() {
    var checked=ready(start(source("COMMERCIAL",8121)));publications.confirm(checked.getRepriceNo(),checked.getCheckedRevision());
    jdbc.update("UPDATE lp_monthly_reprice_batch SET status='RUNNING' WHERE id=?",checked.getId());
    assertThat(pricingPeriods.currentMonth("COMMERCIAL")).isEqualTo(MONTH.toString());
  }
  private static MonthlyRepriceBatch recordFixtureCheck(String no,long revision,boolean passed,Map<String,Object> evidence) {
    var batch=mapper.readBatch(no);
    return service.recordCheck(new MonthlyRepriceCheckService.Report(no,revision,batch.getTotalCount(),
        batch.getSuccessCount(),passed,passed?List.of():List.of("fixture check failure"),evidence));
  }
  private static MonthlyRepriceBatch checkingBatch() {
    var b=start(source("COMMERCIAL",991));var t=task(b);
    int execution=begin(b.getRepriceNo(),t.getId());complete(b.getRepriceNo(),t.getId(),execution,version(b,t));
    return mapper.readBatch(b.getRepriceNo());
  }
  @Test void realCheckPagesCoverEveryCustomerWithoutOffsetOrWholeBatchReads() throws Exception {
    var b=start(java.util.stream.LongStream.range(7000,7130).mapToObj(id->source("COMMERCIAL",id)).toArray(Source[]::new));
    var sizes=new ArrayList<Integer>();var ids=new HashSet<Long>();long after=0;
    while(true) {
      var page=checkQuery.tasks(b.getRepriceNo(),after);if(page.isEmpty())break;
      sizes.add(page.size());assertThat(page.size()).isLessThanOrEqualTo(64);
      page.forEach(t->assertThat(ids.add(t.getId())).isTrue());after=page.getLast().getId();
      assertThat(checkQuery.results(b.getRepriceNo(),page.stream().map(CostRunTask::getCalcObjectKey).toList()))
          .extracting(MonthlyRepriceResult::getCalcObjectKey)
          .containsExactlyInAnyOrderElementsOf(page.stream().map(CostRunTask::getCalcObjectKey).toList());
    }
    assertThat(sizes).containsExactly(64,64,2);assertThat(ids).hasSize(130);
    assertThat(checkQuery.counts(b.getRepriceNo())).isEqualTo(new MonthlyRepriceCheckMapper.Counts(130,130,0));
    assertThat(checkQuery.inconsistentSources(b.getId())).isZero();
    var plan=jdbc.queryForList("EXPLAIN FORMAT=TRADITIONAL SELECT * FROM lp_cost_run_task WHERE source_no=? AND scene='MONTHLY_REPRICE' AND id>? ORDER BY id LIMIT 64",b.getRepriceNo(),ids.stream().sorted().skip(63).findFirst().orElseThrow());
    assertThat(plan).allSatisfy(row->assertThat(row.get("key")).isNotNull());
    String dir=System.getProperty("reprice.test.evidence");
    if(dir!=null)new ObjectMapper().writeValue(java.nio.file.Path.of(dir,"check-keyset-pages.json").toFile(),Map.of("objects",130,"pages",sizes,"explain",plan));
  }
  @Test void knownMissingCustomerSourceIsValidButMissingEvidenceIsNot() {
    customerSource(7131,null,"P","2026-01-01 00:00:00");customerSource(7132,"known","P","2026-01-01 00:00:00");
    var b=service.enqueue("COMMERCIAL",MONTH);
    assertThat(b.getUnresolvedSourceCount()).isEqualTo(1);
    assertThat(checkQuery.inconsistentSources(b.getId())).isZero();
    jdbc.update("UPDATE lp_monthly_reprice_source SET source_snapshot_json=NULL WHERE batch_id=? AND object_id IS NULL",b.getId());
    assertThat(checkQuery.inconsistentSources(b.getId())).isEqualTo(1);
  }
  @Test void uncalculatedPageKeepsSeparateUnknownSourcesAndReturnsLiteralFiltersAndAllMaterialReasons() throws Exception {
    customerSource(7141,null,"P-001","2026-01-01 00:00:00");
    customerSource(7142,"customer_001%","P-001","2026-01-02 00:00:00");
    customerSource(7143,"customer_001%","P-001","2026-01-03 00:00:00");
    var b=service.enqueue("COMMERCIAL",MONTH);
    var pending=uncalculated.page(MONTH.toString(),null,null,null,1,20);
    assertThat(pending.total()).isEqualTo(2);
    assertThat(pending.statistics()).isEqualTo(new com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.Statistics(3,1,0,0,0,0,0,1,1));
    assertThat(pending.list()).extracting(r->r.kind()).containsExactly("SOURCE","TASK");
    assertThat(pending.list().getFirst().customer()).isNull();
    assertThat(pending.list().getFirst().canRetry()).isTrue();
    assertThat(uncalculated.page(MONTH.toString(),"%",null,null,1,20).total()).isEqualTo(1);
    assertThat(uncalculated.page(MONTH.toString(),"customer_001%",null,null,1,1).list()).singleElement()
        .satisfies(row->assertThat(row.sourceCount()).isEqualTo(2));
    var task=task(b); // Unit-contract failure fixture; the worker path is exercised in separate cases.
    var issues=java.util.stream.IntStream.range(0,300).mapToObj(i->new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult.Issue("00"+i,"part","NO_PRICE","缺少适用价格")).toList();
    jdbc.update("UPDATE lp_cost_run_task SET status='FAILED',error_message='缺少物料价格',result_summary_json=?,finished_at=NOW() WHERE id=?",
        new ObjectMapper().writeValueAsString(Map.of("failure",Map.of("code","MISSING_INPUT","issues",issues))),task.getId());
    jdbc.update("UPDATE lp_monthly_reprice_batch SET failed_count=1 WHERE id=?",b.getId());
    var page=uncalculated.page(MONTH.toString(),null,"P-001","FAILED",1,20);
    assertThat(page.list()).singleElement().satisfies(row->{
      assertThat(row.materials()).containsExactlyElementsOf(issues);assertThat(row.reasonCode()).isEqualTo("MISSING_INPUT");
      assertThat(row.canRetry()).isTrue();assertThat(row.lastAttemptAt()).isNotNull();
    });
    auth("HOUSEHOLD","ROLE_BU_DIRECTOR");
    assertThat(uncalculated.page(MONTH.toString(),null,null,null,1,20).total()).isZero();
    auth("COMMERCIAL","ROLE_SALES");
    assertThat(uncalculated.page(MONTH.toString(),null,null,null,1,20).total()).isZero();
    assertThatThrownBy(()->uncalculated.page("2026-13",null,null,null,1,20)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->uncalculated.page("2026-01",null,null,null,1,201)).isInstanceOf(IllegalArgumentException.class);
  }
  private static MonthlyRepriceCheckService.Report fixtureReport(MonthlyRepriceBatch batch) {
    return new MonthlyRepriceCheckService.Report(batch.getRepriceNo(),batch.getResultRevision(),batch.getTotalCount(),
        batch.getSuccessCount(),true,List.of(),Map.of("fixture","coordinator contract only"));
  }
  @Test void selectedRetryLeavesUnselectedFailedPendingAndRunningCustomersUnchanged() {
    for(int i=0;i<4;i++)customerSource(7151+i,"customer-"+i,"P","2026-01-01 00:00:00");
    var b=service.enqueue("COMMERCIAL",MONTH);String no=b.getRepriceNo();
    var first=dispatch.claim("selected-first");var otherFailed=dispatch.claim("other-failed");var running=dispatch.claim("still-running");
    fail(no,first.getId(),first.getExecutionNo(),"first missing input");
    fail(no,otherFailed.getId(),otherFailed.getExecutionNo(),"other missing input");
    var protectedRows=jdbc.queryForList("SELECT * FROM lp_cost_run_task WHERE source_no=? AND id<>? ORDER BY id",no,first.getId());
    var selection=new com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.RetrySelection(List.of(first.getId()),List.of());
    var retry=service.retry(no,selection);
    assertThat(retry.getFailedCount()).isEqualTo(1);
    assertThat(mapper.readTask(no,first.getId()).getStatus()).isEqualTo("PENDING");
    var retryEvidence=jdbc.queryForMap("SELECT result_summary_json,JSON_UNQUOTE(JSON_EXTRACT(result_summary_json,'$.lastAttemptAt')) saved_attempt FROM lp_cost_run_task WHERE id=?",first.getId());
    assertThat(uncalculated.page(MONTH.toString(),null,null,"PENDING",1,20).list()).filteredOn(r->r.id()==first.getId())
        .singleElement().satisfies(row->assertThat(row.lastAttemptAt()).as("stored attempt %s",retryEvidence).isNotNull());
    assertThat(jdbc.queryForList("SELECT * FROM lp_cost_run_task WHERE source_no=? AND id<>? ORDER BY id",no,first.getId()))
        .usingRecursiveComparison().isEqualTo(protectedRows);
    var once=jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",first.getId());
    assertThat(service.retry(no,selection).getResultRevision()).isEqualTo(retry.getResultRevision());
    assertThat(jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",first.getId())).usingRecursiveComparison().isEqualTo(once);
    assertThatThrownBy(()->service.retry(no,new com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.RetrySelection(List.of(otherFailed.getId(),Long.MAX_VALUE),List.of())))
        .hasMessageContaining("不属于");
    assertThat(mapper.readTask(no,otherFailed.getId()).getStatus()).isEqualTo("FAILED");
    auth("HOUSEHOLD","ROLE_BU_DIRECTOR");assertThatThrownBy(()->service.retry(no,selection)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }
  @Test void selectedCustomerRepairKeepsSuccessfulObjectsAndUnselectedUnknownSources() {
    customerSource(7161,"known","P","2026-01-20 00:00:00");
    customerSource(7162,null,"P","2026-01-01 00:00:00");
    customerSource(7163,null,"P","2026-01-01 00:00:00");
    customerSource(7164,null,"P","2026-01-01 00:00:00");
    var b=service.enqueue("COMMERCIAL",MONTH);String no=b.getRepriceNo();var known=dispatch.claim("known-first");
    long cost=version(b,known);complete(no,known.getId(),known.getExecutionNo(),cost);
    var taskBefore=jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",known.getId());
    var versionBefore=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",cost);
    var untouched=jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_source WHERE oa_form_item_id=7164");
    jdbc.update("UPDATE oa_form SET customer='known' WHERE id=17162");
    jdbc.update("UPDATE oa_form SET customer='new' WHERE id=17163");
    var ids=jdbc.queryForList("SELECT id FROM lp_monthly_reprice_source WHERE oa_form_item_id IN (7162,7163) ORDER BY id",Long.class);
    var result=service.retry(no,new com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.RetrySelection(List.of(),ids));
    assertThat(result.getSourceCount()).isEqualTo(4);assertThat(result.getTotalCount()).isEqualTo(2);
    assertThat(result.getUnresolvedSourceCount()).isEqualTo(1);assertThat(result.getSuccessCount()).isEqualTo(1);
    assertThat(jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",known.getId())).usingRecursiveComparison().isEqualTo(taskBefore);
    assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",cost)).usingRecursiveComparison().isEqualTo(versionBefore);
    assertThat(jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_source WHERE oa_form_item_id=7164")).isEqualTo(untouched);
    assertThat(checkQuery.inconsistentSources(b.getId())).isZero();
    assertThat(dispatch.claim("new-customer-can-proceed")).isNotNull();
    var selection=new com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.RetrySelection(List.of(),List.of(((Number)untouched.get("id")).longValue()));
    service.retry(no,selection);
    var pending=uncalculated.page(MONTH.toString(),null,null,"WAIT_CUSTOMER",1,20);
    assertThat(pending.list()).singleElement().satisfies(row->{
      assertThat(row.retryCount()).isEqualTo(1);assertThat(row.lastAttemptAt()).isNotNull();assertThat(row.reason()).contains("仍缺少客户");
    });
  }
  @Test void latestRecoveredOaWaitsForItsRunningCustomerThenUpdatesOnlyThatCustomerAndRetainsSavedCosts() throws Exception {
    customerSource(7171,"known","P","2026-01-01 00:00:00");
    var newer=customerSource(7172,null,"P","2026-01-20 00:00:00");
    var b=service.enqueue("COMMERCIAL",MONTH);String no=b.getRepriceNo();var known=dispatch.claim("running-customer");
    jdbc.update("UPDATE oa_form SET customer='known' WHERE id=17172");
    var selection=new com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.RetrySelection(List.of(),jdbc.queryForList("SELECT id FROM lp_monthly_reprice_source WHERE object_id IS NULL",Long.class));
    service.retry(no,selection);
    assertThat(mapper.readBatch(no).getUnresolvedSourceCount()).isEqualTo(1);
    assertThat(uncalculated.page(MONTH.toString(),null,null,"WAIT_CUSTOMER",1,20).list()).singleElement()
        .satisfies(row->assertThat(row.reason()).contains("正在核算"));
    long cost=version(b,known);complete(no,known.getId(),known.getExecutionNo(),cost);
    var oldCost=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",cost);
    var ids=jdbc.queryForList("SELECT id FROM lp_monthly_reprice_source ORDER BY id",Long.class);
    var result=service.retry(no,selection);
    assertThat(result.getTotalCount()).isEqualTo(1);assertThat(result.getSourceCount()).isEqualTo(2);
    assertThat(result.getSuccessCount()).isZero();assertThat(result.getUnresolvedSourceCount()).isZero();
    var task=mapper.readTask(no,known.getId());assertThat(task.getStatus()).isEqualTo("PENDING");
    assertThat(task.getExecutionNo()).isEqualTo(known.getExecutionNo()+1);
    assertThat(new ObjectMapper().readTree(task.getRequestSnapshotJson()).path("sourceCostVersionId").asLong()).isEqualTo(newer.sourceCostVersionId());
    assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",cost)).usingRecursiveComparison().isEqualTo(oldCost);
    assertThat(jdbc.queryForList("SELECT id FROM lp_monthly_reprice_source ORDER BY id",Long.class)).isEqualTo(ids);
    assertThat(checkQuery.inconsistentSources(b.getId())).isZero();
  }
  @Test void independentCheckersDoNotDuplicateWorkOrHoldTheBatchRowWhileReading() throws Exception {
    var b=checkingBatch();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    var checks=org.mockito.Mockito.mock(MonthlyRepriceCheckService.class);
    org.mockito.Mockito.when(checks.verify(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
      entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return fixtureReport(call.getArgument(0));
    });
    var first=new MonthlyRepriceCheckCoordinator(jdbc.getDataSource(),mapper,checks,service);
    var second=new MonthlyRepriceCheckCoordinator(jdbc.getDataSource(),mapper,checks,service);
    try(var pool=Executors.newFixedThreadPool(2)) {
      var running=pool.submit(()->first.check(b.getRepriceNo()));
      try {
        assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
        assertThat(pool.submit(()->second.check(b.getRepriceNo())).get(2,TimeUnit.SECONDS)).isFalse();
        assertThat(pool.submit(()->tx.execute(status->mapper.lock(b.getRepriceNo()).getStatus())).get(2,TimeUnit.SECONDS)).isEqualTo("CHECKING");
      } finally {release.countDown();}
      assertThat(running.get(3,TimeUnit.SECONDS)).isTrue();
    }
    assertThat(second.check(b.getRepriceNo())).isFalse();
    org.mockito.Mockito.verify(checks,org.mockito.Mockito.times(1)).verify(org.mockito.ArgumentMatchers.any());
    assertThat(mapper.readBatch(b.getRepriceNo()).getCheckedRevision()).isEqualTo(b.getResultRevision());
  }
  @Test void resultChangesDuringCheckingDiscardTheOldReportAndAllowTheNewRevision() throws Exception {
    var b=checkingBatch();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    var checks=org.mockito.Mockito.mock(MonthlyRepriceCheckService.class);
    org.mockito.Mockito.when(checks.verify(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
      entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return fixtureReport(call.getArgument(0));
    });
    var coordinator=new MonthlyRepriceCheckCoordinator(jdbc.getDataSource(),mapper,checks,service);
    try(var pool=Executors.newSingleThreadExecutor()) {
      var running=pool.submit(()->coordinator.check(b.getRepriceNo()));
      try {
        assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
        tx.executeWithoutResult(status->{mapper.lock(b.getRepriceNo());mapper.state(b.getRepriceNo(),"CHECKING");});
      } finally {release.countDown();}
      assertThat(running.get(3,TimeUnit.SECONDS)).isFalse();
    }
    assertThat(mapper.readBatch(b.getRepriceNo()).getCheckedRevision()).isNull();
    assertThatThrownBy(()->service.recordCheck(fixtureReport(b))).hasMessageContaining("已变化");
    assertThat(coordinator.check(b.getRepriceNo())).isTrue();
    assertThat(mapper.readBatch(b.getRepriceNo()).getCheckedRevision()).isEqualTo(b.getResultRevision()+1);
  }
  @Test void checkerFailureReleasesMutexAndNeverPublishesAPartialReport() {
    var b=checkingBatch();var checks=org.mockito.Mockito.mock(MonthlyRepriceCheckService.class);
    org.mockito.Mockito.when(checks.verify(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("controlled read failure"))
        .thenAnswer(call->fixtureReport(call.getArgument(0)));
    var coordinator=new MonthlyRepriceCheckCoordinator(jdbc.getDataSource(),mapper,checks,service);
    assertThatThrownBy(()->coordinator.check(b.getRepriceNo())).hasMessageContaining("controlled read failure");
    assertThat(mapper.readBatch(b.getRepriceNo()).getCheckedRevision()).isNull();
    assertThat(new MonthlyRepriceCheckCoordinator(jdbc.getDataSource(),mapper,checks,service).check(b.getRepriceNo())).isTrue();
  }
  @Test void fullCheckRejectsAnOuterBusinessTransaction() {
    var b=checkingBatch();var checks=org.mockito.Mockito.mock(MonthlyRepriceCheckService.class);
    var coordinator=new MonthlyRepriceCheckCoordinator(jdbc.getDataSource(),mapper,checks,service);
    assertThatThrownBy(()->tx.execute(status->coordinator.check(b.getRepriceNo()))).hasMessageContaining("业务事务");
    org.mockito.Mockito.verifyNoInteractions(checks);
  }
  @Test void checkPublicationRejectsCountsFromAnotherResultSet() {
    var b=checkingBatch();
    var report=new MonthlyRepriceCheckService.Report(b.getRepriceNo(),b.getResultRevision(),2,1,true,List.of(),Map.of("fixture",true));
    assertThatThrownBy(()->service.recordCheck(report)).hasMessageContaining("检查对象");
    assertThat(mapper.readBatch(b.getRepriceNo()).getCheckedRevision()).isNull();
  }
  @Test void staleCheckOrChangedSavedVersionCannotBeConfirmed() {
    var b=ready(start(source("COMMERCIAL",90)));
    assertThatThrownBy(()->recordFixtureCheck(b.getRepriceNo(),b.getResultRevision()-1,true,Map.of("stale",true))).hasMessageContaining("已变化");
    jdbc.update("UPDATE lp_quote_cost_run_version SET final_quote_amount=99 WHERE id=?",task(b).getCostRunVersionId());
    assertThatThrownBy(()->publications.confirm(b.getRepriceNo(),b.getCheckedRevision())).hasMessageContaining("Stored total2");
    assertThat(service.blocksQuotation("COMMERCIAL")).isTrue();
  }
  @Test void failedCheckCanBeRetriedWithoutRecalculatingSuccessfulTasks() {
    var b=start(source("COMMERCIAL",91));var t=task(b);int run=begin(b.getRepriceNo(),t.getId());long id=version(b,t);complete(b.getRepriceNo(),t.getId(),run,id);
    var checking=mapper.lock(b.getRepriceNo());recordFixtureCheck(b.getRepriceNo(),checking.getResultRevision(),false,Map.of("issue","business check"));
    var retry=retryFailed(b.getRepriceNo());assertThat(retry.getStatus()).isEqualTo("CHECKING");
    assertThat(task(b).getCostRunVersionId()).isEqualTo(id);
    assertThat(retry.getCheckedRevision()).isNull();
  }
  @Test void genericPermissionsAndOtherUnitCannotStartOrConfirm() {
    var source=source("COMMERCIAL",100);
    for(String role:List.of("*:*:*","price:monthly-reprice:operate","ROLE_BU_STAFF")) {
      auth("COMMERCIAL",role);assertThatThrownBy(()->start(source)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    auth("COMMERCIAL","ROLE_BU_DIRECTOR");var b=ready(start(source));
    auth("HOUSEHOLD","ROLE_BU_DIRECTOR");assertThatThrownBy(()->publications.confirm(b.getRepriceNo(),b.getCheckedRevision())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    auth("COMMERCIAL","*:*:*");assertThatThrownBy(()->publications.confirm(b.getRepriceNo(),b.getCheckedRevision())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"ROLE_ADMIN","ROLE_admin","ROLE_BU_DIRECTOR","ROLE_bu_director"})
  void adminAndDirectorCanStartRetryAndConfirmWithinTheirCurrentBusinessUnit(String role) {
    var input=source("COMMERCIAL",101);
    auth("COMMERCIAL",role);var batch=start(input);var task=task(batch);
    assertThat(service.blocksQuotation("COMMERCIAL")).isTrue();
    int execution=begin(batch.getRepriceNo(),task.getId());
    fail(batch.getRepriceNo(),task.getId(),execution,"controlled missing input");
    var checked=ready(retryFailed(batch.getRepriceNo()));
    auth("HOUSEHOLD",role);
    assertThatThrownBy(()->retryFailed(checked.getRepriceNo())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(()->publications.confirm(checked.getRepriceNo(),checked.getCheckedRevision())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(service.blocksQuotation("COMMERCIAL")).isTrue();
    auth("COMMERCIAL",role);
    assertThat(publications.confirm(checked.getRepriceNo(),checked.getCheckedRevision()).getStatus()).isEqualTo("CONFIRMED");
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
    assertThatThrownBy(()->publications.confirm(b.getRepriceNo(),b.getCheckedRevision())).hasMessageContaining("fixture fixed sources incomplete");
    assertThat(service.blocksQuotation("COMMERCIAL")).isTrue();
  }
  @Test void pendingOrRunningBatchCannotBeConfirmedOrCheckedAndMissingTaskCannotWrite() {
    var b=start(source("COMMERCIAL",133));
    assertThatThrownBy(()->publications.confirm(b.getRepriceNo(),0)).hasMessageContaining("当前检查");
    assertThatThrownBy(()->recordFixtureCheck(b.getRepriceNo(),0,true,Map.of("complete",true))).hasMessageContaining("已变化");
    assertThatThrownBy(()->service.complete(b.getRepriceNo(),9999999,1,"not-owner",1)).hasMessageContaining("不属于");
    var t=task(b);begin(b.getRepriceNo(),t.getId());
    assertThat(dispatch.claim("duplicate-claim")).isNull();
    assertThat(service.blocksQuotation("COMMERCIAL")).isTrue();
  }
  @Test void eightWorkersClaimEveryTaskOnceAcrossIndependentTransactions() throws Exception {
    // This existing uniqueness test deliberately holds all32 leases without executing them.
    // The separate default-budget tests cover bounded production admission and lease recovery.
    capacity.setMaxActive(32);capacity.setReservedConnections(0);
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

  private static Source customerSource(long item,String customer,String product,String finished) {
    var source=dated(item,finished,"SUCCESS");
    String oa="CUSTOMER-OA-"+item;
    jdbc.update("INSERT INTO oa_form(id,oa_no,business_unit_type,customer) VALUES(?,?,'COMMERCIAL',?)",item+10000,oa,customer);
    jdbc.update("UPDATE oa_form_item SET oa_form_id=?,material_no=?,customer_code='CLIENT-PART-NUMBER' WHERE id=?",item+10000,product,item);
    jdbc.update("UPDATE lp_quote_cost_run_version SET oa_no=?,product_code=? WHERE id=?",oa,product,source.sourceCostVersionId());
    return new Source(new ObjectIdentity("COMMERCIAL",oa,item),source.sourceCostVersionId());
  }

  @Test void customerProductScopeSelectsLatestWholeSourceAndPreservesEveryOa() throws Exception {
    var a=customerSource(6001,"001","P-001","2025-12-20 12:00:00");
    var b=customerSource(6002,"001","P-001","2026-01-20 12:00:00");
    var c=customerSource(6003,"002","P-001","2026-01-15 12:00:00");
    jdbc.update("UPDATE oa_form_item SET package_method=CASE id WHEN 6001 THEN 'BOX-A' WHEN 6002 THEN 'BOX-B' ELSE 'BOX-C' END");
    var original=jdbc.queryForList("SELECT * FROM lp_quote_cost_run_version ORDER BY id");
    var batch=service.enqueue("COMMERCIAL",MONTH);
    assertThat(batch.getSourceCount()).isEqualTo(3);
    assertThat(batch.getTotalCount()).isEqualTo(2);
    assertThat(batch.getUnresolvedSourceCount()).isZero();
    assertThat(mapper.results(batch.getRepriceNo())).extracting(MonthlyRepriceResult::getSourceCostVersionId)
        .containsExactlyInAnyOrder(b.sourceCostVersionId(),c.sourceCostVersionId());
    var merged=jdbc.queryForMap("SELECT id,merged_source_count FROM lp_monthly_reprice_result WHERE reuse_batch_id=? AND customer_identity_value='001'",batch.getId());
    assertThat(((Number)merged.get("merged_source_count")).intValue()).isEqualTo(2);
    assertThat(jdbc.queryForList("SELECT source_cost_version_id FROM lp_monthly_reprice_source WHERE object_id=? ORDER BY source_cost_version_id",Long.class,merged.get("id")))
        .containsExactly(a.sourceCostVersionId(),b.sourceCostVersionId());
    var selected=mapper.tasks(batch.getRepriceNo()).stream().filter(t -> t.getOaFormItemId()==6002).findFirst().orElseThrow();
    var request=new ObjectMapper().readTree(selected.getRequestSnapshotJson());
    assertThat(request.path("selectedInput").path("item").path("packageMethod").asText()).isEqualTo("BOX-B");
    assertThat(request.path("customerIdentity").path("customerValue").asText()).isEqualTo("001");
    assertThat(jdbc.queryForList("SELECT * FROM lp_quote_cost_run_version ORDER BY id")).isEqualTo(original);
  }

  @Test void newReadsUseSavedCustomerAndAllSourcesAfterLiveOaChanges() {
    var a = customerSource(6101,"001","P-001","2025-12-20 12:00:00");
    var b = customerSource(6102,"001","P-001","2026-01-20 12:00:00");
    var c = customerSource(6103,"002","P-001","2026-01-15 12:00:00");
    jdbc.update("UPDATE oa_form_item SET package_method='BOX-B' WHERE id=6102");
    var batch = service.enqueue("COMMERCIAL",MONTH);
    jdbc.update("UPDATE oa_form SET customer='CHANGED'");
    jdbc.update("UPDATE oa_form_item SET package_method='CHANGED'");
    var before = jdbc.queryForList("SELECT * FROM lp_monthly_reprice_source ORDER BY id");
    var query = queryService();
    var request = new com.sanhua.marketingcost.dto.MonthlyRepriceResultQueryRequest();
    request.setCustomerName("001"); request.setPageSize(1);
    var page = query.pageResults(batch.getRepriceNo(),request);
    assertThat(page.getTotal()).isEqualTo(1);
    var row = page.getList().getFirst();
    assertThat(row.getScopeVersion()).isEqualTo(1);
    assertThat(row.getCustomerIdentitySource()).isEqualTo("OA_HEADER_CUSTOMER");
    assertThat(row.getCustomerIdentityValue()).isEqualTo("001");
    assertThat(row.getCustomerName()).isEqualTo("001");
    assertThat(row.getPackageMethod()).isEqualTo("BOX-B");
    assertThat(row.getMergedSourceCount()).isEqualTo(2);
    assertThat(row.getSourceCostVersionId()).isEqualTo(b.sourceCostVersionId());
    assertThat(mapper.tasks(batch.getRepriceNo()).stream().filter(t -> t.getOaFormItemId()==6102).findFirst().orElseThrow().getCustomerName())
        .isEqualTo("001");
    var first = query.pageSources(batch.getRepriceNo(),row.getId(),1,1);
    var second = query.pageSources(batch.getRepriceNo(),row.getId(),2,1);
    assertThat(first.getTotal()).isEqualTo(2);
    assertThat(first.getList()).extracting(com.sanhua.marketingcost.dto.MonthlyRepriceSourceDto::sourceCostVersionId)
        .containsExactly(a.sourceCostVersionId());
    assertThat(first.getList().getFirst().selected()).isFalse();
    assertThat(second.getList().getFirst().sourceCostVersionId()).isEqualTo(b.sourceCostVersionId());
    assertThat(second.getList().getFirst().selected()).isTrue();
    var other = mapper.results(batch.getRepriceNo()).stream().filter(r -> r.getSourceCostVersionId().equals(c.sourceCostVersionId())).findFirst().orElseThrow();
    assertThat(query.pageSources(batch.getRepriceNo(),other.getId(),1,20).getTotal()).isEqualTo(1);
    assertThatThrownBy(() -> query.pageSources("LEGACY",row.getId(),1,20)).hasMessageContaining("不存在");
    var counts = query.getBatch(batch.getRepriceNo());
    assertThat(counts.getScopeVersion()).isEqualTo(1);
    assertThat(counts.getSourceCount()).isEqualTo(3);
    assertThat(counts.getTotalCount()).isEqualTo(2);
    assertThat(counts.getUnresolvedSourceCount()).isZero();
    assertThat(jdbc.queryForList("SELECT * FROM lp_monthly_reprice_source ORDER BY id")).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Integer.class)).isZero();
    assertThat(historicalResults.confirmedForSource("COMMERCIAL",a.object().oaNo(),6101L,"P-001")).isEmpty();
    var checked=ready(batch);
    publications.confirm(checked.getRepriceNo(),checked.getCheckedRevision());
    assertThat(historicalResults.confirmedForSource("COMMERCIAL",a.object().oaNo(),6101L,"P-001"))
        .extracting(MonthlyRepriceResult::getId).containsExactly(row.getId());
    assertThat(historicalResults.confirmedForSource("COMMERCIAL",b.object().oaNo(),6102L,"P-001"))
        .extracting(MonthlyRepriceResult::getId).containsExactly(row.getId());
    assertThat(historicalResults.confirmedForSource("HOUSEHOLD",a.object().oaNo(),6101L,"P-001")).isEmpty();
    assertThat(historicalResults.confirmedForSource("COMMERCIAL",a.object().oaNo().toLowerCase(),6101L,"P-001")).isEmpty();
    assertThat(historicalResults.sourceMembership(batch.getId(),row.getId(),c.object().oaNo(),6103L)).isZero();
    assertThat(historicalResults.sourceMembership(batch.getId(),row.getId(),a.object().oaNo(),6101L)).isEqualTo(1);
  }

  @Test void legacyReadKeepsOriginalValuesAndNoInferredCustomerScope() {
    var before = jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_result WHERE id=1");
    var query = queryService();
    var old = query.pageResults("LEGACY",null).getList().getFirst();
    assertThat(old.getTotalCost()).isEqualByComparingTo("12.123456");
    assertThat(old.getOaNo()).isEqualTo("OLD");
    assertThat(old.getProductCode()).isEqualTo("0001");
    assertThat(old.getScopeVersion()).isNull();
    assertThat(old.getCustomerIdentitySource()).isNull();
    assertThat(old.getCustomerIdentityValue()).isNull();
    assertThat(old.getMergedSourceCount()).isNull();
    assertThat(query.getBatch("LEGACY").getSourceCount()).isNull();
    assertThat(query.pageSources("LEGACY",1L,1,20).getList()).isEmpty();
    assertThat(jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_result WHERE id=1")).isEqualTo(before);
    for (var entry:oldResult.entrySet()) assertThat(before.get(entry.getKey())).isEqualTo(entry.getValue());
  }

  @Test void sourcePaginationReturnsEveryMergedOaOnceWithARealDatabaseLimit() {
    for (int i=0;i<205;i++) customerSource(8000+i,"001","P","2026-01-01 00:00:00");
    var batch=service.enqueue("COMMERCIAL",MONTH);
    var object=mapper.results(batch.getRepriceNo()).getFirst();
    var query=queryService();
    var first=query.pageSources(batch.getRepriceNo(),object.getId(),1,10000);
    var second=query.pageSources(batch.getRepriceNo(),object.getId(),2,200);
    assertThat(first.getTotal()).isEqualTo(205);
    assertThat(first.getList()).hasSize(200);
    assertThat(second.getList()).hasSize(5);
    var all=new ArrayList<>(first.getList());all.addAll(second.getList());
    assertThat(all).extracting(com.sanhua.marketingcost.dto.MonthlyRepriceSourceDto::id).doesNotHaveDuplicates();
    assertThat(all.stream().filter(com.sanhua.marketingcost.dto.MonthlyRepriceSourceDto::selected)).hasSize(1);
    var plan=jdbc.queryForMap("EXPLAIN FORMAT=TRADITIONAL SELECT object_id FROM lp_monthly_reprice_source WHERE oa_no='CUSTOMER-OA-8000' AND oa_form_item_id=8000");
    assertThat(plan.get("key")).isEqualTo("idx_reprice_source_oa");
  }

  private static com.sanhua.marketingcost.service.impl.MonthlyRepriceQueryServiceImpl queryService() {
    return new com.sanhua.marketingcost.service.impl.MonthlyRepriceQueryServiceImpl(history,
        org.mockito.Mockito.mock(CostRunTaskMapper.class),historicalResults,
        org.mockito.Mockito.mock(MonthlyRepricePartItemMapper.class),org.mockito.Mockito.mock(MonthlyRepriceCostItemMapper.class),
        org.mockito.Mockito.mock(MonthlyRepriceAuditLogMapper.class),new com.sanhua.marketingcost.security.PermissionService(),
        org.mockito.Mockito.mock(MonthlyCostVersionService.class));
  }

  @Test void equalCompletionTimesUseGreaterVersionAndDuplicateSubmissionKeepsFixedScope() {
    var a=customerSource(6011,"Customer","P","2026-01-01 00:00:00");
    var b=customerSource(6012,"Customer","P","2026-01-01 00:00:00");
    var batch=service.enqueue("COMMERCIAL",MONTH);
    var before=jdbc.queryForList("SELECT * FROM lp_monthly_reprice_source ORDER BY id");
    var objects=jdbc.queryForList("SELECT * FROM lp_monthly_reprice_result WHERE reuse_batch_id=?",batch.getId());
    customerSource(6013,"Customer","P","2026-01-31 00:00:00");
    jdbc.update("UPDATE oa_form SET customer='CHANGED'");
    assertThat(service.enqueue("COMMERCIAL",MONTH).getId()).isEqualTo(batch.getId());
    assertThat(mapper.results(batch.getRepriceNo())).singleElement().extracting(MonthlyRepriceResult::getSourceCostVersionId).isEqualTo(b.sourceCostVersionId());
    assertThat(jdbc.queryForList("SELECT * FROM lp_monthly_reprice_source ORDER BY id")).isEqualTo(before);
    assertThat(jdbc.queryForList("SELECT * FROM lp_monthly_reprice_result WHERE reuse_batch_id=?",batch.getId())).usingRecursiveComparison().isEqualTo(objects);
  }

  @Test void absentCustomerDoesNotBlockKnownCustomerAndRepairUsesOnlyFrozenSources() throws Exception {
    var known=customerSource(6021,"001","P","2026-01-01 00:00:00");
    var missing=customerSource(6022,null,"P","2026-01-02 00:00:00");
    var batch=service.enqueue("COMMERCIAL",MONTH);
    assertThat(batch.getStatus()).isEqualTo("FAILED");
    assertThat(batch.getSourceCount()).isEqualTo(2);
    assertThat(batch.getUnresolvedSourceCount()).isEqualTo(1);
    var knownTask=dispatch.claim("known-can-proceed");assertThat(knownTask).isNotNull();
    fail(batch.getRepriceNo(),knownTask.getId(),knownTask.getExecutionNo(),"fixture input gap");
    var before=jdbc.queryForList("SELECT * FROM lp_monthly_reprice_source ORDER BY id");
    assertThat(service.retry(batch.getRepriceNo(),new com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.RetrySelection(
        List.of(),jdbc.queryForList("SELECT id FROM lp_monthly_reprice_source WHERE object_id IS NULL",Long.class))).getStatus()).isEqualTo("FAILED");
    assertThat(jdbc.queryForList("SELECT * FROM lp_monthly_reprice_source ORDER BY id")).isEqualTo(before);
    var ids=jdbc.queryForList("SELECT id FROM lp_monthly_reprice_source ORDER BY id",Long.class);
    customerSource(6023,"001","P","2026-01-20 00:00:00");
    // Only the missing customer's identity can change; the known source stays the accepted customer.
    jdbc.update("UPDATE oa_form SET customer='OTHER' WHERE id=16021");
    jdbc.update("UPDATE oa_form SET customer='001' WHERE id=16022");
    jdbc.update("UPDATE oa_form_item SET package_method='AFTER_ACCEPT' WHERE id=6022");
    var repaired=retryFailed(batch.getRepriceNo());
    assertThat(repaired.getStatus()).isEqualTo("RUNNING");
    assertThat(repaired.getSourceCount()).isEqualTo(2);
    assertThat(repaired.getTotalCount()).isEqualTo(1);
    assertThat(repaired.getUnresolvedSourceCount()).isZero();
    assertThat(jdbc.queryForList("SELECT id FROM lp_monthly_reprice_source ORDER BY id",Long.class)).isEqualTo(ids);
    assertThat(jdbc.queryForList("SELECT source_cost_version_id FROM lp_monthly_reprice_source ORDER BY source_cost_version_id",Long.class))
        .containsExactly(known.sourceCostVersionId(),missing.sourceCostVersionId());
    var request=new ObjectMapper().readTree(task(repaired).getRequestSnapshotJson());
    assertThat(request.path("sourceCostVersionId").asLong()).isEqualTo(missing.sourceCostVersionId());
    assertThat(request.path("selectedInput").path("item").path("packageMethod").isNull()).isTrue();
    assertThat(dispatch.claim("after-customer-repair")).isNotNull();
  }

  @Test void allMissingCustomersAreIndividuallyTraceableAndNeverAnEmptySuccessfulBatch() {
    customerSource(6031,null,"P","2026-01-01 00:00:00");
    customerSource(6032," / ","P","2026-01-01 00:00:00");
    var batch=service.enqueue("COMMERCIAL",MONTH);
    assertThat(batch.getStatus()).isEqualTo("FAILED");
    assertThat(batch.getSourceCount()).isEqualTo(2);
    assertThat(batch.getTotalCount()).isZero();
    assertThat(batch.getUnresolvedSourceCount()).isEqualTo(2);
    assertThat(retryFailed(batch.getRepriceNo()).getStatus()).isEqualTo("FAILED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_source WHERE object_id IS NULL AND issue_code='MISSING_CUSTOMER'",Integer.class)).isEqualTo(2);
    jdbc.update("UPDATE oa_form SET customer='fixed' WHERE id IN (16031,16032)");
    assertThat(retryFailed(batch.getRepriceNo()).getTotalCount()).isEqualTo(1);
  }

  @Test void customerCaseAndLeadingZerosRemainDistinctButOuterWhitespaceDoesNot() {
    var customers=List.of("001","1","customer","Customer"," 001 ");
    for(int i=0;i<customers.size();i++) customerSource(6040+i,customers.get(i),"P","2026-01-01 00:00:00");
    var batch=service.enqueue("COMMERCIAL",MONTH);
    assertThat(batch.getSourceCount()).isEqualTo(5);
    assertThat(batch.getTotalCount()).isEqualTo(4);
    assertThat(jdbc.queryForList("SELECT customer_identity_value FROM lp_monthly_reprice_result WHERE reuse_batch_id=?",String.class,batch.getId()))
        .containsExactlyInAnyOrder("001","1","customer","Customer");
  }

  @Test void lateSourceInsertFailureRollsBackWholeAcceptance() {
    customerSource(6051,"001","P","2026-01-01 00:00:00");
    customerSource(6052,"001","P","2026-01-02 00:00:00");
    jdbc.execute("CREATE TRIGGER mrp_fail_source BEFORE INSERT ON lp_monthly_reprice_source FOR EACH ROW BEGIN IF NEW.oa_form_item_id=6052 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MRP_SOURCE_ROLLBACK'; END IF; END");
    try { assertThatThrownBy(()->service.enqueue("COMMERCIAL",MONTH)).hasStackTraceContaining("MRP_SOURCE_ROLLBACK"); }
    finally { jdbc.execute("DROP TRIGGER mrp_fail_source"); }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_source",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_task",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_batch",Integer.class)).isZero();
    assertThat(service.blocksQuotation("COMMERCIAL")).isFalse();
  }

  @Test void mergedAcceptanceRemainsIdempotentUnderConcurrentSubmissions() throws Exception {
    customerSource(6061,"001","P","2026-01-01 00:00:00");
    customerSource(6062,"001","P","2026-01-02 00:00:00");
    var barrier=new CyclicBarrier(8);
    try(var pool=Executors.newFixedThreadPool(8)) {
      var jobs=new ArrayList<Future<Long>>();
      for(int i=0;i<8;i++) jobs.add(pool.submit(()->{
        auth("COMMERCIAL","ROLE_BU_DIRECTOR");
        try {barrier.await(10,TimeUnit.SECONDS);return service.enqueue("COMMERCIAL",MONTH).getId();}
        finally {SecurityContextHolder.clearContext();}
      }));
      var batches=new HashSet<Long>();for(var job:jobs) batches.add(job.get(20,TimeUnit.SECONDS));
      assertThat(batches).hasSize(1);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_task",Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_source",Integer.class)).isEqualTo(2);
  }

  @Test void changedProductIdentityRejectsAcceptanceWithoutLeavingPartialObjects() {
    customerSource(6071,"001","P","2026-01-01 00:00:00");
    jdbc.update("UPDATE oa_form_item SET material_no='DIFFERENT' WHERE id=6071");
    assertThatThrownBy(()->service.enqueue("COMMERCIAL",MONTH)).hasMessageContaining("原始产品身份已变化");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_task",Integer.class)).isZero();
    assertThat(service.blocksQuotation("COMMERCIAL")).isFalse();
  }

  @Test void boundedRegistrationPreservesSourcesAcrossChunkBoundaries() {
    for(int i=0;i<405;i++) customerSource(7000+i,"customer","P-"+(i%135),"2026-01-01 00:00:00");
    var batch=service.enqueue("COMMERCIAL",MONTH);
    assertThat(batch.getSourceCount()).isEqualTo(405);
    assertThat(batch.getTotalCount()).isEqualTo(135);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_source WHERE batch_id=?",Integer.class,batch.getId())).isEqualTo(405);
    assertThat(jdbc.queryForList("SELECT merged_source_count FROM lp_monthly_reprice_result WHERE reuse_batch_id=?",Integer.class,batch.getId())).hasSize(135).containsOnly(3);
  }

  @Test void confirmationRejectsMissingMergedProvenanceEvenWhenEveryCostSucceeded() {
    var a=customerSource(6081,"001","P","2026-01-01 00:00:00");
    customerSource(6082,"001","P","2026-01-02 00:00:00");
    var checked=ready(service.enqueue("COMMERCIAL",MONTH));
    jdbc.update("DELETE FROM lp_monthly_reprice_source WHERE source_cost_version_id=?",a.sourceCostVersionId());
    assertThatThrownBy(()->publications.confirm(checked.getRepriceNo(),checked.getCheckedRevision()))
        .hasMessageContaining("fixture fixed sources incomplete");
    assertThat(mapper.readBatch(checked.getRepriceNo()).getStatus()).isEqualTo("WAIT_CONFIRM");
  }

  @Test void failedCustomerRepairRestoresOriginalPendingObjectsAndSourceLinks() {
    customerSource(6091,"001","P","2026-01-01 00:00:00");
    customerSource(6092,null,"P","2026-01-02 00:00:00");
    var batch=service.enqueue("COMMERCIAL",MONTH);
    var original=jdbc.queryForList("SELECT * FROM lp_monthly_reprice_source ORDER BY id");
    long taskId=task(batch).getId();
    jdbc.update("UPDATE oa_form SET customer='001' WHERE id=16092");
    jdbc.execute("CREATE TRIGGER mrp_fail_repair BEFORE UPDATE ON lp_cost_run_task FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MRP_REPAIR_ROLLBACK'");
    try { assertThatThrownBy(()->retryFailed(batch.getRepriceNo())).hasStackTraceContaining("MRP_REPAIR_ROLLBACK"); }
    finally { jdbc.execute("DROP TRIGGER mrp_fail_repair"); }
    assertThat(jdbc.queryForList("SELECT * FROM lp_monthly_reprice_source ORDER BY id")).isEqualTo(original);
    assertThat(task(batch).getId()).isEqualTo(taskId);
    assertThat(mapper.readBatch(batch.getRepriceNo()).getUnresolvedSourceCount()).isEqualTo(1);
    assertThat(mapper.readBatch(batch.getRepriceNo()).getStatus()).isEqualTo("FAILED");
  }

}
