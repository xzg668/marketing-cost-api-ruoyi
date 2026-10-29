package com.sanhua.marketingcost.service.supplier;

import static org.assertj.core.api.Assertions.*;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.pricing.SupplierRatiosImported;
import com.zaxxer.hikari.HikariDataSource;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.event.TransactionalEventListener;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Production workbook parser, Spring transaction proxy, MyBatis and after-commit listener on real MySQL. */
@Tag("integration")
class SupplierSupplyRatioIncrementalIntegrationTest {
  static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("mrp02_ratio_test").withUsername("root").withPassword("mrp02-test-only").withConfigurationOverride("monthly-pricing/mysql");
  static HikariDataSource ds;static JdbcTemplate jdbc;static SqlSessionTemplate sql;
  static AnnotationConfigApplicationContext context;static SupplierSupplyRatioImportService imports;
  static SupplierSupplyRatioService queries;static CommitObserver observer;
  @Configuration @EnableTransactionManagement static class Config {
    @Bean PlatformTransactionManager transactionManager(){return new DataSourceTransactionManager(ds);}
    @Bean SupplierSupplyRatioImportService imports(ApplicationEventPublisher events){return new SupplierSupplyRatioImportServiceImpl(sql.getMapper(SupplierSupplyRatioMapper.class),new SupplierSupplyRatioWorkbookParserImpl(),events,sql.getMapper(BusinessChangeLogMapper.class),new SupplierSupplyRatioImportLock(ds),new ObjectMapper());}
    @Bean SupplierSupplyRatioService queries(){return new SupplierSupplyRatioServiceImpl(sql.getMapper(SupplierSupplyRatioMapper.class),sql.getMapper(SupplierSupplyRatioHistoryMapper.class));}
    @Bean CommitObserver observer(){return new CommitObserver();}
  }
  static class CommitObserver {
    final List<SupplierRatiosImported> committed=Collections.synchronizedList(new ArrayList<>());
    @TransactionalEventListener public void imported(SupplierRatiosImported event){committed.add(event);}
  }
  @BeforeAll static void database() throws Exception {
    MYSQL.start();ds=new HikariDataSource();ds.setJdbcUrl(MYSQL.getJdbcUrl());ds.setUsername(MYSQL.getUsername());ds.setPassword(MYSQL.getPassword());ds.setMaximumPoolSize(12);jdbc=new JdbcTemplate(ds);
    assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    new ResourceDatabasePopulator(new ClassPathResource("supplier-ratio/schema.sql")).execute(ds);
    var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
    for(var type:List.of(SupplierSupplyRatioMapper.class,BusinessChangeLogMapper.class,SupplierSupplyRatioHistoryMapper.class))config.addMapper(type);
    var global=new com.baomidou.mybatisplus.core.config.GlobalConfig();global.setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig());global.setMetaObjectHandler(new com.sanhua.marketingcost.config.MetaObjectHandlerConfig());com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,global);
    var pagination=new MybatisPlusInterceptor();pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));config.addInterceptor(pagination);
    var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);sql=new SqlSessionTemplate(factory.getObject());
    context=new AnnotationConfigApplicationContext(Config.class);imports=context.getBean(SupplierSupplyRatioImportService.class);queries=context.getBean(SupplierSupplyRatioService.class);observer=context.getBean(CommitObserver.class);
    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(imports)).isTrue();
  }
  @AfterAll static void stop(){if(context!=null)context.close();if(ds!=null)ds.close();MYSQL.stop();}
  @BeforeEach void clean(){jdbc.update("DELETE FROM lp_business_change_log");jdbc.update("DELETE FROM lp_supplier_supply_ratio");observer.committed.clear();auth("COMMERCIAL");}
  @AfterEach void logout(){SecurityContextHolder.clearContext();}
  static void auth(String unit){var a=new UsernamePasswordAuthenticationToken("mrp02-finance",null);a.setDetails(Map.of("businessUnitType",unit));SecurityContextHolder.getContext().setAuthentication(a);}
  static SupplierSupplyRatioExcelRow row(String supplier,String ratio){return row("000M",supplier,ratio);}
  static SupplierSupplyRatioExcelRow row(String material,String supplier,String ratio){var r=new SupplierSupplyRatioExcelRow();r.setRowNo(2);r.setMaterialCode(material);r.setMaterialName("物料"+material);r.setSpecModel("SPEC");r.setUnit("件");r.setMaterialShape("B");r.setSupplierCode(supplier);r.setSupplierName("供应商"+supplier);r.setSupplyRatio(ratio==null?null:new BigDecimal(ratio));return r;}
  static SupplierSupplyRatioImportResponse save(SupplierSupplyRatioExcelRow... rows){return imports.importRows(List.of(rows),"controlled-ratio.xlsx",com.sanhua.marketingcost.security.BusinessUnitContext.getCurrentBusinessUnitType(),"mrp02-finance");}
  static List<Map<String,Object>> all(){return jdbc.queryForList("SELECT * FROM lp_supplier_supply_ratio ORDER BY id");}
  static Map<String,Object> current(String supplier){return jdbc.queryForMap("SELECT * FROM lp_supplier_supply_ratio WHERE business_unit_type='COMMERCIAL' AND material_code='000M' AND supplier_code=? AND is_active=1",supplier);}
  static int logs(){return jdbc.queryForObject("SELECT COUNT(*) FROM lp_business_change_log",Integer.class);}
  static byte[] workbook(SupplierSupplyRatioExcelRow... rows) throws Exception {
    try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
      var sheet=book.createSheet("供货比例");var header=sheet.createRow(0);String[] labels={"物料代码","物料名称","型号","单位","物料形态属性","供应商","供应商代码","供货比例"};for(int i=0;i<labels.length;i++)header.createCell(i).setCellValue(labels[i]);
      for(int i=0;i<rows.length;i++){var r=rows[i];var x=sheet.createRow(i+1);String[] values={r.getMaterialCode(),r.getMaterialName(),r.getSpecModel(),r.getUnit(),r.getMaterialShape(),r.getSupplierName(),r.getSupplierCode()};for(int n=0;n<values.length;n++)if(values[n]!=null)x.createCell(n).setCellValue(values[n]);if(r.getSupplyRatio()!=null)x.createCell(7).setCellValue(r.getSupplyRatio().doubleValue());}
      book.write(out);return out.toByteArray();
    }
  }
  @Test void addingOnlyCLeavesEveryOriginalAAndBFieldUntouched() {
    save(row("000A","0.6"),row("000B","0.4"));var original=all();
    var result=save(row("000C","0.2"));assertThat(result.getNewRelations()).isEqualTo(1);assertThat(result.getDeactivatedRows()).isZero();
    assertThat(all().subList(0,2)).isEqualTo(original);assertThat(logs()).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_supplier_supply_ratio WHERE is_active=1",Integer.class)).isEqualTo(3);
  }

  @Test void unnamedWorkbookImportsIdempotentlyAndLaterCodeKeepsOneCurrentRelationship() throws Exception {
    var a=row(null,"0.85");a.setSupplierName("供应商甲");
    var b=row(null,"0.15");b.setSupplierName("供应商乙");
    byte[] file=workbook(a,b);
    var first=imports.importExcel(new ByteArrayInputStream(file),"names.xlsx","COMMERCIAL","finance",null);
    assertThat(first.getInsertedRows()).isEqualTo(2);
    var original=all();
    assertThat(imports.importExcel(new ByteArrayInputStream(file),"names.xlsx","COMMERCIAL","finance",null).getUnchangedRows()).isEqualTo(2);
    assertThat(all()).isEqualTo(original);
    a.setSupplierCode("000A");
    assertThat(save(a).getChangedRelations()).isEqualTo(1);
    assertThat(current("000A").get("supplier_name")).isEqualTo("供应商甲");
    a.setSupplierCode(null);
    assertThat(save(a).getUnchangedRows()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_supplier_supply_ratio WHERE is_active=1",Integer.class)).isEqualTo(2);
  }
  @Test void changingOnlyAAndBRetainsCAndRecordsExactPreviousAndNextVersions() throws Exception {
    save(row("000A","0.5"),row("000B","0.3"),row("000C","0.2"));var c=current("000C");var a=current("000A");
    var result=save(row("000A","0.4"),row("000B","0.4"));assertThat(result.getChangedRelations()).isEqualTo(2);assertThat(current("000C")).isEqualTo(c);
    var page=queries.changes("000M","000A",result.getBatchNo(),1,20,null);assertThat(page.total()).isEqualTo(1);var log=page.records().getFirst();
    assertThat(log.previousVersionId()).isEqualTo(((Number)a.get("id")).longValue());assertThat(log.versionId()).isEqualTo(((Number)current("000A").get("id")).longValue());
    var json=new ObjectMapper();assertThat(json.readTree(log.beforeValue()).get("supplyRatio").decimalValue()).isEqualByComparingTo("0.5");assertThat(json.readTree(log.afterValue()).get("supplyRatio").decimalValue()).isEqualByComparingTo("0.4");
    assertThat(log.changedByName()).isEqualTo("mrp02-finance");assertThat(log.importFileName()).isEqualTo("controlled-ratio.xlsx");assertThat(log.changedAt()).isNotNull();
    assertThat(jdbc.queryForObject("SELECT supply_ratio FROM lp_supplier_supply_ratio WHERE id=?",BigDecimal.class,log.previousVersionId())).isEqualByComparingTo("0.5");
  }
  @Test void theSameWorkbookTwiceProducesNoNewVersionLogOrCommitEvent() throws Exception {
    byte[] file=workbook(row("000A","0.6"),row("000B",null));imports.importExcel(new ByteArrayInputStream(file),"same.xlsx","COMMERCIAL","finance",null);var original=all();int events=observer.committed.size();
    var result=imports.importExcel(new ByteArrayInputStream(file),"same.xlsx","COMMERCIAL","finance",null);
    assertThat(result.getUnchangedRows()).isEqualTo(2);assertThat(result.getInsertedRows()).isZero();assertThat(result.getBatchNo()).isNull();assertThat(all()).isEqualTo(original);assertThat(logs()).isEqualTo(2);assertThat(observer.committed).hasSize(events);
  }
  @Test void increaseDecreaseZeroAndBlankCreateOnlyActualChangedVersions() throws Exception {
    save(row("000A","0.4"));for(String value:List.of("0.6","0.2","0"))save(row("000A",value));
    assertThat((BigDecimal)current("000A").get("supply_ratio")).isEqualByComparingTo("0");save(row("000A",null));assertThat(current("000A").get("supply_ratio")).isNull();assertThat(logs()).isEqualTo(5);
    var latest=queries.changes("000M","000A",null,1,20,null).records().getFirst();var json=new ObjectMapper();assertThat(json.readTree(latest.beforeValue()).get("supplyRatio").decimalValue()).isEqualByComparingTo("0");assertThat(json.readTree(latest.afterValue()).get("supplyRatio").isNull()).isTrue();
    assertThat(save(row("000A",null)).getUnchangedRows()).isEqualTo(1);assertThat(logs()).isEqualTo(5);
  }
  @Test void leadingZerosAndNullAndZeroSurviveTheActualWorkbookParser() throws Exception {
    imports.importExcel(new ByteArrayInputStream(workbook(row("000001","000010",null),row("000001","000011","0"))),"zeros.xlsx","COMMERCIAL","finance",null);
    assertThat(jdbc.queryForList("SELECT material_code FROM lp_supplier_supply_ratio",String.class)).containsOnly("000001");
    assertThat(jdbc.queryForList("SELECT supplier_code FROM lp_supplier_supply_ratio ORDER BY supplier_code",String.class)).containsExactly("000010","000011");
    assertThat(jdbc.queryForObject("SELECT supply_ratio FROM lp_supplier_supply_ratio WHERE supplier_code='000010'",BigDecimal.class)).isNull();assertThat(jdbc.queryForObject("SELECT supply_ratio FROM lp_supplier_supply_ratio WHERE supplier_code='000011'",BigDecimal.class)).isEqualByComparingTo("0");
  }
  @Test void identicalDuplicatesMergeAndConflictingDuplicatesPreserveAllRows() throws Exception {
    var result=imports.importExcel(new ByteArrayInputStream(workbook(row("000A","0.5"),row("000A","0.50"))),"duplicate.xlsx","COMMERCIAL","finance",null);assertThat(result.getDuplicateRows()).isEqualTo(1);assertThat(logs()).isEqualTo(1);var original=all();
    assertThatThrownBy(()->imports.importExcel(new ByteArrayInputStream(workbook(row("000A","0.5"),row("000A","0.6"))),"conflict.xlsx","COMMERCIAL","finance",null)).hasMessageContaining("冲突");assertThat(all()).isEqualTo(original);assertThat(logs()).isEqualTo(1);
  }
  @Test void anyInvalidWorkbookRowRollsBackTheEntireImportBeforeWrites() throws Exception {
    save(row("000A","0.5"));var original=all();
    assertThatThrownBy(()->imports.importExcel(new ByteArrayInputStream(workbook(row("000A","0.6"),row("000B","1.1"))),"invalid.xlsx","COMMERCIAL","finance",null)).hasMessageContaining("第3行");assertThat(all()).isEqualTo(original);assertThat(logs()).isEqualTo(1);
    assertThatThrownBy(()->save(row("000A","0.1234567"))).hasMessageContaining("最多6位");assertThat(all()).isEqualTo(original);
  }
  @Test void lateAuditFailureRollsBackRetirementVersionsAndEarlierLogs() {
    save(row("000A","0.5"),row("000B","0.5"));var original=all();int events=observer.committed.size();
    jdbc.execute("CREATE TRIGGER mrp02_log_failure BEFORE INSERT ON lp_business_change_log FOR EACH ROW BEGIN IF NEW.after_value LIKE '%000B%' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MRP02_LOG_ROLLBACK'; END IF; END");
    try{assertThatThrownBy(()->save(row("000A","0.4"),row("000B","0.6"))).hasStackTraceContaining("MRP02_LOG_ROLLBACK");}finally{jdbc.execute("DROP TRIGGER mrp02_log_failure");}
    assertThat(all()).isEqualTo(original);assertThat(logs()).isEqualTo(2);assertThat(observer.committed).hasSize(events);
    assertThat(save(row("000A","0.4"),row("000B","0.6")).getChangedRelations()).isEqualTo(2);
  }
  @Test void lateVersionFailureAlsoRollsBackPreviousRowsAndLogs() {
    save(row("000A","0.5"));var original=all();
    jdbc.execute("CREATE TRIGGER mrp02_row_failure BEFORE INSERT ON lp_supplier_supply_ratio FOR EACH ROW BEGIN IF NEW.supplier_code='000B' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MRP02_ROW_ROLLBACK'; END IF; END");
    try{assertThatThrownBy(()->save(row("000A","0.4"),row("000B","0.6"))).hasStackTraceContaining("MRP02_ROW_ROLLBACK");}finally{jdbc.execute("DROP TRIGGER mrp02_row_failure");}
    assertThat(all()).isEqualTo(original);assertThat(logs()).isEqualTo(1);
  }
  @Test void eightConcurrentFirstImportsPersistOneCurrentVersionAndOneLog() throws Exception {
    concurrent(8,i->save(row("000A","0.5")));assertThat(all()).hasSize(1);assertThat(logs()).isEqualTo(1);assertThat(observer.committed).hasSize(1);
  }
  @Test void concurrentUpdatesToTheSameRelationProduceOneVersionForTheSameValue() throws Exception {
    save(row("000A","0.5"));concurrent(8,i->save(row("000A","0.8")));assertThat(all()).hasSize(2);assertThat(logs()).isEqualTo(2);assertThat((BigDecimal)current("000A").get("supply_ratio")).isEqualByComparingTo("0.8");
  }
  @Test void concurrentDifferentValuesKeepAContinuousPreviousVersionChain() throws Exception {
    save(row("000A","0.5"));concurrent(2,i->save(row("000A",i==0?"0.6":"0.7")));
    var rows=all();assertThat(rows).hasSize(3);var history=queries.changes("000M","000A",null,1,20,null).records();assertThat(history).hasSize(3);
    assertThat(history.get(0).previousVersionId()).isEqualTo(history.get(1).versionId());assertThat(history.get(1).previousVersionId()).isEqualTo(history.get(2).versionId());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_supplier_supply_ratio WHERE is_active=1",Integer.class)).isEqualTo(1);
  }
  interface ConcurrentImport { Object run(int index); }
  static void concurrent(int count,ConcurrentImport task) throws Exception {
    var barrier=new CyclicBarrier(count);try(var pool=Executors.newFixedThreadPool(count)){
      var futures=new ArrayList<Future<?>>();for(int i=0;i<count;i++){final int index=i;futures.add(pool.submit(()->{auth("COMMERCIAL");try{barrier.await(10,TimeUnit.SECONDS);return task.run(index);}finally{SecurityContextHolder.clearContext();}}));}
      for(var f:futures)f.get(35,TimeUnit.SECONDS);
    }
  }
  @Test void separateUnitsKeepIndependentVersionsAndHistoryAndRejectCrossUnitRequests() {
    save(row("000A","0.5"));auth("HOUSEHOLD");save(row("000A","0.7"));
    assertThat(queries.page("000M",null,null,null,"000A",null,null,1,1,20,null).getRecords()).hasSize(1).allSatisfy(r->assertThat(r.getBusinessUnitType()).isEqualTo("HOUSEHOLD"));
    assertThat(queries.changes("000M","000A",null,1,20,null).total()).isEqualTo(1);
    assertThatThrownBy(()->imports.importRows(List.of(row("000A","0.9")),"cross.xlsx","COMMERCIAL","finance")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(()->queries.changes(null,null,null,1,20,"COMMERCIAL")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(logs()).isEqualTo(2);
  }
  @Test void currentHistoryAndBatchFiltersReconcileWithoutChangingAnyRows() {
    var first=save(row("000A","0.5"),row("000B","0.5"));var changed=save(row("000A","0.6"));var original=all();
    assertThat(queries.page("000M",null,null,null,null,null,null,1,1,20,null).getTotal()).isEqualTo(2);
    assertThat(queries.page("000M",null,null,null,null,null,null,0,1,20,null).getTotal()).isEqualTo(1);
    assertThat(queries.page(null,null,null,null,null,first.getBatchNo(),null,null,1,20,null).getTotal()).isEqualTo(2);
    assertThat(queries.changes(null,null,changed.getBatchNo(),1,20,null).total()).isEqualTo(1);assertThat(all()).isEqualTo(original);assertThat(logs()).isEqualTo(3);
  }
  @Test void metadataChangesAreVersionedButFileOrDecimalScaleChangesAreNot() {
    save(row("000A","0.6"));var changed=row("000A","0.600000");changed.setUnit("套");assertThat(save(changed).getChangedRelations()).isEqualTo(1);var original=all();
    assertThat(imports.importRows(List.of(changed),"renamed.xlsx","COMMERCIAL","another-finance").getUnchangedRows()).isEqualTo(1);assertThat(all()).isEqualTo(original);assertThat(logs()).isEqualTo(2);
  }
  @Test void newVersionsNeedNoDatesAndUnrelatedHistoricalRowsAreNotBackfilled() {
    jdbc.update("INSERT INTO lp_supplier_supply_ratio(material_code,material_name,spec_model,supplier_code,supplier_name,supply_ratio,source_batch_no,is_active) VALUES('LEGACY','旧物料','SPEC','OLD','旧供应商',0.2,'old-batch',0)");var old=all().getFirst();
    save(row("000A","0.5"));assertThat(current("000A").get("effective_from")).isNull();assertThat(current("000A").get("effective_to")).isNull();assertThat(all().getFirst()).isEqualTo(old);assertThat(queries.changes("LEGACY",null,null,1,20,null).total()).isZero();
  }
  @Test void duplicateExistingCurrentVersionsAreReportedWithoutDeletingHistory() {
    save(row("000A","0.5"));jdbc.update("INSERT INTO lp_supplier_supply_ratio(material_code,material_name,spec_model,supplier_code,supplier_name,supply_ratio,source_batch_no) VALUES('000M','物料000M','SPEC','000A','供应商000A',0.3,'legacy-duplicate')");var old=all();assertThatThrownBy(()->save(row("000A","0.6"))).hasMessageContaining("多个当前版本");assertThat(all()).isEqualTo(old);assertThat(logs()).isEqualTo(1);
  }
  @Test void importPublishesOnlyChangedMaterialsAfterCommit() {
    save(row("000M","000A","0.5"));observer.committed.clear();
    save(row("000M","000A","0.50"),row("000N","000C","0.2"));assertThat(observer.committed).hasSize(1);assertThat(observer.committed.getFirst().materialCodes()).containsExactly("000N");
  }
  @Test void financePermissionPatchPreservesRetiredAndOtherRolesAndIsIdempotent() {
    jdbc.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,role_key VARCHAR(64),status CHAR(1),del_flag CHAR(1))");
    jdbc.execute("CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,parent_id BIGINT,perms VARCHAR(128))");
    jdbc.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,PRIMARY KEY(role_id,menu_id))");
    jdbc.update("INSERT INTO sys_role VALUES(4,'finance_reviewer','0','2'),(13,'finance_reviewer','0','0'),(14,'finance_reviewer','1','0'),(11,'bu_staff','0','0')");
    jdbc.update("INSERT INTO sys_menu VALUES(1,5,'base:supplier-supply-ratio:list'),(2,1,'base:supplier-supply-ratio:import'),(3,0,'unrelated:permission'),(5,6,NULL),(6,0,NULL),(7,1,'base:supplier-supply-ratio:edit')");
    jdbc.update("INSERT INTO sys_role_menu VALUES(4,1),(11,3),(13,6)");
    var patch=new ResourceDatabasePopulator(new ClassPathResource("db/V349__supplier_ratio_finance_import_permission.sql"));
    patch.execute(ds);patch.execute(ds);
    assertThat(jdbc.queryForList("SELECT menu_id FROM sys_role_menu WHERE role_id=13 ORDER BY menu_id",Long.class)).containsExactly(1L,2L,5L,6L);
    assertThat(jdbc.queryForList("SELECT menu_id FROM sys_role_menu WHERE role_id=4 ORDER BY menu_id",Long.class)).containsExactly(1L);
    assertThat(jdbc.queryForList("SELECT menu_id FROM sys_role_menu WHERE role_id=14",Long.class)).isEmpty();
    assertThat(jdbc.queryForList("SELECT menu_id FROM sys_role_menu WHERE role_id=11",Long.class)).containsExactly(3L);
  }
}
