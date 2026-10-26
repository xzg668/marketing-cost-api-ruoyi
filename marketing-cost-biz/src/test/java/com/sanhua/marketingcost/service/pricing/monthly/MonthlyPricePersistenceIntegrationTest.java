package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.mapper.monthly.MonthlyPriceStorageMapper;
import com.sanhua.marketingcost.mapper.monthly.HouseholdInquiryStorageMapper;
import com.sanhua.marketingcost.mapper.monthly.MonthlyPriceLinkMapper;
import com.sanhua.marketingcost.entity.monthly.HouseholdInquiryBatchRow;
import com.sanhua.marketingcost.entity.monthly.HouseholdInquiryPriceRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/** Production DDL and Mapper boundary; database behavior is never replaced with a mock. */
@Tag("integration")
class MonthlyPricePersistenceIntegrationTest {
  private static final String MIGRATION = "db/V342__monthly_material_price_storage.sql";
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(org.testcontainers.utility.DockerImageName
      .parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("mpp_storage_test").withUsername("root").withPassword("mpp-test-only")
      .withConfigurationOverride("monthly-pricing/mysql");
  private static JdbcTemplate jdbc;
  private static TransactionTemplate transaction;
  private static MonthlyPriceStorage storage;
  private static MonthlyPriceStorageMapper mapper;
  private static HouseholdInquiryStorage household;

  @BeforeAll static void database() {
    MYSQL.start();
    var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("SELECT @@version",String.class))
        .startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    // Only the external historical FK identity is needed; technical lifecycle is not under test.
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");
    jdbc.update("INSERT INTO lp_quote_tech_data_version(id) VALUES(1)");
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/pre-v342-schema.sql"))
        .execute(dataSource);
    jdbc.update("INSERT INTO lp_quote_cost_run_version(id,cost_run_no,oa_no,product_code,pricing_month,result_period,status,total_cost,tech_data_input_json,business_unit_type,tech_data_version_id,tech_data_version_no,tech_data_source,tech_data_retrieved_at) "
        + "VALUES(1,'legacy-mpp02','MPP02-LEGACY','001Aa','2026-10','2026-10','SUCCESS',12.123456,?, 'COMMERCIAL',1,1,'QUOTE_TECH_EFFECTIVE_VERSION','2026-10-01 00:00:00')",
        "{\"productFees\":{\"included\":false,\"unitMouldFee\":0}}");
  }

  @AfterAll static void stop() { MYSQL.stop(); }

  static void storage() throws Exception {
    if (storage != null) return;
    var configuration = new org.apache.ibatis.session.Configuration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.addMapper(MonthlyPriceStorageMapper.class);
    configuration.addMapper(HouseholdInquiryStorageMapper.class);
    configuration.addMapper(MonthlyPriceLinkMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(jdbc.getDataSource());
    factory.setConfiguration(configuration);
    mapper = new SqlSessionTemplate(factory.getObject()).getMapper(MonthlyPriceStorageMapper.class);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    transaction.setIsolationLevelName("ISOLATION_REPEATABLE_READ");
    transaction.setTimeout(3);
    storage = new MonthlyPriceStorage(mapper, new MonthlyPriceDocumentCodec(new ObjectMapper()),
        new SqlSessionTemplate(factory.getObject()).getMapper(MonthlyPriceLinkMapper.class));
    household = new HouseholdInquiryStorage(new SqlSessionTemplate(factory.getObject()).getMapper(HouseholdInquiryStorageMapper.class),
        new MonthlyPriceDocumentCodec(new ObjectMapper()));
  }

  static boolean migrated;
  static synchronized void migrate() throws Exception {
    if(migrated)return;
    for(String file:List.of(MIGRATION,"db/V344__monthly_material_price_single_table.sql")) {
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource(file),"/tmp/mpp-storage.sql");
      var result=MYSQL.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 mpp_storage_test < /tmp/mpp-storage.sql");
      assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
    }
    migrated=true;
  }
  @Test void singleTableMigrationPreservesExistingAmountsAndJson() throws Exception {
    var before=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=1");
    migrate();
    var after=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=1");
    before.forEach((key,value)->assertThat(after.get(key)).as(key).isEqualTo(value));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'lp_material_monthly_%'",Integer.class)).isEqualTo(1);
    assertThat(after.get("jianji_snapshot_json")).isNull();
  }

