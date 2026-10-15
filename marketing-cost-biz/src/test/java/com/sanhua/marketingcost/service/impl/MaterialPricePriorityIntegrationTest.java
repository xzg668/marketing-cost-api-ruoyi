package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.sanhua.marketingcost.enums.MaterialPriceSourceType;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.service.MaterialPricePriorityService;
import com.sanhua.marketingcost.service.MaterialPricePriorityService.Scope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

@Tag("integration")
class MaterialPricePriorityIntegrationTest {
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
      .withDatabaseName("priority_test").withUsername("root").withPassword("test-only-password");
  private static MaterialPricePriorityService service;
  private static JdbcTemplate jdbc;
  private static TransactionTemplate transaction;
  private final Scope scope=new Scope("COMMERCIAL","2026-10","210","M1");

  @BeforeAll static void start(){
    MYSQL.start();
    var data=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
    jdbc=new JdbcTemplate(data);
    jdbc.execute("""
        CREATE TABLE lp_material_price_type (
          id BIGINT AUTO_INCREMENT PRIMARY KEY, business_unit_type VARCHAR(20) NOT NULL,
          period CHAR(7) NOT NULL, price_org_code VARCHAR(32) NOT NULL, material_code VARCHAR(64) NOT NULL,
          material_name VARCHAR(128),price_type VARCHAR(32) NOT NULL,priority TINYINT NOT NULL,
          manual_priority TINYINT,priority_updated_by BIGINT,priority_updated_at DATETIME(6),
          source VARCHAR(32),source_system VARCHAR(16),created_at DATETIME,updated_at DATETIME,
          UNIQUE KEY uk_scope(business_unit_type,period,price_org_code,material_code,price_type))
        """);
    var config=new MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    config.setEnvironment(new Environment("mysql",new SpringManagedTransactionFactory(),data));
    config.addMapper(MaterialPriceTypeMapper.class);
    var sessions=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
    service=new MaterialPricePriorityService(sessions.getMapper(MaterialPriceTypeMapper.class),new DataSourceTransactionManager(data));
    transaction=new TransactionTemplate(new DataSourceTransactionManager(data));
  }
  @AfterAll static void stop(){MYSQL.stop();}
  @BeforeEach void clean(){jdbc.update("DELETE FROM lp_material_price_type");}

  @Test void databaseRoundTripPreservesAllFiveIndependentTypesAndDefaultRevision(){
    var empty=service.read(scope);
    transaction.executeWithoutResult(status->service.initialize(scope,"物料1"));
    var saved=service.read(scope);
    assertThat(saved).isEqualTo(empty);
    assertThat(jdbc.queryForList("SELECT price_type FROM lp_material_price_type ORDER BY priority",String.class))
        .containsExactly("LINKED","SETTLE_LINKED","RANGE","SETTLE_FIXED","FIXED");
  }

  @Test void automaticGenerationCannotOverwriteFinanceEditAndResetRestoresDefault(){
    transaction.executeWithoutResult(status->service.initialize(scope,"物料1"));
    var order=new ArrayList<>(MaterialPriceSourceType.DEFAULT_ORDER);Collections.reverse(order);
    var original=service.read(scope);
    var edited=transaction.execute(status->service.change(scope,order,original.revision(),9L));
    transaction.executeWithoutResult(status->service.initialize(scope,"物料1"));
    assertThat(service.read(scope)).isEqualTo(edited);
    assertThat(service.read(scope).order().getFirst().type()).isEqualTo(MaterialPriceSourceType.FIXED);
    var reset=transaction.execute(status->service.change(scope,null,edited.revision(),9L));
    assertThat(reset.manual()).isFalse();
    assertThat(reset.order().getFirst().type()).isEqualTo(MaterialPriceSourceType.LINKED);
    assertThat(reset.revision()).isNotEqualTo(edited.revision()).isNotEqualTo(original.revision());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_price_type WHERE priority_updated_by=9",Integer.class)).isEqualTo(5);
  }

