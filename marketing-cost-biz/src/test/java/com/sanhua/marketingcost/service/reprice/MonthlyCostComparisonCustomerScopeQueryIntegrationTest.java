package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonScope.Selected;
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
class MonthlyCostComparisonCustomerScopeQueryIntegrationTest {
  static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withLabel("sales-cost.task",System.getProperty("mpp.mysql.task","monthly-reprice-reuse-20261105-r02")).withReuse(Boolean.getBoolean("mpp.mysql.retain")).withConfigurationOverride("monthly-pricing/mysql").withDatabaseName("comparison_query").withUsername("root").withPassword("query-only-test");
  static JdbcTemplate jdbc;static MonthlyCostComparisonMapper mapper;
  @BeforeAll static void setup() throws Exception {
    MYSQL.start();var properties=new Properties();properties.setProperty("connectionCollation","utf8mb4_unicode_ci");
    var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());ds.setConnectionProperties(properties);jdbc=new JdbcTemplate(ds);
    assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    jdbc.execute("CREATE TABLE oa_form(id BIGINT PRIMARY KEY,oa_no VARCHAR(64),business_unit_type VARCHAR(32))");
    jdbc.execute("CREATE TABLE oa_form_item(id BIGINT PRIMARY KEY,oa_form_id BIGINT,business_unit_type VARCHAR(32))");
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");jdbc.execute("CREATE TABLE lp_material_monthly_price(id BIGINT PRIMARY KEY)");
    new ResourceDatabasePopulator(new ClassPathResource("monthly-reprice/pre-v348-schema.sql"),new ClassPathResource("db/V348__monthly_reprice_1130_contract.sql"),new ClassPathResource("db/V361__monthly_reprice_reuse_contract.sql"),new ClassPathResource("db/V363__monthly_reprice_partial_publication.sql")).execute(ds);
    var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.addMapper(MonthlyCostComparisonMapper.class);
    var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setConfiguration(config);factory.setDataSource(ds);
    mapper=new SqlSessionTemplate(factory.getObject()).getMapper(MonthlyCostComparisonMapper.class);
  }
  @AfterAll static void stop(){if(!Boolean.getBoolean("mpp.mysql.retain"))MYSQL.stop();}
  @BeforeEach void clear(){
    for(var table:List.of("lp_monthly_reprice_source","lp_monthly_reprice_result","lp_monthly_reprice_compute_group","lp_cost_run_task","lp_cost_run_part_item","lp_cost_run_cost_item","lp_quote_cost_run_version","lp_monthly_reprice_batch","oa_form_item","oa_form")) jdbc.update("DELETE FROM "+table);
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

  static void customerMonth(String month,String oa,long item,String customer,boolean success) {
    String no="CUSTOMER-"+month;
    Long batch=jdbc.query("SELECT id FROM lp_monthly_reprice_batch WHERE reprice_no=?",rs->rs.next()?rs.getLong(1):null,no);
    if(batch==null) {
      owner(no,"COMMERCIAL",month,"CONFIRMED");
      jdbc.update("UPDATE lp_monthly_reprice_batch SET contract_version=1,go_live_month='2025-01',result_revision=1,checked_revision=1,check_status='PASSED',check_result_json='{}',checked_at=NOW(),confirmed_at=NOW(),confirmed_by='READ-FIXTURE',scope_version=1,source_count=1,unresolved_source_count=0 WHERE reprice_no=?",no);
      batch=jdbc.queryForObject("SELECT id FROM lp_monthly_reprice_batch WHERE reprice_no=?",Long.class,no);
    } else jdbc.update("UPDATE lp_monthly_reprice_batch SET source_count=source_count+1 WHERE id=?",batch);
    jdbc.update("UPDATE lp_monthly_reprice_batch SET total_count=total_count+1 WHERE id=?",batch);
    long saved=version("COMMERCIAL",oa,item,month,success?"SUCCESS":"FAILED",month+"-15 12:00:00",no,success);
    String key="OBJECT-"+item;
    jdbc.update("INSERT INTO lp_monthly_reprice_result(reprice_no,pricing_month,business_unit_type,oa_no,oa_form_item_id,product_code,calc_object_key,contract_version,source_cost_version_id,cost_run_version_id,calc_status,total_cost,reuse_batch_id,customer_identity_source,customer_identity_value,merged_source_count) VALUES(?,?,'COMMERCIAL',?,?,'00001',?,1,?,?,?, ?,?,'OA_HEADER_CUSTOMER',?,1)",
        no,month,oa,item,key,saved,success?saved:null,success?"SUCCESS":"PENDING",success?100:null,batch,customer);
    long object=jdbc.queryForObject("SELECT id FROM lp_monthly_reprice_result WHERE reprice_no=? AND calc_object_key=?",Long.class,no,key);
    jdbc.update("INSERT INTO lp_cost_run_task(batch_no,scene,source_no,calc_object_key,oa_no,oa_form_item_id,product_code,business_unit_type,pricing_month,status,cost_run_version_id,error_message) VALUES(?,'MONTHLY_REPRICE',?,?,?,?,'00001','COMMERCIAL',?,?,?,?)",
        no,no,key,oa,item,month,success?"SUCCESS":"FAILED",success?saved:null,success?null:"Missing actual material price");
    jdbc.update("INSERT INTO lp_monthly_reprice_source(batch_id,object_id,source_cost_version_id,oa_no,oa_form_item_id,source_snapshot_json) VALUES(?,?,?,?,?,'{}')",batch,object,saved,oa,item);
  }
  @Test void mixedLegacyAndReuseCollationsRemainQueryableWithoutAnySavedObjects() {
    // The production schema intentionally preserves legacy unicode_ci and exact source identities.
    assertThat(jdbc.queryForObject("SELECT @@collation_connection",String.class)).isEqualTo("utf8mb4_unicode_ci");
    assertThat(jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='lp_quote_cost_run_version' AND COLUMN_NAME='oa_no'",String.class))
        .isEqualTo("utf8mb4_unicode_ci");
    assertThat(jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='lp_monthly_reprice_source' AND COLUMN_NAME='oa_no'",String.class))
        .isEqualTo("utf8mb4_bin");
    for(boolean review:List.of(false,true)) {
      var empty=scope("HOUSEHOLD",review,1,20);
      assertThat(mapper.coverage(empty)).isEmpty();
      assertThat(mapper.countObjects(empty)).isZero();
      assertThat(mapper.pageSelections(empty)).isEmpty();
      var exported=new ArrayList<Selected>();
      mapper.exportSelections(empty,row->exported.add(row.getResultObject()));
      assertThat(exported).isEmpty();
      assertThat(mapper.countHistoricalSources(empty,1)).isZero();
      assertThat(mapper.historicalSources(empty,1)).isEmpty();
    }
  }
  @Test void sameProductAndCustomerPairsAcrossDifferentRepresentativeOasBeforePagination() {
    customerMonth("2025-12","OA-OLD",1,"Customer-A",true);
    customerMonth("2026-03","OA-NEW",2,"Customer-A",true);
    assertThat(mapper.countObjects(scope("COMMERCIAL",true,1,1))).isEqualTo(1);
    assertThat(mapper.pageSelections(scope("COMMERCIAL",true,1,1))).extracting(Selected::oaNo).containsExactly("OA-OLD","OA-NEW");
  }
  @Test void customerWithNoSuccessfulCostInEitherMonthStillBelongsToTheReport() {
    customerMonth("2025-12","OA-OLD",1,"Customer-A",false);
    customerMonth("2026-03","OA-NEW",2,"Customer-A",false);
    assertThat(mapper.countObjects(scope("COMMERCIAL",true,1,1))).isEqualTo(1);
    assertThat(mapper.coverage(scope("COMMERCIAL",true,1,1))).extracting(MonthlyCostComparisonMapper.Coverage::month).containsExactly("2025-12","2026-03");
  }
  @Test void customerCaseAndSourceIdentityAreExactAndAnOaFilterKeepsBothWholeMonths() {
    customerMonth("2025-12","OA-OLD",1,"A",true);customerMonth("2026-03","OA-NEW",2,"A",true);
    customerMonth("2025-12","OA-OTHER",3,"a",true);customerMonth("2026-03","OA-OTHER-NEW",4,"a",true);
    assertThat(mapper.countObjects(scope("COMMERCIAL",true,1,20))).isEqualTo(2);
    var filtered=new Scope("COMMERCIAL","2025-12","2026-03","OA-OLD",1L,null,true,0,1);
    assertThat(mapper.pageSelections(filtered)).extracting(s->s.oaNo()).containsExactly("OA-OLD","OA-NEW");
    assertThat(mapper.countObjects(filtered)).isEqualTo(1);
  }
  @Test void aMergedSourceOutsideTheComparedMonthsStillFindsBothWholeCustomerMonths() {
    customerMonth("2025-12","OA-OLD",1,"A",true);
    customerMonth("2026-03","OA-NEW",2,"A",true);
    customerMonth("2026-03","OA-OTHER",4,"a",true);
    long original=version("COMMERCIAL","OA-MERGED",3,"2026-01","SUCCESS","2026-01-15",null,true);
    long object=jdbc.queryForObject("SELECT id FROM lp_monthly_reprice_result WHERE oa_no='OA-NEW'",Long.class);
    long batch=jdbc.queryForObject("SELECT reuse_batch_id FROM lp_monthly_reprice_result WHERE id=?",Long.class,object);
    jdbc.update("INSERT INTO lp_monthly_reprice_source(batch_id,object_id,source_cost_version_id,oa_no,oa_form_item_id,source_snapshot_json) VALUES(?,?,?,'OA-MERGED',3,'{}')",batch,object,original);
    jdbc.update("UPDATE lp_monthly_reprice_result SET merged_source_count=2 WHERE id=?",object);
    for(var filter:List.of(
        new Scope("COMMERCIAL","2025-12","2026-03","OA-MERGED",null,null,true,0,20),
        new Scope("COMMERCIAL","2025-12","2026-03",null,3L,null,true,0,20),
        new Scope("COMMERCIAL","2025-12","2026-03","OA-MERGED",3L,"00001",true,0,20))) {
      assertThat(mapper.countObjects(filter)).isEqualTo(1);
      var selected=mapper.pageSelections(filter);
      assertThat(selected).extracting(Selected::oaNo).containsExactly("OA-OLD","OA-NEW");
      var exported=new ArrayList<Selected>();mapper.exportSelections(filter,row->exported.add(row.getResultObject()));
      assertThat(exported).containsExactlyElementsOf(selected);
    }
    for(var filter:List.of(
        new Scope("COMMERCIAL","2025-12","2026-03","oa-merged",3L,null,true,0,20),
        new Scope("COMMERCIAL","2025-12","2026-03","OA-MERGED",2L,null,true,0,20),
        new Scope("HOUSEHOLD","2025-12","2026-03","OA-MERGED",3L,null,true,0,20)))
      assertThat(mapper.countObjects(filter)).isZero();
  }
  @Test void failedSelectedObjectDoesNotFallBackToAnEarlierSuccessfulCostInTheSameBatch() {
    customerMonth("2026-03","OA-NEW",2,"A",false);
    version("COMMERCIAL","OA-NEW",2,"2026-03","SUCCESS","2026-03-01","CUSTOMER-2026-03",true);
    assertThat(mapper.pageSelections(scope("COMMERCIAL",true,1,20))).singleElement().satisfies(s->{
      assertThat(s.status()).isEqualTo("FAILED");assertThat(s.versionId()).isNull();assertThat(s.reason()).contains("Missing");
    });
  }
  @Test void pendingAndRunningCustomersRemainVisibleWithNoCost() {
    customerMonth("2025-12","OA-PENDING",1,"A",false);customerMonth("2026-03","OA-RUNNING",2,"A",false);
    jdbc.update("UPDATE lp_cost_run_task SET status=CASE pricing_month WHEN '2025-12' THEN 'PENDING' ELSE 'RUNNING' END");
    var rows=mapper.pageSelections(scope("COMMERCIAL",true,1,1));
    assertThat(rows).extracting(Selected::status).containsExactly("PENDING","RUNNING");
    assertThat(rows).allSatisfy(s->assertThat(s.versionId()).isNull());
  }
  @Test void successfulTaskWithDifferentCostBindingCannotPublishAnApparentlyValidResult() {
    customerMonth("2026-03","OA-NEW",2,"A",true);
    jdbc.update("UPDATE lp_cost_run_task SET cost_run_version_id=NULL");
    assertThat(mapper.pageSelections(scope("COMMERCIAL",true,1,20))).singleElement().satisfies(s->{
      assertThat(s.status()).isEqualTo("DATA_ISSUE");assertThat(s.versionId()).isNull();
    });
  }
  @Test void taskFromAnotherQueueCannotSupplyTheCustomerResult() {
    customerMonth("2026-03","OA-NEW",2,"A",true);
    jdbc.update("UPDATE lp_cost_run_task SET batch_no='UNRELATED-QUEUE'");
    assertThat(mapper.pageSelections(scope("COMMERCIAL",true,1,20))).singleElement().satisfies(s->{
      assertThat(s.status()).isEqualTo("DATA_ISSUE");assertThat(s.versionId()).isNull();
    });
  }
  @Test void legacyEvidenceWithItsOriginalQueueNumberStillUsesTheLatestMatchingSuccess() {
    owner("LEGACY","COMMERCIAL","2025-12","CONFIRMED");
    long saved=version("COMMERCIAL","L-1",1,"2025-12","SUCCESS","2025-12-01","LEGACY",true);
    for(var customer:List.of("EARLIER","LATEST")) {
      jdbc.update("INSERT INTO lp_cost_run_task(batch_no,scene,source_no,calc_object_key,oa_no,oa_form_item_id,product_code,business_unit_type,pricing_month,status,cost_run_version_id,request_snapshot_json) VALUES(?,'MONTHLY_REPRICE','LEGACY',?,'L-1',1,'00001','COMMERCIAL','2025-12','SUCCESS',?,?)",
          "OLD-QUEUE-"+customer,customer,saved,"{\"customerIdentity\":{\"customerSource\":\"OA_HEADER_CUSTOMER\",\"customerValue\":\""+customer+"\"}}");
    }
    assertThat(mapper.pageSelections(scope("COMMERCIAL",true,1,20)))
        .singleElement().satisfies(s->assertThat(s.customerValue()).isEqualTo("LATEST"));
  }
  @Test void savedLegacyCustomerEvidenceGroupsOasAndKeepsEveryOriginalSourcePage() {
    owner("LEGACY","COMMERCIAL","2025-12","CONFIRMED");
    var first=version("COMMERCIAL","L-1",1,"2025-12","SUCCESS","2025-12-01","LEGACY",true);
    var last=version("COMMERCIAL","L-2",2,"2025-12","SUCCESS","2025-12-31","LEGACY",true);
    for(var pair:List.of(new long[]{first,1},new long[]{last,2})) {
      String oa="L-"+pair[1];
      jdbc.update("INSERT INTO lp_cost_run_task(batch_no,scene,source_no,calc_object_key,oa_no,oa_form_item_id,product_code,business_unit_type,pricing_month,status,cost_run_version_id,request_snapshot_json) VALUES('LEGACY','MONTHLY_REPRICE','LEGACY',?,?,?,'00001','COMMERCIAL','2025-12','SUCCESS',?,?)",
          oa,oa,pair[1],pair[0],"{\"bomInput\":{\"context\":{\"customer\":{\"source\":\"OA_HEADER_CUSTOMER\",\"value\":\"A\"}}}}");
    }
    var scope=scope("COMMERCIAL",true,1,1);
    assertThat(mapper.countObjects(scope)).isEqualTo(1);
    assertThat(mapper.pageSelections(scope)).singleElement().satisfies(s->{assertThat(s.versionId()).isEqualTo(last);assertThat(s.sourceCount()).isEqualTo(2);});
    assertThat(mapper.countHistoricalSources(scope,last)).isEqualTo(2);
    assertThat(mapper.historicalSources(scope,last)).singleElement().satisfies(s->{assertThat(s.oaNo()).isEqualTo("L-1");assertThat(s.selected()).isFalse();});
    assertThat(mapper.historicalSources(scope("COMMERCIAL",true,2,1),last)).singleElement().satisfies(s->{assertThat(s.oaNo()).isEqualTo("L-2");assertThat(s.selected()).isTrue();});
    assertThat(mapper.countHistoricalSources(scope("HOUSEHOLD",true,1,1),last)).isZero();
  }
  @Test void streamedExportMatchesAllPagesIncludingUnmatchedHistoryAndFailedCustomers() {
    customerMonth("2025-12","A-OLD",1,"A",true);
    customerMonth("2026-03","A-NEW",2,"A",false);
    customerMonth("2026-03","B-NEW",3,"B",true);
    version("COMMERCIAL","UNKNOWN-4",4,"2025-12","SUCCESS","2025-12-01",null,true);
    version("COMMERCIAL","UNKNOWN-5",5,"2025-12","SUCCESS","2025-12-01",null,true);
    version("HOUSEHOLD","OTHER-UNIT",6,"2025-12","SUCCESS","2025-12-01",null,true);
    var selection=scope("COMMERCIAL",true,1,1);
    var expected=new ArrayList<Selected>();
    for(int page=1;page<=mapper.countObjects(selection);page++)
      expected.addAll(mapper.pageSelections(scope("COMMERCIAL",true,page,1)));
    var streamed=new ArrayList<Selected>();
    var transaction=new org.springframework.transaction.support.TransactionTemplate(
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    transaction.setReadOnly(true);
    transaction.executeWithoutResult(status->{
      mapper.exportSelections(selection,row->streamed.add(row.getResultObject()));
      // The streaming result is closed before snapshot/detail queries use this same connection.
      assertThat(mapper.countObjects(selection)).isEqualTo(4);
    });
    assertThat(streamed).containsExactlyElementsOf(expected).hasSize(5);
    assertThat(streamed.stream().filter(s->s.unmatchedKey()!=null)).hasSize(2);
    assertThat(streamed.stream().filter(s->"FAILED".equals(s.status())))
        .singleElement().satisfies(s->assertThat(s.versionId()).isNull());
    assertThat(streamed).allSatisfy(s->assertThat(s.businessUnitType()).isEqualTo("COMMERCIAL"));
  }
  @Test void capturedLegacyRepricingSourceIdentityAlsoIdentifiesItsExactPriorCost() {
    long old=version("COMMERCIAL","OA-CHAIN",901,"2025-12","SUCCESS","2025-12-10 12:00:00",null,true);
    owner("CHAIN-MR","COMMERCIAL","2026-03","CONFIRMED");
    long current=version("COMMERCIAL","OA-CHAIN",901,"2026-03","SUCCESS","2026-03-10 12:00:00","CHAIN-MR",true);
    capturedTask("CHAIN-MR",current,old,"Customer-A","OA-CHAIN",901);
    var rows=mapper.pageSelections(scope("COMMERCIAL",false,1,20));
    assertThat(mapper.countObjects(scope("COMMERCIAL",false,1,20))).isEqualTo(1);
    assertThat(rows).hasSize(2).allMatch(x->x.identity().matched() && x.customerValue().equals("Customer-A"));
    assertThat(rows.stream().map(Selected::versionId)).containsExactlyInAnyOrder(old,current);
  }
  @Test void conflictingCapturedCustomerClaimsNeverGuessAnOldCostCustomer() {
    long old=version("COMMERCIAL","OA-CONFLICT",902,"2025-12","SUCCESS","2025-12-10 12:00:00",null,true);
    owner("CLAIM-A","COMMERCIAL","2026-03","CONFIRMED");
    long a=version("COMMERCIAL","OA-CONFLICT",902,"2026-03","HISTORY","2026-03-10 12:00:00","CLAIM-A",true);
    capturedTask("CLAIM-A",a,old,"Customer-A","OA-CONFLICT",902);
    owner("CLAIM-B","COMMERCIAL","2026-03","CONFIRMED");
    long b=version("COMMERCIAL","OA-CONFLICT",902,"2026-03","SUCCESS","2026-03-11 12:00:00","CLAIM-B",true);
    capturedTask("CLAIM-B",b,old,"Customer-B","OA-CONFLICT",902);
    assertThat(mapper.pageSelections(scope("COMMERCIAL",false,1,20)).stream().filter(x->Objects.equals(x.versionId(),old)))
        .singleElement().satisfies(x->assertThat(x.identity().matched()).isFalse());
  }
  @Test void aDifferentOriginalOaCannotLendItsCapturedIdentityToAnotherCost() {
    long old=version("COMMERCIAL","OA-OWNER",903,"2025-12","SUCCESS","2025-12-10 12:00:00",null,true);
    owner("WRONG-CLAIM","COMMERCIAL","2026-03","CONFIRMED");
    long current=version("COMMERCIAL","OA-OTHER",904,"2026-03","SUCCESS","2026-03-10 12:00:00","WRONG-CLAIM",true);
    capturedTask("WRONG-CLAIM",current,old,"Customer-A","OA-OTHER",904);
    assertThat(mapper.pageSelections(scope("COMMERCIAL",false,1,20)).stream().filter(x->Objects.equals(x.versionId(),old)))
        .singleElement().satisfies(x->assertThat(x.identity().matched()).isFalse());
  }
  @Test void currentCapturedSourcePairsItsExactPriorCostWithAnUnfinishedCustomerObject() {
    long old=version("COMMERCIAL","OA-CAPTURE",905,"2025-12","SUCCESS","2025-12-10",null,true);
    customerMonth("2026-03","OA-CAPTURE",905,"Customer-A",false);
    captureSource(old,"OA-CAPTURE",905,"Customer-A");
    assertThat(mapper.countObjects(scope("COMMERCIAL",true,1,20))).isEqualTo(1);
    assertThat(mapper.pageSelections(scope("COMMERCIAL",true,1,20))).hasSize(2)
        .allSatisfy(row -> assertThat(row.customerValue()).isEqualTo("Customer-A"));
  }

  @Test void currentCaptureWithWrongVersionIdentityCannotLendACustomer() {
    long old=version("COMMERCIAL","OA-CAPTURE",905,"2025-12","SUCCESS","2025-12-10",null,true);
    customerMonth("2026-03","OA-CAPTURE",905,"Customer-A",false);
    captureSource(old,"OA-CAPTURE",905,"Customer-A");
    jdbc.update("UPDATE lp_monthly_reprice_source SET source_snapshot_json=JSON_SET(source_snapshot_json,'$.version.oaNo','WRONG-OA')");
    assertThat(mapper.countObjects(scope("COMMERCIAL",true,1,20))).isEqualTo(2);
    assertThat(mapper.pageSelections(scope("COMMERCIAL",true,1,20)).stream().filter(row -> Objects.equals(row.versionId(),old)))
        .singleElement().satisfies(row -> assertThat(row.identity().matched()).isFalse());
  }

  static void captureSource(long source,String oa,long item,String customer) {
    String snapshot="{\"version\":{\"id\":"+source+",\"oaNo\":\""+oa+"\",\"oaFormItemId\":"+item+",\"productCode\":\"00001\",\"businessUnitType\":\"COMMERCIAL\"},\"form\":{\"customer\":\""+customer+"\"}}";
    jdbc.update("UPDATE lp_monthly_reprice_source SET source_cost_version_id=?,source_snapshot_json=? WHERE oa_no=? AND oa_form_item_id=?",source,snapshot,oa,item);
  }

  static void capturedTask(String batch,long current,long source,String customer,String oa,long item) {
    var snapshot="{\"sourceCostVersionId\":"+source+",\"bomInput\":{\"context\":{\"customer\":{\"source\":\"OA_HEADER_CUSTOMER\",\"value\":\""+customer+"\"}}}}";
    jdbc.update("INSERT INTO lp_cost_run_task(batch_no,scene,source_no,calc_object_key,oa_no,oa_form_item_id,product_code,business_unit_type,pricing_month,status,cost_run_version_id,request_snapshot_json) VALUES(?,'MONTHLY_REPRICE',?,'CHAIN',?,?,'00001','COMMERCIAL','2026-03','SUCCESS',?,?)",batch,batch,oa,item,current,snapshot);
  }

}
