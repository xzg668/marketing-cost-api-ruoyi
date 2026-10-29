package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.MonthlyCostComparisonMapper;
import com.sanhua.marketingcost.mapper.MonthlyCostComparisonMapper.Scope;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Production comparison SQL on a fresh real database; synthetic contract rows are not costing acceptance. */
@Tag("integration")
class MonthlyCostComparisonQueryIntegrationTest {
  static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withConfigurationOverride("monthly-pricing/mysql").withDatabaseName("comparison_query").withUsername("root").withPassword("query-only-test");
  static JdbcTemplate jdbc;static MonthlyCostComparisonMapper mapper;
  @BeforeAll static void setup() throws Exception {
    MYSQL.start();var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());jdbc=new JdbcTemplate(ds);
    assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    jdbc.execute("CREATE TABLE oa_form(id BIGINT PRIMARY KEY,oa_no VARCHAR(64),business_unit_type VARCHAR(32))");
    jdbc.execute("CREATE TABLE oa_form_item(id BIGINT PRIMARY KEY,oa_form_id BIGINT,business_unit_type VARCHAR(32))");
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");jdbc.execute("CREATE TABLE lp_material_monthly_price(id BIGINT PRIMARY KEY)");
    new ResourceDatabasePopulator(new ClassPathResource("monthly-reprice/pre-v348-schema.sql"),new ClassPathResource("db/V348__monthly_reprice_1130_contract.sql")).execute(ds);
    var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.addMapper(MonthlyCostComparisonMapper.class);
    var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setConfiguration(config);factory.setDataSource(ds);
    mapper=new SqlSessionTemplate(factory.getObject()).getMapper(MonthlyCostComparisonMapper.class);
  }
  @AfterAll static void stop(){MYSQL.stop();}
  @BeforeEach void clear(){
    for(var table:List.of("lp_cost_run_part_item","lp_cost_run_cost_item","lp_quote_cost_run_version","lp_monthly_reprice_batch","oa_form_item","oa_form")) jdbc.update("DELETE FROM "+table);
  }
  static Scope scope(String unit,boolean review,int page,int size){return new Scope(unit,"2025-12","2026-03",null,null,null,review,(page-1)*size,size);}
  static long version(String unit,String oa,long item,String month,String status,String finished,String owner,boolean complete){
    jdbc.update("INSERT IGNORE INTO oa_form(id,oa_no,business_unit_type) VALUES(?,?,?)",item,oa,unit);
    jdbc.update("INSERT IGNORE INTO oa_form_item(id,oa_form_id,business_unit_type) VALUES(?,?,?)",item,item,unit);
    String run=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO lp_quote_cost_run_version(cost_run_no,oa_no,oa_form_item_id,product_code,pricing_month,result_period,status,total_cost,trial_finished_at,business_unit_type,reprice_no,part_item_count,cost_item_count) VALUES(?,?,?,'00001',?,?,?,100,?,?,?,1,1)",run,oa,item,month,month,status,finished,unit,owner);
    long id=jdbc.queryForObject("SELECT id FROM lp_quote_cost_run_version WHERE cost_run_no=?",Long.class,run);
    if(complete){
      jdbc.update("INSERT INTO lp_cost_run_part_item(oa_no,product_code,cost_run_version_id,part_code,qty,amount) VALUES(?,'00001',?,'000X',1,100)",oa,id);
      jdbc.update("INSERT INTO lp_cost_run_cost_item(oa_no,product_code,cost_run_version_id,cost_code,cost_name,amount) VALUES(?,'00001',?,'TOTAL','合计',100)",oa,id);
    }
    return id;
  }
  static void owner(String no,String unit,String month,String status){
    jdbc.update("INSERT INTO lp_monthly_reprice_batch(reprice_no,pricing_month,price_as_of_time,business_unit_type,status) VALUES(?,?,NOW(),?,?)",no,month,unit,status);
  }
  @Test void latestCompleteSuccessWinsOverFailedLastAndIncompleteSuccessWithStableTieBreak(){
    var old=version("COMMERCIAL","OA",1,"2025-12","HISTORY","2025-12-01",null,true);
    var latest=version("COMMERCIAL","OA",1,"2025-12","SUCCESS","2025-12-31",null,true);
    var tied=version("COMMERCIAL","OA",1,"2025-12","SUCCESS","2025-12-31",null,true);
    var failed=version("COMMERCIAL","OA",1,"2025-12","FAILED","2025-12-31 23:59:59",null,true);
    var partial=version("COMMERCIAL","OA",1,"2025-12","SUCCESS","2025-12-31 23:59:59",null,false);
    var next=version("COMMERCIAL","OA",1,"2026-03","CONFIRMED","2026-03-15",null,true);
    assertThat(mapper.pageVersions(scope("COMMERCIAL",true,1,20))).extracting(QuoteCostRunVersion::getId).containsExactly(tied,next);
    assertThat(mapper.coverage(scope("COMMERCIAL",true,1,20))).extracting(MonthlyCostComparisonMapper.Coverage::objects).containsExactly(1L,1L);
  }
  @Test void eachOriginalOaAndProductLineRemainsIndependentAndPagesDoNotSplitTwoMonths(){
    for(long item:List.of(1L,2L,3L)){
      version("COMMERCIAL",item==3?"OA-2":"OA-1",item,"2025-12","SUCCESS","2025-12-31",null,true);
      version("COMMERCIAL",item==3?"OA-2":"OA-1",item,"2026-03","SUCCESS","2026-03-31",null,true);
    }
    assertThat(mapper.countObjects(scope("COMMERCIAL",true,1,1))).isEqualTo(3);
    for(int page=1;page<=3;page++){
      var v=mapper.pageVersions(scope("COMMERCIAL",true,page,1));assertThat(v).hasSize(2);
      assertThat(v).extracting(QuoteCostRunVersion::getOaFormItemId).containsOnly((long)page);
    }
    assertThat(mapper.pageVersions(scope("COMMERCIAL",true,4,1))).isEmpty();
  }
  @Test void unconfirmedRepricingIsRestrictedToReviewScopeAndCannotHideConfirmedSavedMonth(){
    var original=version("COMMERCIAL","OA",1,"2025-12","SUCCESS","2025-12-15",null,true);
    owner("MRP-TEST","COMMERCIAL","2025-12","WAIT_CONFIRM");
    var repriced=version("COMMERCIAL","OA",1,"2025-12","SUCCESS","2025-12-31","MRP-TEST",true);
    assertThat(mapper.pageVersions(scope("COMMERCIAL",false,1,20))).extracting(QuoteCostRunVersion::getId).containsExactly(original);
    assertThat(mapper.pageVersions(scope("COMMERCIAL",true,1,20))).extracting(QuoteCostRunVersion::getId).containsExactly(repriced);
    jdbc.update("UPDATE lp_monthly_reprice_batch SET status='CONFIRMED' WHERE reprice_no='MRP-TEST'");
    assertThat(mapper.pageVersions(scope("COMMERCIAL",false,1,20))).extracting(QuoteCostRunVersion::getId).containsExactly(repriced);
  }
  @Test void scopesAndFiltersAreExactAndQueryHasNoWriteSideEffects(){
    var commercial=version("COMMERCIAL","000OA",1,"2025-12","SUCCESS","2025-12-31",null,true);
    version("HOUSEHOLD","000OA",2,"2025-12","SUCCESS","2025-12-31",null,true);
    var checks=jdbc.queryForList("SELECT id,total_cost,status,reprice_no FROM lp_quote_cost_run_version ORDER BY id");
    var selected=new Scope("COMMERCIAL","2025-12","2026-03","000OA",1L,"00001",true,0,20);
    assertThat(mapper.pageVersions(selected)).extracting(QuoteCostRunVersion::getId).containsExactly(commercial);
    assertThat(mapper.countObjects(scope("HOUSEHOLD",true,1,20))).isEqualTo(1);
    assertThat(mapper.pageVersions(new Scope("COMMERCIAL","2025-12","2026-03","000OA",2L,null,true,0,20))).isEmpty();
    assertThat(jdbc.queryForList("SELECT id,total_cost,status,reprice_no FROM lp_quote_cost_run_version ORDER BY id")).isEqualTo(checks);
  }
  @Test void orphanedAndCrossUnitProductBindingsAreExcludedInsteadOfRelabelled(){
    version("COMMERCIAL","OA-1",1,"2025-12","SUCCESS","2025-12-31",null,true);
    jdbc.update("UPDATE oa_form SET oa_no='OA-OTHER' WHERE id=1");
    assertThat(mapper.countObjects(scope("COMMERCIAL",true,1,20))).isZero();
    jdbc.update("UPDATE oa_form SET oa_no='OA-1' WHERE id=1");jdbc.update("UPDATE oa_form_item SET business_unit_type='HOUSEHOLD' WHERE id=1");
    assertThat(mapper.countObjects(scope("COMMERCIAL",true,1,20))).isZero();
  }
}
