package com.sanhua.marketingcost.service.pricing.household;

import static org.assertj.core.api.Assertions.*;
import static com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.monthly.HouseholdInquiryStorageMapper;
import com.sanhua.marketingcost.mapper.household.HouseholdInquiryQueryMapper;
import com.sanhua.marketingcost.service.pricing.monthly.HouseholdInquiryStorage;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import com.sanhua.marketingcost.service.pricing.monthly.TaxBasis;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.*;

@Tag("integration")
class HouseholdInquiryPublicationIntegrationTest {
  static final PriceContext CONTEXT=new PriceContext("件","CNY",TaxBasis.EXCLUDED);
  static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("mpp_source_test").withUsername("root").withPassword("mpp-source-test-only").withConfigurationOverride("monthly-pricing/mysql");
  static org.apache.ibatis.session.Configuration mappedConfiguration;
  static JdbcTemplate jdbc;static HouseholdInquiryService service;static HouseholdInquiryQueryMapper queries;static DataSourceTransactionManager transactions;
  static HouseholdInquiryStorage storage;static HouseholdInquiryFiles files;static MutableClock clock=new MutableClock();static Path archive;
  static class MutableClock extends Clock { volatile Instant time=Instant.parse("2026-10-23T10:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return time;} }
  @BeforeAll static void setup() throws Exception {
    MYSQL.start();var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());jdbc=new JdbcTemplate(ds);
    assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/pre-v342-schema.sql")).execute(ds);
    MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/V342__monthly_material_price_storage.sql"),"/tmp/source-storage.sql");
    assertThat(MYSQL.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 mpp_source_test < /tmp/source-storage.sql").getExitCode()).isZero();
    var config=new org.apache.ibatis.session.Configuration();mappedConfiguration=config;config.setMapUnderscoreToCamelCase(true);config.addMapper(HouseholdInquiryQueryMapper.class);config.addMapper(HouseholdInquiryStorageMapper.class);
    var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);var sql=new SqlSessionTemplate(factory.getObject());
    var json=new ObjectMapper();var codec=new MonthlyPriceDocumentCodec(json);queries=sql.getMapper(HouseholdInquiryQueryMapper.class);
    storage=new HouseholdInquiryStorage(sql.getMapper(HouseholdInquiryStorageMapper.class),codec);transactions=new DataSourceTransactionManager(ds);
    archive=Files.createTempDirectory("mpp03-archive-");files=new HouseholdInquiryFiles(archive.toString());
    service=new HouseholdInquiryService(queries,storage,new HouseholdInquiryWorkbookParser(),files,codec,transactions,json,clock);
  }
  @AfterAll static void stop(){MYSQL.stop();}
  static byte[] source(String material,String price) throws Exception {
    var book=HouseholdInquiryWorkbookParserTest.workbook();var row=book.getSheetAt(0).getRow(2);row.getCell(0).setCellValue(material);row.getCell(4).setCellValue(price);
    return HouseholdInquiryWorkbookParserTest.bytes(book);
  }
  static Preview preview(String material,String price) throws Exception {return service.preview("COMMERCIAL","finance",source(material,price),"synthetic.xls","2026-10");}
  static PublishRequest request(Preview p){return new PublishRequest(p.previewId(),p.publishRequestId(),p.previewFingerprint());}
  static PublishResult publish(Preview p){return service.publish("COMMERCIAL","finance",request(p));}
  static String material(){return "001Ab-"+UUID.randomUUID().toString().substring(0,8);}
  static void failure(Runnable call,String code){assertThatThrownBy(call::run).isInstanceOfSatisfying(HouseholdInquiryFailure.class,e->assertThat(e.code()).isEqualTo(code));}