  @Test void storedFinancialFactRoundTripsExactIdentityCoverageAndEighteenDecimals() throws Exception {
    migrate(); storage();
    var key = new MonthlyPriceFamilyKey(YearMonth.of(2026,10), "COMMERCIAL", "000AbC", null,
        "件", "CNY", TaxBasis.EXCLUDED, null);
    var coverage = MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210","220"),
        QuantityInterval.all(), ConditionDomain.any()));
    var fact = MonthlyAdoptionPlan.PriceFact.normal(new BigDecimal("99999999999999999999.123456789012345678"),
        MonthlyAdoptionPlan.SourceEvidence.ofJson("{\"sourceKind\":\"SCRAP\",\"price\":99999999999999999999.123456789012345678}"), Set.of());
    transaction.executeWithoutResult(status -> {
      storage.lockFamilies(List.of(key));
      var saved = storage.insertPrice(key, coverage, fact, null, "{\"requestId\":\"roundtrip\"}", "test");
      var restored = storage.readByIds("COMMERCIAL", Set.of(saved.id())).getFirst();
      assertThat(restored.family()).isEqualTo(key);
      assertThat(restored.applicability()).isEqualTo(coverage);
      assertThat(restored.unitPrice()).isEqualByComparingTo("99999999999999999999.123456789012345678");
      assertThat(restored.evidence()).isEqualTo(fact.evidence());
      assertThat(storage.readByIds("HOUSEHOLD", Set.of(saved.id()))).isEmpty();
      status.setRollbackOnly();
    });
  }

  static MonthlyPriceStorage.StoredPrice price() {
    var key = new MonthlyPriceFamilyKey(YearMonth.of(2026,10), "COMMERCIAL", "MPP02-"+java.util.UUID.randomUUID(),
        null,"件","CNY",TaxBasis.EXCLUDED,null);
    return storage.insertPrice(key, MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210"),
        QuantityInterval.all(),ConditionDomain.any())), MonthlyAdoptionPlan.PriceFact.normal(BigDecimal.TEN,
        MonthlyAdoptionPlan.SourceEvidence.ofJson("{\"sourceKind\":\"FIXED\"}"),Set.of()),null,"{\"requestId\":\"schema-test\"}","test");
  }

  @Test void evidenceEnvelopeCannotPassDatabaseValidationWithMissingRequiredFields() throws Exception {
    migrate(); storage();
    transaction.executeWithoutResult(status -> {
      var saved=price();
      var row=mapper.pricesByIds("COMMERCIAL",Set.of(saved.id())).getFirst();
      var different=new MonthlyPriceFamilyKey(YearMonth.of(2026,11),"COMMERCIAL",saved.family().materialCode(),null,"件","CNY",TaxBasis.EXCLUDED,null);
      row.setPricingMonth(different.month().toString());row.setFamilyHash(MonthlyPriceDocumentCodec.sha256(different.canonical()));
      row.setSourceEvidenceJson("{}");
      checkConstraint(() -> mapper.insertPrice(row),"ck_monthly_price_evidence");
      status.setRollbackOnly();
    });
  }

  @Test void preparationAndCostRowsCannotAttachAnIdentityWithoutItsPricingType() throws Exception {
    migrate(); storage();
    transaction.executeWithoutResult(status -> {
      var saved=price();
      checkConstraint(() -> jdbc.update("INSERT INTO lp_price_prepare_item(prepare_no,oa_no,top_product_code,material_code,item_type,monthly_price_id) VALUES('MPP02','MPP02','TOP',?,'NORMAL',?)",saved.family().materialCode(),saved.id()),"ck_prepare_monthly_pair");
      checkConstraint(() -> jdbc.update("INSERT INTO lp_cost_run_part_item(oa_no,product_code,monthly_price_id) VALUES('MPP02','TOP',?)",saved.id()),"ck_cost_part_monthly_pair");
      status.setRollbackOnly();
    });
  }

  @Test void aVersionedSnapshotCannotOmitItsEncodingVersion() throws Exception {
    migrate(); storage();
    transaction.executeWithoutResult(status -> {
      checkConstraint(() -> jdbc.update("INSERT INTO lp_quote_cost_run_version(cost_run_no,oa_no,product_code,pricing_month,result_period,jianji_snapshot_json) VALUES(?,'MPP02','TOP','2026-10','2026-10','{}')",java.util.UUID.randomUUID().toString()),"ck_cost_jianji_snapshot");
      status.setRollbackOnly();
    });
  }

  static void checkConstraint(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String name) {
    Throwable failure=org.assertj.core.api.Assertions.catchThrowable(action);
    assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining(name);
    assertThat(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure))
        .isInstanceOfSatisfying(java.sql.SQLException.class, sql -> assertThat(sql.getErrorCode()).isEqualTo(3819));
  }

  static HouseholdInquiryBatchRow batch() {
    var b=new HouseholdInquiryBatchRow();
    b.setBusinessUnitType("COMMERCIAL"); b.setPreviewId(java.util.UUID.randomUUID().toString());
    b.setPublishRequestId(java.util.UUID.randomUUID().toString()); b.setPreviewFingerprint("1".repeat(64));
    b.setFileKey("test-content-addressed-object"); b.setFileSha256("2".repeat(64)); b.setFileName("synthetic.xls");
    b.setWorksheetName("10月线圈询价"); b.setParserSchema("HOUSEHOLD_COIL_V1"); b.setSourceMonth("2026-10");
    b.setSourceMonthOrigin("OPERATOR_INPUT"); b.setPricingUnit("件"); b.setCurrency("CNY"); b.setTaxBasis("EXCLUDED");
    b.setPreviewJson("{\"expectedCurrent\":null}"); b.setStatus("PREVIEW");
    b.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(30)); b.setCreatedBy("test");
    return household.insertBatch(b);
  }

  static HouseholdInquiryPriceRow version(long batchId,String material,int revision,Long previous,String price) {
    var v=new HouseholdInquiryPriceRow(); v.setBusinessUnitType("COMMERCIAL"); v.setMaterialCode(material);
    v.setMaterialName("合成线圈"); v.setSpecification("SPEC"); v.setRevisionNo(revision); v.setPreviousPriceId(previous);
    v.setImportBatchId(batchId); v.setSourceRowsJson("[3]"); v.setUnitPrice(new BigDecimal(price));
    v.setHouseholdRate(new BigDecimal("0.085123456789")); v.setPricingFingerprint("3".repeat(64));
    v.setRecordFingerprint("4".repeat(64)); v.setReferenceJson("{\"referenceAmount\":1.234567890123456789}");v.setCurrentFlag(1);
    return v;
  }

  @Test void householdHistoryKeepsOriginalValuesAndOneCurrentVersion() throws Exception {
    migrate(); storage();
    transaction.executeWithoutResult(status -> {
      var source=batch(); String material="000Coil-"+java.util.UUID.randomUUID();
      var first=household.insertVersion(version(source.getId(),material,1,null,"20.123456789012"));
      household.completePublication("COMMERCIAL",source.getId(),"{\"created\":1}");
      var nextBatch=batch();
      assertThatThrownBy(() -> household.insertVersion(version(nextBatch.getId(),material,2,first.getId(),"21")))
          .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
      household.retire("COMMERCIAL",first.getId());
      var second=household.insertVersion(version(nextBatch.getId(),material,2,first.getId(),"21"));
      household.completePublication("COMMERCIAL",nextBatch.getId(),"{\"created\":1}");
      assertThat(household.current("COMMERCIAL",Set.of(material))).extracting(HouseholdInquiryPriceRow::getId).containsExactly(second.getId());
      var history=household.history("COMMERCIAL",material);
      assertThat(history).hasSize(2);
      assertThat(history.getLast().getUnitPrice()).isEqualByComparingTo("20.123456789012");
      assertThat(history.getLast().getHouseholdRate()).isEqualByComparingTo("0.085123456789");
      assertThat(history.getLast().getReferenceJson()).isEqualTo("{\"referenceAmount\":1.234567890123456789}");
      assertThat(household.history("HOUSEHOLD",material)).isEmpty();
      status.setRollbackOnly();
    });
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"batch","previous-material"})
  void householdVersionCannotCrossItsBatchOrPreviousMaterial(String difference) throws Exception {
    migrate(); storage();
    transaction.executeWithoutResult(status -> {
      var source=batch(); String material="Scope-"+java.util.UUID.randomUUID();
      if (difference.equals("batch")) {
        var wrong=version(source.getId(),material,1,null,"20"); wrong.setBusinessUnitType("HOUSEHOLD");
        assertThatThrownBy(() -> household.insertVersion(wrong)).hasStackTraceContaining("HOUSEHOLD_VERSION_SCOPE");
      } else {
        var first=household.insertVersion(version(source.getId(),material,1,null,"20"));
        assertThatThrownBy(() -> household.insertVersion(version(source.getId(),material+"X",2,first.getId(),"21")))
            .hasStackTraceContaining("HOUSEHOLD_VERSION_SCOPE");
      }
      status.setRollbackOnly();
    });
  }

  @Test void monthlyHouseholdFactCannotReferenceAnotherBusinessUnitsSource() throws Exception {
    migrate(); storage();
    transaction.executeWithoutResult(status -> {
      var b=batch(); String material="Scope-"+java.util.UUID.randomUUID();
      var source=household.insertVersion(version(b.getId(),material,1,null,"20"));
      household.completePublication("COMMERCIAL",b.getId(),"{\"created\":1}");
      var wrong=new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"HOUSEHOLD",material,null,"件","CNY",TaxBasis.EXCLUDED,null);
      assertThatThrownBy(() -> storage.insertPrice(wrong,MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any())),
          MonthlyAdoptionPlan.PriceFact.household(source.getUnitPrice(),source.getHouseholdRate(),MonthlyAdoptionPlan.SourceEvidence.ofJson("{\"sourceKind\":\"HOUSEHOLD\"}")),source.getId(),"{\"request\":\"test\"}","test"))
          .hasStackTraceContaining("MPP_HOUSEHOLD_SCOPE");
      status.setRollbackOnly();
    });
  }

  @Test void preparationCostAndVersionMetadataUseTheSameSavedMonthlyIdentity() throws Exception {
    migrate(); storage();
    transaction.executeWithoutResult(status -> {
      var saved=price();
      jdbc.update("INSERT INTO lp_price_prepare_item(prepare_no,oa_no,top_product_code,material_code,item_type,business_unit_type,period_month,price_org_code) VALUES(?,'MPP02','TOP',?,'NORMAL','COMMERCIAL','2026-10','210')",java.util.UUID.randomUUID().toString(),saved.family().materialCode());
      long preparation=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
      String runNo=java.util.UUID.randomUUID().toString();
      jdbc.update("INSERT INTO lp_quote_cost_run_version(cost_run_no,oa_no,oa_form_item_id,product_code,pricing_month,result_period,business_unit_type) VALUES(?,'MPP02',1,'TOP','2026-10','2026-10','COMMERCIAL')",runNo);
      long version=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
      jdbc.update("INSERT INTO lp_cost_run_part_item(oa_no,oa_form_item_id,product_code,part_code,business_unit_type,price_org_code,cost_run_version_id,cost_run_no) VALUES('MPP02',1,'TOP',?,'COMMERCIAL','210',?,?)",saved.family().materialCode(),version,runNo);
      long costPart=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
      storage.attachPreparation("COMMERCIAL",preparation,saved.id(),saved.pricingType());
      storage.attachCostPart("COMMERCIAL",costPart,saved.id(),saved.pricingType());
      assertThat(storage.preparationLink("COMMERCIAL",preparation).monthlyPriceId()).isEqualTo(saved.id());
      assertThat(storage.costPartLink("COMMERCIAL",costPart).monthlyPriceId()).isEqualTo(saved.id());
      String snapshot="{\"fees\":{\"mould\":null,\"depreciation\":0},\"totalCost\":999999999999.123456}";
      storage.saveVersionSnapshot("COMMERCIAL",version,"FEE_PROFILE_OPTIONAL_V2",1,snapshot);
      assertThat(storage.versionSnapshot("COMMERCIAL",version).jianjiSnapshotJson()).isEqualTo(snapshot);
      assertThat(storage.versionSnapshot("COMMERCIAL",1L).jianjiSnapshotJson()).isNull();
      jdbc.update("UPDATE lp_quote_cost_run_version SET status='SUCCESS' WHERE id=?",version);
      status.setRollbackOnly();
    });
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"applicability","first-use"})
  void everyStoredPriceDocumentRequiresItsTransportEnvelope(String field) throws Exception {
    migrate();storage();
    transaction.executeWithoutResult(status -> {
      var saved=price();var row=mapper.pricesByIds("COMMERCIAL",Set.of(saved.id())).getFirst();
      var key=new MonthlyPriceFamilyKey(YearMonth.of(2026,11),"COMMERCIAL",saved.family().materialCode(),null,"件","CNY",TaxBasis.EXCLUDED,null);
      row.setPricingMonth(key.month().toString());row.setFamilyHash(MonthlyPriceDocumentCodec.sha256(key.canonical()));
      if (field.equals("applicability")) row.setApplicabilityJson("{}"); else row.setFirstUseJson("{}");
      checkConstraint(() -> mapper.insertPrice(row),"ck_monthly_price_json");
      status.setRollbackOnly();
    });
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"preparation","cost-part"})
  void aPriceLinkCannotCrossBusinessUnitsEvenThroughRawSql(String target) throws Exception {
    migrate();storage();
    transaction.executeWithoutResult(status -> {
      var saved=price();
      if(target.equals("preparation")) {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO lp_price_prepare_item(prepare_no,oa_no,top_product_code,material_code,item_type,business_unit_type,period_month,price_org_code,monthly_price_id,monthly_pricing_type) VALUES(?,'MPP02','TOP',?,'NORMAL','HOUSEHOLD','2026-10','210',?,'NORMAL')",java.util.UUID.randomUUID().toString(),saved.family().materialCode(),saved.id()))
            .hasStackTraceContaining("MPP_PRICE_LINK_SCOPE");
      } else {
        String runNo=java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO lp_quote_cost_run_version(cost_run_no,oa_no,oa_form_item_id,product_code,pricing_month,result_period,business_unit_type) VALUES(?,'MPP02',1,'TOP','2026-10','2026-10','HOUSEHOLD')",runNo);
        long version=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO lp_cost_run_part_item(oa_no,oa_form_item_id,product_code,part_code,business_unit_type,price_org_code,monthly_price_id,monthly_pricing_type,cost_run_version_id,cost_run_no) VALUES('MPP02',1,'TOP',?,'HOUSEHOLD','210',?,'NORMAL',?,?)",saved.family().materialCode(),saved.id(),version,runNo))
            .hasStackTraceContaining("MPP_PRICE_LINK_SCOPE");
      }
      status.setRollbackOnly();
    });
  }

  @Test void successfulVersionSnapshotCannotBeChangedThroughRawSql() throws Exception {
    migrate();storage();
    transaction.executeWithoutResult(status -> {
      assertThatThrownBy(() -> jdbc.update("UPDATE lp_quote_cost_run_version SET fee_policy_version='NEW' WHERE id=1"))
          .hasStackTraceContaining("MPP_COST_SNAPSHOT_IMMUTABLE");
      status.setRollbackOnly();
    });
  }

  @Test void previewContentsCannotBeRewrittenAndPublishedObservationsCannotBeDeleted() throws Exception {
    migrate();storage();
    transaction.executeWithoutResult(status -> {
      var source=batch();
      assertThatThrownBy(() -> jdbc.update("UPDATE lp_household_inquiry_import_batch SET file_key='changed' WHERE id=?",source.getId()))
          .hasStackTraceContaining("HOUSEHOLD_PREVIEW_IMMUTABLE");
      household.completePublication("COMMERCIAL",source.getId(),"{\"unchanged\":true}");
      assertThatThrownBy(() -> jdbc.update("DELETE FROM lp_household_inquiry_import_batch WHERE id=?",source.getId()))
          .hasStackTraceContaining("HOUSEHOLD_PUBLICATION_IMMUTABLE");
      status.setRollbackOnly();
    });
  }

  @Test void snapshotEncodingVersionDoesNotMakeAnUnencodedObjectReadable() throws Exception {
    migrate();storage();
    transaction.executeWithoutResult(status -> {
      checkConstraint(() -> jdbc.update("INSERT INTO lp_quote_cost_run_version(cost_run_no,oa_no,product_code,pricing_month,result_period,jianji_snapshot_version,jianji_snapshot_json) VALUES(?,'MPP02','TOP','2026-10','2026-10',1,'{}')",java.util.UUID.randomUUID().toString()),"ck_cost_jianji_snapshot");
      status.setRollbackOnly();
    });
  }

  @Test void missingOptionalTermsDoNotDuplicateIdentityAndDeclaredConditionsRemainIsolated() throws Exception {
    migrate(); storage();
    transaction.executeWithoutResult(status -> {
      var key=new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL","001Ab-"+java.util.UUID.randomUUID(),null,"件","CNY",TaxBasis.EXCLUDED,null);
      var equivalent=new MonthlyPriceFamilyKey(key.month()," commercial ",key.materialCode()," "," 件 ","cny",TaxBasis.EXCLUDED,"");
      storage.lockFamilies(List.of(key,equivalent));
      var saved=storage.insertPrice(key,MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210","220"),QuantityInterval.all(),ConditionDomain.any())),
          MonthlyAdoptionPlan.PriceFact.normal(BigDecimal.TEN,MonthlyAdoptionPlan.SourceEvidence.ofJson("{\"sourceKind\":\"SCRAP\",\"financeOrg\":\"210\"}"),Set.of()),null,"{\"request\":\"identity\"}","test");
      assertThat(storage.readFamilies(List.of(key,equivalent))).extracting(MonthlyPriceStorage.StoredPrice::id).containsExactly(saved.id());
      for (String org : List.of("210","220")) for (int quantity : List.of(1,2)) {
        assertThat(saved.applicability().matches(new MonthlyPriceRequest(key,org,NonCuConditions.empty(),BigDecimal.valueOf(quantity)))).isTrue();
      }
      for (var different : List.of(
          new MonthlyPriceFamilyKey(key.month(),"COMMERCIAL",key.materialCode().toLowerCase(java.util.Locale.ROOT),null,"件","CNY",TaxBasis.EXCLUDED,null),
          new MonthlyPriceFamilyKey(key.month(),"COMMERCIAL",key.materialCode(),"OTHER","件","CNY",TaxBasis.EXCLUDED,null),
          new MonthlyPriceFamilyKey(key.month(),"COMMERCIAL",key.materialCode(),null,"kg","CNY",TaxBasis.EXCLUDED,null),
          new MonthlyPriceFamilyKey(key.month(),"COMMERCIAL",key.materialCode(),null,"件","USD",TaxBasis.EXCLUDED,null),
          new MonthlyPriceFamilyKey(key.month(),"COMMERCIAL",key.materialCode(),null,"件","CNY",TaxBasis.INCLUDED,null),
          new MonthlyPriceFamilyKey(key.month(),"COMMERCIAL",key.materialCode(),null,"件","CNY",TaxBasis.EXCLUDED,"S1"))) {
        assertThat(storage.readFamilies(List.of(different))).isEmpty();
      }
      status.setRollbackOnly();
    });
  }

  @Test void theDocumentedCanonicalNumberBoundarySurvivesDatabaseRoundTrip() throws Exception {
    migrate();storage();
    transaction.executeWithoutResult(status -> {
      var key=new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL","Big-"+java.util.UUID.randomUUID(),null,"件","CNY",TaxBasis.EXCLUDED,null);
      var evidence=MonthlyAdoptionPlan.SourceEvidence.ofJson("{\"factor\":1e4095,\"sourceKind\":\"FIXED\"}");
      var saved=storage.insertPrice(key,MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210"),
          new QuantityInterval(new BigDecimal("1e4095"),true,null,false),ConditionDomain.any())),
          MonthlyAdoptionPlan.PriceFact.normal(BigDecimal.ONE,evidence,Set.of()),null,"{\"request\":\"precision\"}","test");
      var restored=storage.readByIds("COMMERCIAL",Set.of(saved.id())).getFirst();
      assertThat(restored.evidence().json()).isEqualTo("{\"factor\":1"+"0".repeat(4095)+",\"sourceKind\":\"FIXED\"}");
      assertThat(restored.evidence().sha256()).isEqualTo(evidence.sha256());
      assertThat(restored.applicability()).isEqualTo(saved.applicability());
      status.setRollbackOnly();
    });
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"missing","month","history"})
  void newCostLinksRequireTheSameUnfinishedVersionAndMonth(String difference) throws Exception {
    migrate();storage();
    transaction.executeWithoutResult(status -> {
      var saved=price();String runNo=java.util.UUID.randomUUID().toString();Long version=null;
      if(!difference.equals("missing")) {
        String month=difference.equals("month")?"2026-11":"2026-10";
        jdbc.update("INSERT INTO lp_quote_cost_run_version(cost_run_no,oa_no,oa_form_item_id,product_code,pricing_month,result_period,business_unit_type,status) VALUES(?,'MPP02',1,'TOP',?,?,'COMMERCIAL',?)",runNo,month,month,difference.equals("history")?"SUCCESS":"TRIAL");
        version=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
      }
      jdbc.update("INSERT INTO lp_cost_run_part_item(oa_no,oa_form_item_id,product_code,part_code,business_unit_type,price_org_code,cost_run_version_id,cost_run_no) VALUES('MPP02',1,'TOP',?,'COMMERCIAL','210',?,?)",saved.family().materialCode(),version,runNo);
      long part=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
      assertThatThrownBy(() -> storage.attachCostPart("COMMERCIAL",part,saved.id(),saved.pricingType()))
          .hasStackTraceContaining("MPP_PRICE_LINK_SCOPE");
      status.setRollbackOnly();
    });
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"preparation"})
  void aDedicatedOrganizationPriceCannotBeUsedByTheOtherOrganization(String target) throws Exception {
    migrate();storage();
    transaction.executeWithoutResult(status -> {
      var saved=price();
      if(target.equals("preparation")) {
        jdbc.update("INSERT INTO lp_price_prepare_item(prepare_no,oa_no,top_product_code,material_code,item_type,business_unit_type,period_month,price_org_code) VALUES(?,'MPP02','TOP',?,'NORMAL','COMMERCIAL','2026-10','220')",java.util.UUID.randomUUID().toString(),saved.family().materialCode());
        long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        assertThatThrownBy(() -> storage.attachPreparation("COMMERCIAL",id,saved.id(),saved.pricingType()))
            .hasStackTraceContaining("MPP_PRICE_LINK_SCOPE");
      }
      status.setRollbackOnly();
    });
  }

  @Test void aCompletedVersionCannotBecomeEditableAgainToRewriteItsSnapshot() throws Exception {
    migrate();storage();
    transaction.executeWithoutResult(status -> {
      jdbc.update("INSERT INTO lp_quote_cost_run_version(cost_run_no,oa_no,product_code,pricing_month,result_period,business_unit_type) VALUES(?,'MPP02','TOP','2026-10','2026-10','COMMERCIAL')",java.util.UUID.randomUUID().toString());
      long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
      storage.saveVersionSnapshot("COMMERCIAL",id,"FEE_PROFILE_OPTIONAL_V2",1,"{\"totalCost\":10}");
      jdbc.update("UPDATE lp_quote_cost_run_version SET status='SUCCESS' WHERE id=?",id);
      assertThatThrownBy(() -> jdbc.update("UPDATE lp_quote_cost_run_version SET status='TRIAL' WHERE id=?",id))
          .hasStackTraceContaining("MPP_COST_SNAPSHOT_IMMUTABLE");
      status.setRollbackOnly();
    });
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"SCRAP","SETTLE"})
  void aSharedNormalFactKeepsOneIdentityForBothOrganizations(String sourceKind) throws Exception {
    migrate();storage();
    transaction.executeWithoutResult(status -> {
      var key=new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL","Shared-"+java.util.UUID.randomUUID(),null,"件","CNY",TaxBasis.EXCLUDED,null);
      var saved=storage.insertPrice(key,MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210","220"),QuantityInterval.all(),ConditionDomain.any())),
          MonthlyAdoptionPlan.PriceFact.normal(BigDecimal.TEN,MonthlyAdoptionPlan.SourceEvidence.ofJson("{\"sourceKind\":\""+sourceKind+"\",\"financeOrg\":\"210\"}"),Set.of()),null,"{\"request\":\"shared\"}","test");
      for(String org:List.of("210","220")) {
        jdbc.update("INSERT INTO lp_price_prepare_item(prepare_no,oa_no,top_product_code,material_code,item_type,business_unit_type,period_month,price_org_code) VALUES(?,'MPP02','TOP',?,'NORMAL','COMMERCIAL','2026-10',?)",java.util.UUID.randomUUID().toString(),key.materialCode(),org);
        long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);storage.attachPreparation("COMMERCIAL",id,saved.id(),saved.pricingType());
      }
      assertThat(storage.readFamilies(List.of(key))).extracting(MonthlyPriceStorage.StoredPrice::id).containsExactly(saved.id());
      status.setRollbackOnly();
    });
  }

  @Test void readOnlyAndUnboundOperationsCannotCreateFamilyGuardsOrPrices() throws Exception {
    migrate();storage();
    var key=new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL","Read-"+java.util.UUID.randomUUID(),null,"件","CNY",TaxBasis.EXCLUDED,null);
    assertThat(storage.readFamilies(List.of(key))).isEmpty();
    assertThat(household.current("COMMERCIAL",Set.of(key.materialCode()))).isEmpty();
    assertThatThrownBy(() -> storage.lockFamilies(List.of(key))).hasMessageContaining("writable transaction");
    var readOnly=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    readOnly.setReadOnly(true);readOnly.setIsolationLevelName("ISOLATION_READ_COMMITTED");
    readOnly.executeWithoutResult(status -> assertThatThrownBy(() -> storage.lockFamilies(List.of(key))).hasMessageContaining("writable transaction"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE family_hash=?",Integer.class,MonthlyPriceDocumentCodec.sha256(key.canonical()))).isZero();
  }

  @Test void householdSourceWritesRollbackWithTheirPreviewAndPublishedBatch() throws Exception {
    migrate();storage();var preview=new java.util.concurrent.atomic.AtomicReference<String>();var material="Rollback-"+java.util.UUID.randomUUID();
    assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
      var source=batch();preview.set(source.getPreviewId());household.insertVersion(version(source.getId(),material,1,null,"20"));
      household.completePublication("COMMERCIAL",source.getId(),"{\"created\":1}");
      throw new IllegalStateException("rollback-source-publication");
    })).hasMessage("rollback-source-publication");
    assertThat(household.batch("COMMERCIAL",preview.get())).isEmpty();
    assertThat(household.history("COMMERCIAL",material)).isEmpty();
  }

  @Test void existingMonthlyFactsCannotBeUpdatedOrDeleted() throws Exception {
    migrate();storage();
    transaction.executeWithoutResult(status -> {
      var saved=price();
      assertThatThrownBy(() -> jdbc.update("UPDATE lp_material_monthly_price SET unit_price=11 WHERE id=?",saved.id())).hasStackTraceContaining("MPP_MONTHLY_PRICE_IMMUTABLE");
      assertThatThrownBy(() -> jdbc.update("DELETE FROM lp_material_monthly_price WHERE id=?",saved.id())).hasStackTraceContaining("MPP_MONTHLY_PRICE_IMMUTABLE");
      status.setRollbackOnly();
    });
  }

  @Test void anUnpublishedBatchCannotExposeAnEffectiveSourceVersion() throws Exception {
    migrate();storage();
    transaction.executeWithoutResult(status -> {
      var source=batch();var material="Pending-"+java.util.UUID.randomUUID();
      household.insertVersion(version(source.getId(),material,1,null,"20"));
      assertThat(household.current("COMMERCIAL",Set.of(material))).isEmpty();
      assertThat(household.history("COMMERCIAL",material)).isEmpty();
      household.completePublication("COMMERCIAL",source.getId(),"{\"created\":1}");
      assertThat(household.current("COMMERCIAL",Set.of(material))).hasSize(1);
      assertThat(household.history("COMMERCIAL",material)).hasSize(1);
      status.setRollbackOnly();
    });
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"0","20.123456789012","999999999999.999999999999"})
  void adoptedHouseholdFactsTraceTheirPublishedVersionAndKeepTheirOriginalPrice(String amount) throws Exception {
    migrate();storage();
    transaction.executeWithoutResult(status -> {
      var batch=batch();var material="House-"+java.util.UUID.randomUUID();
      var input=version(batch.getId(),material,1,null,amount);
      input.setHouseholdRate(amount.equals("0")?BigDecimal.ZERO:new BigDecimal("999999.999999999999"));
      var source=household.insertVersion(input);
      var key=new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL",material,null,"件","CNY",TaxBasis.EXCLUDED,null);
      var applicability=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210","220"),QuantityInterval.all(),ConditionDomain.any()));
      var fact=MonthlyAdoptionPlan.PriceFact.household(source.getUnitPrice(),source.getHouseholdRate(),
          MonthlyAdoptionPlan.SourceEvidence.ofJson("{\"sourceKind\":\"HOUSEHOLD\",\"sourceVersionId\":"+source.getId()+"}"));
      assertThatThrownBy(() -> storage.insertPrice(key,applicability,fact,source.getId(),"{\"request\":\"household\"}","test"))
          .hasStackTraceContaining("MPP_HOUSEHOLD_SCOPE");
      household.completePublication("COMMERCIAL",batch.getId(),"{\"created\":1}");
      var saved=storage.insertPrice(key,applicability,fact,source.getId(),"{\"request\":\"household\"}","test");
      household.retire("COMMERCIAL",source.getId());var next=batch();
      household.insertVersion(version(next.getId(),material,2,source.getId(),"21"));
      household.completePublication("COMMERCIAL",next.getId(),"{\"created\":1}");
      var restored=storage.readByIds("COMMERCIAL",Set.of(saved.id())).getFirst();
      assertThat(restored.unitPrice()).isEqualByComparingTo(amount);
      assertThat(restored.householdRate()).isEqualByComparingTo(input.getHouseholdRate());
      assertThat(restored.householdSourceId()).isEqualTo(source.getId());
      assertThat(household.history("COMMERCIAL",material).getLast().getUnitPrice()).isEqualByComparingTo(amount);
      assertThat(household.current("COMMERCIAL",Set.of(material)).getFirst().getUnitPrice()).isEqualByComparingTo("21");
      status.setRollbackOnly();
    });
  }

  @Test void quantityBandsAndSharedRemainingFragmentsKeepEarlierAdoptedFacts() throws Exception {
    migrate();storage();
    transaction.executeWithoutResult(status -> {
      var key=new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL","Band-"+java.util.UUID.randomUUID(),null,"件","CNY",TaxBasis.EXCLUDED,null);
      var original=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210"),new QuantityInterval(BigDecimal.ZERO,true,new BigDecimal("100"),true),ConditionDomain.any()));
      var next=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210"),new QuantityInterval(new BigDecimal("100"),false,new BigDecimal("200"),true),ConditionDomain.any()));
      var evidence=MonthlyAdoptionPlan.SourceEvidence.ofJson("{\"sourceKind\":\"SCRAP\"}");
      var first=storage.insertPrice(key,original,MonthlyAdoptionPlan.PriceFact.normal(BigDecimal.TEN,evidence,Set.of()),null,"{\"request\":1}","test");
      var overlapping=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210"),new QuantityInterval(new BigDecimal("50"),true,new BigDecimal("200"),true),ConditionDomain.any()));
      assertThatThrownBy(() -> storage.insertPrice(key,overlapping,MonthlyAdoptionPlan.PriceFact.normal(new BigDecimal("12"),evidence,Set.of()),null,"{\"request\":2}","test"))
          .hasMessageContaining("already adopted");
      storage.insertPrice(key,next,MonthlyAdoptionPlan.PriceFact.normal(new BigDecimal("12"),evidence,Set.of()),null,"{\"request\":3}","test");
      var shared=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210","220"),QuantityInterval.all(),ConditionDomain.any()));
      var remaining=shared.subtract(original).orElseThrow().subtract(next).orElseThrow();
      var sharedSaved=storage.insertPrice(key,remaining,MonthlyAdoptionPlan.PriceFact.normal(new BigDecimal("14"),evidence,Set.of()),null,"{\"request\":4}","test");
      var prices=storage.readFamilies(List.of(key));assertThat(prices).hasSize(3);
      var request210=new MonthlyPriceRequest(key,"210",NonCuConditions.empty(),new BigDecimal("80"));
      var request220=new MonthlyPriceRequest(key,"220",NonCuConditions.empty(),new BigDecimal("80"));
      assertThat(prices.stream().filter(row -> row.applicability().matches(request210))).extracting(MonthlyPriceStorage.StoredPrice::id).containsExactly(first.id());
      assertThat(prices.stream().filter(row -> row.applicability().matches(request220))).extracting(MonthlyPriceStorage.StoredPrice::id).containsExactly(sharedSaved.id());
      status.setRollbackOnly();
    });
  }

  @Test void fractionalDocumentSchemaVersionsCannotBeTruncatedToAnAcceptedVersion() {
    var codec=new MonthlyPriceDocumentCodec(new ObjectMapper());
    assertThatThrownBy(() -> codec.unwrap("{\"schemaVersion\":1.9,\"document\":\"{}\"}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(codec.unwrap(codec.wrap("{\"precise\":1.9}"))).isEqualTo("{\"precise\":1.9}");
  }

  @Test void storageQueriesHavePhysicalIndexesAndNoCascadingHistoryDeletes() throws Exception {
    migrate();
    assertIndex("lp_material_monthly_price","uk_monthly_price_identity",List.of("family_hash","applicability_hash"));
    assertIndex("lp_material_monthly_price","idx_monthly_price_query",List.of("business_unit_type","pricing_month","material_code","id"));
    assertIndex("lp_material_monthly_price","idx_monthly_price_source",List.of("household_source_id","id"));
    assertIndex("lp_household_inquiry_price","uk_household_current",List.of("business_unit_type","current_material"));
    assertIndex("lp_household_inquiry_price","uk_household_revision",List.of("business_unit_type","material_code","revision_no"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND (delete_rule NOT IN ('RESTRICT','NO ACTION') OR update_rule NOT IN ('RESTRICT','NO ACTION'))",Integer.class)).isZero();
  }

  static void assertIndex(String table,String index,List<String> columns) {
    assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=? ORDER BY SEQ_IN_INDEX",String.class,table,index)).containsExactlyElementsOf(columns);
  }
  @Test void readCommittedCannotBeUsedForMonthlyRangeLocks() throws Exception {
    migrate();storage();var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    tx.setIsolationLevelName("ISOLATION_READ_COMMITTED");
    tx.executeWithoutResult(status->assertThatThrownBy(()->storage.lockFamilies(List.of(
        new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL","RC",null,"件","CNY",TaxBasis.EXCLUDED,null))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("REPEATABLE_READ"));
  }
  @Test void singleTableMigrationRefusesAnyExistingMonthlyDataBeforeChangingTables() throws Exception {
    jdbc.execute("CREATE DATABASE mpp_migration_guard CHARACTER SET utf8mb4");
    var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl().replace("/mpp_storage_test","/mpp_migration_guard"),MYSQL.getUsername(),MYSQL.getPassword());
    var guard=new JdbcTemplate(ds);guard.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/pre-v342-schema.sql")).execute(ds);
    MYSQL.copyFileToContainer(MountableFile.forClasspathResource(MIGRATION),"/tmp/guard-old.sql");
    var old=MYSQL.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 mpp_migration_guard < /tmp/guard-old.sql");
    assertThat(old.getExitCode()).withFailMessage(old.getStderr()).isZero();
    guard.update("INSERT INTO lp_material_monthly_family(family_hash,family_canonical,family_json,pricing_month,business_unit_type,material_code,pricing_unit,currency,tax_basis) VALUES(SHA2('guard',256),'guard','{}','2026-10','COMMERCIAL','KEEP','件','CNY','EXCLUDED')");
    var before=guard.queryForMap("SHOW CREATE TABLE lp_material_monthly_price");
    MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/V344__monthly_material_price_single_table.sql"),"/tmp/guard-new.sql");
    var blocked=MYSQL.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 mpp_migration_guard < /tmp/guard-new.sql");
    assertThat(blocked.getExitCode()).isNotZero();assertThat(blocked.getStderr()).contains("MPP_SINGLE_TABLE_REQUIRES_UNUSED_MONTHLY_STORAGE");
    assertThat(guard.queryForMap("SHOW CREATE TABLE lp_material_monthly_price")).isEqualTo(before);
    assertThat(guard.queryForObject("SELECT material_code FROM lp_material_monthly_family",String.class)).isEqualTo("KEEP");
    assertThat(guard.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'lp_material_monthly_%'",Integer.class)).isEqualTo(4);
  }
}