  @Test void eightConcurrentPreparationsCreateExactlyOneConfigurationGroup() throws Exception {
    try(var pool=Executors.newFixedThreadPool(8)){
      var start=new java.util.concurrent.CountDownLatch(1);
      var futures=new ArrayList<java.util.concurrent.Future<?>>();
      for(int i=0;i<8;i++)futures.add(pool.submit(()->{
        start.await();transaction.executeWithoutResult(status->service.initialize(scope,"物料1"));return null;
      }));
      start.countDown();for(var future:futures)future.get(15,TimeUnit.SECONDS);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_price_type",Integer.class)).isEqualTo(5);
  }

  @Test void concurrentFinanceEditsUseVersionCheckAndNeverMixOrders() throws Exception {
    transaction.executeWithoutResult(status->service.initialize(scope,"物料1"));
    String version=service.read(scope).revision();
    var reverse=new ArrayList<>(MaterialPriceSourceType.DEFAULT_ORDER);Collections.reverse(reverse);
    try(var pool=Executors.newFixedThreadPool(2)){
      var first=pool.submit(()->transaction.execute(status->service.change(scope,reverse,version,9L)));
      var second=pool.submit(()->transaction.execute(status->service.change(scope,MaterialPriceSourceType.DEFAULT_ORDER,version,10L)));
      int succeeded=0;int rejected=0;
      for(var future:List.of(first,second)){
        try{future.get(15,TimeUnit.SECONDS);succeeded++;}
        catch(java.util.concurrent.ExecutionException failure){assertThat(failure.getCause()).isInstanceOf(OptimisticLockingFailureException.class);rejected++;}
      }
      assertThat(succeeded).isEqualTo(1);assertThat(rejected).isEqualTo(1);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT priority_updated_by) FROM lp_material_price_type",Integer.class)).isEqualTo(1);
  }

  @Test void otherMonthOrganizationAndBusinessUnitAreNotChanged(){
    var scopes=List.of(scope,new Scope("COMMERCIAL","2026-11","210","M1"),
        new Scope("COMMERCIAL","2026-10","220","M1"),new Scope("HOUSEHOLD","2026-10","210","M1"));
    scopes.forEach(s->transaction.executeWithoutResult(status->service.initialize(s,"物料1")));
    transaction.executeWithoutResult(status->service.change(scope,MaterialPriceSourceType.DEFAULT_ORDER,service.read(scope).revision(),9L));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_price_type",Integer.class)).isEqualTo(20);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_price_type WHERE manual_priority IS NOT NULL",Integer.class)).isEqualTo(5);
    scopes.stream().skip(1).forEach(s->assertThat(service.read(s).manual()).isFalse());
  }

  @Test void preparationReadsCommittedFinanceOrderDespiteOlderQuoteTransactionSnapshot() {
    service.initialize(scope, "物料1");
    var shortTransaction = new TransactionTemplate(transaction.getTransactionManager());
    shortTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.executeWithoutResult(status -> {
      var original = service.read(scope); // 报价长事务已建立旧的一致性快照。
      var reverse = new ArrayList<>(MaterialPriceSourceType.DEFAULT_ORDER);
      Collections.reverse(reverse);
      var edited = shortTransaction.execute(ignored -> service.change(scope, reverse, original.revision(), 9L));
      assertThat(service.read(scope).manual()).isFalse();
      assertThat(service.initialize(scope, "物料1")).isEqualTo(edited);
    });
  }

  @Test void initializationReturnsCompleteConfigurationAndSurvivesQuoteRollback() {
    transaction.executeWithoutResult(status -> {
      assertThat(service.read(scope).order()).hasSize(5);
      var initialized = service.initialize(scope, "物料1");
      assertThat(initialized.order()).hasSize(5);
      assertThat(initialized.manual()).isFalse();
      status.setRollbackOnly();
    });
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_price_type", Integer.class)).isEqualTo(5);
  }
}