  @Test void realOriginalPreviewsSeventeenRowsAndPublishesWithoutWritingAnyMonthlyPrice() throws Exception {
    byte[] original=HouseholdInquiryPublicationIntegrationTest.class.getResourceAsStream("/household-inquiry/real-20261023.xls").readAllBytes();
    long versions=jdbc.queryForObject("SELECT COUNT(*) FROM lp_household_inquiry_price",Long.class);
    var p=service.preview("COMMERCIAL","finance",original,"real.xls","2026-10");
    assertThat(p.content().rows()).hasSize(17);assertThat(p.content().workbook().errors()).isEmpty();
    assertThat(p.content().priceContext()).isEqualTo(CONTEXT);
    var batch=storage.batch("COMMERCIAL",p.previewId()).orElseThrow();
    assertThat(batch.getPricingUnit()).isEqualTo("件");assertThat(batch.getCurrency()).isEqualTo("CNY");assertThat(batch.getTaxBasis()).isEqualTo("EXCLUDED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_household_inquiry_price",Long.class)).isEqualTo(versions);
    var r=publish(p);assertThat(r.newVersions()).isEqualTo(17);assertThat(r.rows()).allSatisfy(row->assertThat(row.action()).isEqualTo(Action.CREATE));
    for(var row:p.content().rows()) {
      var view=service.current("COMMERCIAL",row.source().materialCode(),1,20).rows().getFirst();
      assertThat(view.unitPrice()).isEqualTo(row.source().unitPrice());assertThat(view.householdRate()).isEqualTo("0.085");
      assertThat(view.priceContext()).isEqualTo(CONTEXT);
      assertThat(service.financial("COMMERCIAL",view.id()).evidence().priceContext()).isEqualTo(CONTEXT);
      assertThat(service.original("COMMERCIAL",view.id()).bytes()).isEqualTo(original);
    }
    for(String t:List.of("lp_material_monthly_family","lp_material_monthly_price","lp_material_monthly_adoption","lp_material_monthly_price_ref")) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+t,Long.class)).isZero();
  }
  @Test void sameValuesAreObservationsAndReplayingAnOlderFileCreatesANewVersion() throws Exception {
    String m=material();byte[] original=source(m,"10.000");var first=service.preview("COMMERCIAL","finance",original,"first.xls","2026-10");var a=publish(first);
    var same=preview(m,"10.000");assertThat(publish(same).newVersions()).isZero();
    var observation=service.importDetails("COMMERCIAL","another-finance",same.batchId());
    assertThat(observation.publication().rows().getFirst().versionId()).isEqualTo(a.rows().getFirst().versionId());
    assertThat(observation.publication().newVersions()).isZero();assertThat(observation.preview().batchId()).isEqualTo(same.batchId());
    assertThat(service.financial("COMMERCIAL",a.rows().getFirst().versionId()).fileName()).isEqualTo("first.xls");
    assertThat(service.history("COMMERCIAL",m,1,100).rows()).hasSize(1);
    var b=publish(preview(m,"11"));var back=service.preview("COMMERCIAL","finance",original,"same-old-bytes.xls","2026-10");assertThat(back.content().workbook().sha256()).isEqualTo(first.content().workbook().sha256());assertThat(back.content().rows().getFirst().historicalValue()).isTrue();
    var c=publish(back);assertThat(c.rows().getFirst().revisionNo()).isEqualTo(3);assertThat(c.rows().getFirst().versionId()).isNotEqualTo(a.rows().getFirst().versionId());
    assertThat(service.publish("COMMERCIAL","finance",request(first))).isEqualTo(a);
    assertThat(service.current("COMMERCIAL",m,1,20).rows().getFirst().id()).isEqualTo(c.rows().getFirst().versionId());
    assertThat(service.history("COMMERCIAL",m,1,100).rows()).extracting(PriceView::revisionNo).containsExactly(3,2,1);
    assertThat(service.price("COMMERCIAL",b.rows().getFirst().versionId()).current()).isFalse();
  }
  @Test void addingOnlyAnEmptyStyledColumnDoesNotCreateAReferenceVersion() throws Exception {
    String m=material();publish(preview(m,"12"));
    var book=HouseholdInquiryWorkbookParserTest.workbook();var sheet=book.getSheetAt(0);sheet.getRow(2).getCell(0).setCellValue(m);sheet.getRow(2).getCell(4).setCellValue("12");
    var style=book.createCellStyle();style.setDataFormat(book.createDataFormat().getFormat("0.0000"));
    sheet.getRow(2).createCell(25,org.apache.poi.ss.usermodel.CellType.BLANK).setCellStyle(style);sheet.getRow(1).createCell(25,org.apache.poi.ss.usermodel.CellType.BLANK).setCellStyle(style);
    var p=service.preview("COMMERCIAL","finance",HouseholdInquiryWorkbookParserTest.bytes(book),"style-only.xls","2026-10");
    assertThat(p.content().rows().getFirst().action()).isEqualTo(Action.UNCHANGED);
    assertThat(publish(p).newVersions()).isZero();assertThat(service.history("COMMERCIAL",m,1,100).rows()).hasSize(1);
  }

  @Test void metadataChangesCreateAReferenceVersionWithoutClaimingAPriceChange() throws Exception {
    String m=material();publish(preview(m,"12"));
    var p=service.preview("COMMERCIAL","finance",source(m,"12.0"),"changed-month.xls","2026-09");
    assertThat(p.content().rows().getFirst().action()).isEqualTo(Action.NEW_VERSION);assertThat(p.content().rows().getFirst().priceChanged()).isFalse();
    var r=publish(p);assertThat(r.newVersions()).isEqualTo(1);assertThat(service.current("COMMERCIAL",m,1,20).rows().getFirst().sourceMonth()).isEqualTo("2026-09");
  }
  @Test void aChangedTargetMakesTheEntirePreviewStaleEvenIfThePriceIsEqual() throws Exception {
    String m=material();publish(preview(m,"12"));var old=preview(m,"14");publish(preview(m,"14"));
    failure(()->publish(old),"STALE_PREVIEW");assertThat(service.history("COMMERCIAL",m,1,100).rows()).hasSize(2);
    assertThat(service.preview("COMMERCIAL","finance",old.previewId()).status()).isEqualTo("PREVIEW");
  }
  @Test void anUnrelatedMaterialDoesNotInvalidateThePreview() throws Exception {
    var p=preview(material(),"20");publish(preview(material(),"40"));assertThat(publish(p).newVersions()).isEqualTo(1);
  }
  @Test void requestIdentityAndOwnerCannotBeSubstituted() throws Exception {
    var p=preview(material(),"20");
    failure(()->service.publish("COMMERCIAL","other-finance",request(p)),"PREVIEW_OWNER_MISMATCH");
    failure(()->service.publish("HOUSEHOLD","finance",request(p)),"PREVIEW_NOT_FOUND");
    failure(()->service.publish("COMMERCIAL","finance",new PublishRequest(p.previewId(),p.publishRequestId(),"0".repeat(64))),"IDEMPOTENCY_CONFLICT");
    var other=preview(material(),"25");failure(()->service.publish("COMMERCIAL","finance",new PublishRequest(other.previewId(),p.publishRequestId(),p.previewFingerprint())),"IDEMPOTENCY_CONFLICT");
  }
  @Test void expiredPreviewsRejectButSuccessfulRequestsRecoverAfterExpiry() throws Exception {
    var pending=preview(material(),"20");var done=preview(material(),"21");var result=publish(done);
    Instant before=clock.time;try{clock.time=clock.time.plusSeconds(1801);failure(()->publish(pending),"PREVIEW_EXPIRED");assertThat(publish(done)).isEqualTo(result);}finally{clock.time=before;}
  }
  @Test void partialInvalidFilesCannotPublishAnyOtherwiseValidRows() throws Exception {
    var book=HouseholdInquiryWorkbookParserTest.workbook();var row=book.getSheetAt(0).createRow(3);row.createCell(0).setCellValue(material());row.createCell(4).setCellValue("-1");row.createCell(13).setCellValue(8.5);
    var p=service.preview("COMMERCIAL","finance",HouseholdInquiryWorkbookParserTest.bytes(book),"invalid.xls","2026-10");
    long count=jdbc.queryForObject("SELECT COUNT(*) FROM lp_household_inquiry_price",Long.class);
    failure(()->publish(p),"PREVIEW_HAS_ERRORS");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_household_inquiry_price",Long.class)).isEqualTo(count);
  }
  @Test void competingFirstPublicationsHaveOneWinnerAndNoHalfPublishedBatch() throws Exception {
    String m=material();var p=preview(m,"1");var q=preview(m,"2");var start=new CountDownLatch(2);
    try(var pool=Executors.newFixedThreadPool(2)) {
      var futures=new ArrayList<Future<String>>();for(var item:List.of(p,q))futures.add(pool.submit(()->{start.countDown();assertThat(start.await(5,TimeUnit.SECONDS)).isTrue();try{publish(item);return "PUBLISHED";}catch(HouseholdInquiryFailure e){return e.code();}}));
      assertThat(List.of(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("PUBLISHED","STALE_PREVIEW");
    }
    assertThat(service.history("COMMERCIAL",m,1,100).rows()).hasSize(1);
  }
  @Test void missingArchivedOriginalPreventsAllPublicationWrites() throws Exception {
    var p=preview(material(),"22");Path path=archive.resolve("COMMERCIAL").resolve(p.content().workbook().sha256()).resolve("workbook.bin");Files.delete(path);
    long count=jdbc.queryForObject("SELECT COUNT(*) FROM lp_household_inquiry_price",Long.class);
    assertThatThrownBy(()->publish(p)).isInstanceOf(IllegalStateException.class);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_household_inquiry_price",Long.class)).isEqualTo(count);
  }
  @Test void failureAfterAnEarlierTargetWasWrittenRestoresEveryPreviousCurrentAndTheBatch() throws Exception {
    String first="A-"+material(),second="Z-"+material();publish(preview(first,"1"));publish(preview(second,"2"));
    var book=HouseholdInquiryWorkbookParserTest.workbook();var sheet=book.getSheetAt(0);sheet.getRow(2).getCell(0).setCellValue(first);sheet.getRow(2).getCell(4).setCellValue("3");
    var next=sheet.createRow(3);for(var cell:sheet.getRow(2)){var c=next.createCell(cell.getColumnIndex());if(cell.getCellType()==org.apache.poi.ss.usermodel.CellType.STRING)c.setCellValue(cell.getStringCellValue());else c.setCellValue(cell.getNumericCellValue());}next.getCell(0).setCellValue(second);
    var p=service.preview("COMMERCIAL","finance",HouseholdInquiryWorkbookParserTest.bytes(book),"atomic.xls","2026-10");
    jdbc.execute("CREATE TRIGGER mpp03_injected_failure BEFORE INSERT ON lp_household_inquiry_price FOR EACH ROW BEGIN IF NEW.material_code='"+second+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP03_INJECTED_SECOND_ROW'; END IF; END");
    try{assertThatThrownBy(()->publish(p)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("MPP03_INJECTED_SECOND_ROW");}finally{jdbc.execute("DROP TRIGGER mpp03_injected_failure");}
    assertThat(service.history("COMMERCIAL",first,1,100).rows()).hasSize(1);assertThat(service.history("COMMERCIAL",second,1,100).rows()).hasSize(1);
    assertThat(service.current("COMMERCIAL",first,1,20).rows().getFirst().unitPrice()).isEqualTo("1");
    assertThat(service.current("COMMERCIAL",second,1,20).rows().getFirst().unitPrice()).isEqualTo("2");
    assertThat(service.preview("COMMERCIAL","finance",p.previewId()).status()).isEqualTo("PREVIEW");
    assertThat(jdbc.queryForObject("SELECT result_json FROM lp_household_inquiry_import_batch WHERE id=?",String.class,p.batchId())).isNull();
  }
  @Test void aLostCommitAcknowledgementRecoversTheActualCommittedReceipt() throws Exception {
    String m=material();var p=preview(m,"19");
    var acknowledgementLoss=new org.springframework.transaction.PlatformTransactionManager(){
      public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition){return transactions.getTransaction(definition);}
      public void commit(org.springframework.transaction.TransactionStatus status){transactions.commit(status);throw new org.springframework.transaction.TransactionSystemException("controlled-lost-commit-acknowledgement");}
      public void rollback(org.springframework.transaction.TransactionStatus status){transactions.rollback(status);}
    };
    var uncertain=new HouseholdInquiryService(queries,storage,new HouseholdInquiryWorkbookParser(),files,new MonthlyPriceDocumentCodec(new ObjectMapper()),acknowledgementLoss,new ObjectMapper(),clock);
    var recovered=uncertain.publish("COMMERCIAL","finance",request(p));assertThat(publish(p)).isEqualTo(recovered);
    assertThat(service.history("COMMERCIAL",m,1,100).rows()).hasSize(1);
  }
  @Test void aRetryAfterExpiryRecoversACommitThatWasPendingWhenItReadThePreview() throws Exception {
    String m=material();var p=preview(m,"27");Instant before=clock.time;
    var written=new CountDownLatch(1);var allowCommit=new CountDownLatch(1);var committed=new CountDownLatch(1);var readPending=new CountDownLatch(1);
    var gatedCommit=new org.springframework.transaction.PlatformTransactionManager(){
      public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition){return transactions.getTransaction(definition);}
      public void commit(org.springframework.transaction.TransactionStatus status){
        written.countDown();try{if(!allowCommit.await(5,TimeUnit.SECONDS))throw new AssertionError("commit gate timed out");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
        transactions.commit(status);committed.countDown();
      }
      public void rollback(org.springframework.transaction.TransactionStatus status){transactions.rollback(status);committed.countDown();}
    };
    var staleObservation=(HouseholdInquiryQueryMapper)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{HouseholdInquiryQueryMapper.class},(proxy,method,args)->{
      Object value;try{value=method.invoke(queries,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
      if(method.getName().equals("batchByRequest")) {
        assertThat(((com.sanhua.marketingcost.entity.monthly.HouseholdInquiryBatchRow)value).getStatus()).isEqualTo("PREVIEW");
        readPending.countDown();assertThat(committed.await(5,TimeUnit.SECONDS)).isTrue();
      }
      return value;
    });
    var firstService=new HouseholdInquiryService(queries,storage,new HouseholdInquiryWorkbookParser(),files,new MonthlyPriceDocumentCodec(new ObjectMapper()),gatedCommit,new ObjectMapper(),clock);
    var retryService=new HouseholdInquiryService(staleObservation,storage,new HouseholdInquiryWorkbookParser(),files,new MonthlyPriceDocumentCodec(new ObjectMapper()),transactions,new ObjectMapper(),clock);
    try(var pool=Executors.newFixedThreadPool(2)) {
      clock.time=p.expiresAt().minusSeconds(1);
      var first=pool.submit(()->firstService.publish("COMMERCIAL","finance",request(p)));
      assertThat(written.await(5,TimeUnit.SECONDS)).isTrue();clock.time=p.expiresAt();
      var retry=pool.submit(()->retryService.publish("COMMERCIAL","finance",request(p)));
      assertThat(readPending.await(5,TimeUnit.SECONDS)).isTrue();allowCommit.countDown();
      var expected=first.get(10,TimeUnit.SECONDS);
      try{assertThat(retry.get(10,TimeUnit.SECONDS)).isEqualTo(expected);}
      catch(ExecutionException failure){throw new AssertionError("Expired retry must recover the original committed receipt after its stale PREVIEW observation",failure.getCause());}
      assertThat(service.history("COMMERCIAL",m,1,100).rows()).hasSize(1);
    } finally { allowCommit.countDown();committed.countDown();clock.time=before; }
  }

  @Test void anExpiredRecoveryLockFailureCannotClaimThatTheOriginalRequestFailed() throws Exception {
    var p=preview(material(),"28");Instant before=clock.time;
    var unavailableLock=(HouseholdInquiryQueryMapper)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{HouseholdInquiryQueryMapper.class},(proxy,method,args)->{
      if(method.getName().equals("lockBatch"))throw new org.springframework.dao.CannotAcquireLockException("controlled-expired-recovery-lock-failure");
      try{return method.invoke(queries,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
    });
    var recovery=new HouseholdInquiryService(unavailableLock,storage,new HouseholdInquiryWorkbookParser(),files,new MonthlyPriceDocumentCodec(new ObjectMapper()),transactions,new ObjectMapper(),clock);
    try {
      clock.time=p.expiresAt();
      assertThatThrownBy(()->recovery.publish("COMMERCIAL","finance",request(p))).isInstanceOfSatisfying(HouseholdInquiryFailure.class,error->{
        assertThat(error.code()).isEqualTo("PUBLICATION_UNCERTAIN");assertThat(error.status()).isEqualTo(503);
        assertThat(error.getCause()).isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
      });
    } finally {clock.time=before;}
  }

  @Test void anExpiredPreviewIsRejectedBeforeTryingToReadItsMissingArchive() throws Exception {
    var p=preview(material(),"8");Files.delete(archive.resolve("COMMERCIAL").resolve(p.content().workbook().sha256()).resolve("workbook.bin"));
    Instant before=clock.time;try{clock.time=clock.time.plusSeconds(1800);failure(()->publish(p),"PREVIEW_EXPIRED");}finally{clock.time=before;}
  }

  @Test void recoveryQueryFailureKeepsTheUnknownCommitOutcomeAndOriginalRequestUsable() throws Exception {
    String m=material();var p=preview(m,"33");var committed=new java.util.concurrent.atomic.AtomicBoolean();
    var unavailableQueries=(HouseholdInquiryQueryMapper)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{HouseholdInquiryQueryMapper.class},(proxy,method,args)->{
      if(committed.get()&&method.getName().equals("batchByRequest"))throw new org.springframework.dao.DataAccessResourceFailureException("controlled-recovery-connection-down");
      try{return method.invoke(queries,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
    });
    var acknowledgementLoss=new org.springframework.transaction.PlatformTransactionManager(){
      public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition){return transactions.getTransaction(definition);}
      public void commit(org.springframework.transaction.TransactionStatus status){transactions.commit(status);committed.set(true);throw new org.springframework.transaction.TransactionSystemException("controlled-lost-commit-acknowledgement");}
      public void rollback(org.springframework.transaction.TransactionStatus status){transactions.rollback(status);}
    };
    var uncertain=new HouseholdInquiryService(unavailableQueries,storage,new HouseholdInquiryWorkbookParser(),files,new MonthlyPriceDocumentCodec(new ObjectMapper()),acknowledgementLoss,new ObjectMapper(),clock);
    assertThatThrownBy(()->uncertain.publish("COMMERCIAL","finance",request(p))).isInstanceOfSatisfying(HouseholdInquiryFailure.class,e->{
      assertThat(e.code()).isEqualTo("PUBLICATION_UNCERTAIN");assertThat(e.status()).isEqualTo(503);assertThat(e.getCause()).isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
      assertThat(e.getSuppressed()).singleElement().isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    });
    assertThat(publish(p).newVersions()).isEqualTo(1);assertThat(service.history("COMMERCIAL",m,1,100).rows()).hasSize(1);
  }
  @Test void oversizedFinalSourceEvidenceIsARowErrorDuringPreview() throws Exception {
    var book=HouseholdInquiryWorkbookParserTest.workbook();var row=book.getSheetAt(0).getRow(2);row.getCell(0).setCellValue(material());
    for(int c=0;c<19;c++)if(!Set.of(0,1,2,4,13).contains(c)) {var cell=row.getCell(c);if(cell==null)cell=row.createCell(c);cell.setCellValue("财".repeat(16000));}
    var p=service.preview("COMMERCIAL","finance",HouseholdInquiryWorkbookParserTest.bytes(book),"oversized-evidence.xls","2026-10");
    assertThat(p.content().workbook().errors()).singleElement().satisfies(e->{assertThat(e.excelRow()).isEqualTo(3);assertThat(e.column()).isEqualTo("A:S");});
    failure(()->publish(p),"PREVIEW_HAS_ERRORS");
  }

  @Test void currentPaginationUsesTheGeneratedCurrentRangeIndexInProductionSql() throws Exception {
    String historical=material();
    for(int version=0;version<50;version++)publish(preview(historical,Integer.toString(version+1)));
    var parameters=new HashMap<String,Object>();parameters.put("unit","COMMERCIAL");parameters.put("material",null);parameters.put("size",20);parameters.put("offset",0);
    for(String method:List.of("page","count")) {
      var bound=mappedConfiguration.getMappedStatement(HouseholdInquiryQueryMapper.class.getName()+"."+method).getBoundSql(parameters);
      Object[] arguments=bound.getParameterMappings().stream().map(mapping->parameters.get(mapping.getProperty())).toArray();
      var plan=jdbc.queryForList("EXPLAIN FORMAT=TRADITIONAL "+bound.getSql(),arguments);
      assertThat(plan).filteredOn(row->"p".equals(row.get("table"))).singleElement().satisfies(row->{
        assertThat(row.get("key")).as("production %s EXPLAIN: %s",method,plan).isEqualTo("uk_household_current");assertThat(row.get("type")).isEqualTo("range");
      });
    }
  }

  @Test void partialImportsPreserveAbsentMaterialsAndReferenceAndRateChangesAreDistinct() throws Exception {
    String absent=material(),changed=material();var untouched=publish(preview(absent,"4"));var original=publish(preview(changed,"5"));
    var book=HouseholdInquiryWorkbookParserTest.workbook();var row=book.getSheetAt(0).getRow(2);row.getCell(0).setCellValue(changed);row.getCell(4).setCellValue("5.0");row.getCell(17).setCellValue("888");
    var p=service.preview("COMMERCIAL","finance",HouseholdInquiryWorkbookParserTest.bytes(book),"reference-only.xls","2026-10");assertThat(p.content().rows().getFirst().priceChanged()).isFalse();
    assertThat(publish(p).newVersions()).isEqualTo(1);assertThat(service.financial("COMMERCIAL",original.rows().getFirst().versionId()).evidence().source().financialReference().get("R").value()).isEqualTo("999999");
    assertThat(service.current("COMMERCIAL",absent,1,20).rows().getFirst().id()).isEqualTo(untouched.rows().getFirst().versionId());
    book=HouseholdInquiryWorkbookParserTest.workbook();row=book.getSheetAt(0).getRow(2);row.getCell(0).setCellValue(changed);row.getCell(4).setCellValue("5");row.getCell(13).setCellValue("9.25%");
    var rate=service.preview("COMMERCIAL","finance",HouseholdInquiryWorkbookParserTest.bytes(book),"rate-change.xls","2026-10");assertThat(rate.content().rows().getFirst().priceChanged()).isTrue();publish(rate);
    assertThat(service.current("COMMERCIAL",changed,1,20).rows().getFirst().householdRate()).isEqualTo("0.0925");
  }
  @Test void oppositeFileOrdersCannotProduceAMixtureOfTwoCompetingMultiMaterialBatches() throws Exception {
    String a="A-"+material(),b="Z-"+material();var previews=new ArrayList<Preview>();
    for(int variant=0;variant<2;variant++) {
      var book=HouseholdInquiryWorkbookParserTest.workbook();var sheet=book.getSheetAt(0);var row=sheet.getRow(2);row.getCell(0).setCellValue(variant==0?a:b);row.getCell(4).setCellValue(Integer.toString(variant+1));
      var next=sheet.createRow(3);for(var cell:row){var copy=next.createCell(cell.getColumnIndex());if(cell.getCellType()==org.apache.poi.ss.usermodel.CellType.STRING)copy.setCellValue(cell.getStringCellValue());else copy.setCellValue(cell.getNumericCellValue());}next.getCell(0).setCellValue(variant==0?b:a);
      previews.add(service.preview("COMMERCIAL","finance",HouseholdInquiryWorkbookParserTest.bytes(book),"opposite-order.xls","2026-10"));
    }
    var ready=new CountDownLatch(2);
    try(var pool=Executors.newFixedThreadPool(2)) {
      var futures=new ArrayList<Future<String>>();for(var p:previews)futures.add(pool.submit(()->{ready.countDown();assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();try{publish(p);return "PUBLISHED";}catch(HouseholdInquiryFailure e){return e.code();}}));
      assertThat(List.of(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("PUBLISHED","STALE_PREVIEW");
    }
    var left=service.current("COMMERCIAL",a,1,20).rows().getFirst();var right=service.current("COMMERCIAL",b,1,20).rows().getFirst();
    assertThat(left.importBatchId()).isEqualTo(right.importBatchId());assertThat(left.unitPrice()).isEqualTo(right.unitPrice());
    assertThat(service.history("COMMERCIAL",a,1,100).rows()).hasSize(1);assertThat(service.history("COMMERCIAL",b,1,100).rows()).hasSize(1);
  }

  @Test void cancelEndsOnlyThePreviewAndAllReadModelsRemainBusinessUnitScoped() throws Exception {
    String m=material();var published=publish(preview(m,"2"));var p=preview(m,"3");service.cancel("COMMERCIAL","finance",p.previewId());
    failure(()->publish(p),"PREVIEW_CLOSED");assertThat(service.history("COMMERCIAL",m,1,100).rows()).hasSize(1);
    assertThat(service.current("HOUSEHOLD",m,1,20).rows()).isEmpty();assertThat(service.history("HOUSEHOLD",m,1,100).rows()).isEmpty();
    long id=published.rows().getFirst().versionId();failure(()->service.price("HOUSEHOLD",id),"SOURCE_NOT_FOUND");failure(()->service.original("HOUSEHOLD",id),"SOURCE_NOT_FOUND");
    assertThat(service.financial("COMMERCIAL",id).evidence().source().financialReference()).containsKey("R");
  }
}
