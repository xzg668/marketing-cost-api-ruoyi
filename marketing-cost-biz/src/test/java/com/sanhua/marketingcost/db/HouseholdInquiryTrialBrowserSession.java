package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import com.sanhua.marketingcost.service.pricing.household.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Original workbook, real financial reader/formula/API and browser against a disposable MySQL only. */
@Tag("integration")
@EnabledIfSystemProperty(named="household.trial.browser.state",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","app.cors.allowed-origins=http://127.0.0.1:15238",
    "integration.oa-person-directory.enabled=false","integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false","srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false"})
class HouseholdInquiryTrialBrowserSession extends OriginalPricingIntegrationTestBase {
  static final Path ARCHIVE=temporaryArchive();
  static Path temporaryArchive() {
    try {return Files.createTempDirectory("household-trial-browser-");}
    catch(Exception failure) {throw new ExceptionInInitializerError(failure);}
  }
  @DynamicPropertySource static void archive(DynamicPropertyRegistry properties) {
    properties.add("price.household.workbooks-root",ARCHIVE::toString);
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired javax.sql.DataSource dataSource;
  @Autowired HouseholdInquiryService inquiries;
  @LocalServerPort int port;

  @Test void originalWorkbookTrialAndAllSavedPricesRemainUnchanged() throws Exception {
    new ResourceDatabasePopulator(new ClassPathResource("db/V343__household_inquiry_menu.sql")).execute(dataSource);
    finance("2026-11","90","20.8310629161179");
    finance("2026-12","100","24");
    byte[] bytes=getClass().getResourceAsStream("/household-inquiry/real-20261107.xlsx").readAllBytes();
    var preview=inquiries.preview("COMMERCIAL","admin",bytes,"家用询价单2026.11.7.xlsx","2026-11");
    assertThat(preview.content().workbook().errors()).isEmpty();
    var published=inquiries.publish("COMMERCIAL","admin",new HouseholdInquiryModels.PublishRequest(
        preview.previewId(),preview.publishRequestId(),preview.previewFingerprint()));
    assertThat(published.newVersions()).isEqualTo(125);
    var tables=List.of("lp_household_inquiry_price","lp_household_inquiry_import_batch",
        "lp_factor_monthly_price","lp_factor_upload_batch","lp_material_monthly_price","lp_quote_cost_run_version");
    var before=new LinkedHashMap<String,List<Map<String,Object>>>();
    for(String table:tables) before.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id"));
    var json=new ObjectMapper().findAndRegisterModules();
    var state=Path.of(System.getProperty("household.trial.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    Files.writeString(state,json.writeValueAsString(Map.of("apiPort",port,"month","2026-11")));
    var release=Path.of(state+".release");long deadline=System.nanoTime()+Duration.ofMinutes(6).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline)Thread.sleep(200);
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    for(String table:tables) assertThat(jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id")).as(table).isEqualTo(before.get(table));
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of("passed",true,
        "protectedTables",tables,"originalSources",125,"monthlyAdoptionOrCosting",false)));
  }

  void finance(String month,String copper,String zinc) {
    String no=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO lp_factor_upload_batch(batch_no,import_type,price_month,business_unit_type,file_name,file_sha256,content_hash,uploaded_by,status,price_org_code,publication_revision,default_scheme_code,published_at) VALUES(?,'FINANCE_BASE',?,'COMMERCIAL','household-base.xlsx',?,?,'fixture','PUBLISHED','210',1,'DEFAULT',NOW())",no,month,"a".repeat(64),"b".repeat(64));
    long batch=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE batch_no=?",Long.class,no);
    for(var factor:Map.of(PriceFactorContext.COPPER,copper,PriceFactorContext.ZINC,zinc).entrySet()) {
      String seq=UUID.randomUUID().toString(),key=factor.getKey();
      jdbc.update("INSERT INTO lp_factor_identity(business_unit_type,factor_seq_no,factor_name,short_name,price_source,canonical_factor_key) VALUES('COMMERCIAL',?,?,?,'AVG',?)",seq,key,key,key);
      long identity=jdbc.queryForObject("SELECT id FROM lp_factor_identity WHERE factor_seq_no=?",Long.class,seq);
      jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,tax_included,source_upload_batch_id,status,price_org_code,scheme_code,condition_code,revision_no,unit,published_at) VALUES(?,?,?,1,?,'PUBLISHED','210','DEFAULT','GENERAL',1,'CNY/KG',NOW())",identity,month,new BigDecimal(factor.getValue()),batch);
      long price=jdbc.queryForObject("SELECT id FROM lp_factor_monthly_price WHERE factor_identity_id=?",Long.class,identity);
      jdbc.update("INSERT INTO lp_factor_row_ref(factor_upload_batch_id,source_sheet_name,source_row_number,factor_identity_id,factor_monthly_price_id,source_column_number,source_cell_ref,scheme_code,condition_code,source_value_text,source_unit,source_number_format,conversion_basis) VALUES(?,'商用基价',?,?,?,4,?,'DEFAULT','GENERAL',?,'CNY/KG','0.000000','IDENTITY')",batch,key.equals(PriceFactorContext.COPPER)?3:4,identity,price,key.equals(PriceFactorContext.COPPER)?"D3":"D4",factor.getValue());
    }
  }
}
