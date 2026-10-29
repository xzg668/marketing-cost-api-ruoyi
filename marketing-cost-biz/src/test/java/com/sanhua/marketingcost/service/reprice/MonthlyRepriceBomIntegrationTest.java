package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.sanhua.marketingcost.service.reprice.MonthlyRepriceContractIntegrationTest.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.ingest.*;
import com.sanhua.marketingcost.service.quotebom.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Real contract/snapshot/supplement SQL and transactions; controlled U9 acquisition rows.
 * No external OA/drawing service or ordinary costing acceptance is claimed here. */
@Tag("integration")
class MonthlyRepriceBomIntegrationTest {
  static AnnotationConfigApplicationContext spring;
  static MonthlyRepriceBomService repricing;
  static MonthlyBomSnapshotDetailService details;
  static final OaFormMapper FORMS=mock(OaFormMapper.class);
  static final OaFormItemMapper ITEMS=mock(OaFormItemMapper.class);
  static final QuoteBomStatusMapper STATUS=mock(QuoteBomStatusMapper.class);
  static final LiveU9BomGateway LIVE_U9=mock(LiveU9BomGateway.class);
  static volatile List<BomRawHierarchy> raw;
  static OaForm form;
  static OaFormItem item;

  @Configuration @EnableTransactionManagement @EnableAspectJAutoProxy
  static class Transactions {}

  @BeforeAll static void startDatabase() throws Exception {
    MonthlyRepriceContractIntegrationTest.database();
    var ds=jdbc.getDataSource();
    var manager=new DataSourceTransactionManager(ds);
    jdbc.execute("CREATE TABLE lp_drawing_verification_result(id BIGINT PRIMARY KEY)");
    new ResourceDatabasePopulator(new ClassPathResource("monthly-reprice/mrp05-bom-schema.sql")).execute(ds);
    var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
    config.addMapper(QuoteBomMonthlySnapshotMapper.class);config.addMapper(QuoteBomSupplementVersionMapper.class);
    var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
    factory.setDataSource(ds);factory.setConfiguration(config);
    var sql=new SqlSessionTemplate(factory.getObject());
    var snapshots=sql.getMapper(QuoteBomMonthlySnapshotMapper.class);
    var supplements=sql.getMapper(QuoteBomSupplementVersionMapper.class);
    var json=new ObjectMapper().findAndRegisterModules();
    details=spy(new MonthlyBomSnapshotDetailService(jdbc,json,null,null));
    doAnswer(call->{details.save(call.getArgument(0),raw);return null;})
        .when(details).captureU9(anyLong(),any(),anyString());
    var contexts=new QuoteBomContextResolver(mock(MaterialMasterRawMapper.class));
    var packaging=mock(U9ProductPackagingTypeResolver.class);
    spring=new AnnotationConfigApplicationContext();
    spring.register(Transactions.class,com.sanhua.marketingcost.service.concurrent.CostingConcurrencyRetryAspect.class);
    spring.registerBean("transactionManager",DataSourceTransactionManager.class,()->manager);
    spring.registerBean(QuoteBomMonthlySnapshotMapper.class,()->snapshots);
    spring.registerBean(LiveU9BomGateway.class,()->LIVE_U9);
    spring.registerBean(MonthlyBomSnapshotDetailService.class,()->details);
    spring.registerBean(MonthlySnapshotU9BomGateway.class);
    spring.registerBean(QuoteBomStatusServiceImpl.class,()->new QuoteBomStatusServiceImpl(
        FORMS,ITEMS,STATUS,snapshots,spring.getBean(CurrentU9BomGateway.class),packaging,
        contexts,new SupplementBomAvailabilityResolver(supplements)));
    spring.refresh();
    repricing=new MonthlyRepriceBomService(mapper,FORMS,ITEMS,contexts,
        spring.getBean(QuoteBomStatusService.class),json,manager);
  }
  @AfterAll static void closeDatabase() { spring.close();MonthlyRepriceContractIntegrationTest.stop(); }
  @BeforeEach void prepareFixture() {
    new MonthlyRepriceContractIntegrationTest().clean();
    for(String table:List.of("lp_quote_bom_monthly_snapshot_detail","lp_quote_bom_monthly_snapshot","lp_quote_bom_supplement_version"))
      jdbc.update("DELETE FROM "+table);
    reset(FORMS,ITEMS,STATUS,LIVE_U9);
    form=new OaForm();form.setId(1L);form.setOaNo("MRP1130-TEST");form.setBusinessUnitType("COMMERCIAL");form.setCustomer("SAVED-CUSTOMER");
    item=new OaFormItem();item.setId(301L);item.setOaFormId(1L);item.setMaterialNo("TOP");
    item.setBusinessUnitType("COMMERCIAL");item.setProductName("成品");item.setPackageMethod("BOX");
    when(FORMS.selectById(1L)).thenReturn(form);when(ITEMS.selectById(301L)).thenReturn(item);
    raw=tree("V1");
    when(LIVE_U9.readLive(any())).thenAnswer(call->CurrentU9BomResult.available("U9",
        raw.getFirst().getMaterialName(),"RAW-"+raw.getFirst().getMaterialName(),raw.size(),
        (raw.size()==5?"1":"2").repeat(64)));
  }
  @AfterEach void logout() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

  static MonthlyRepriceBatch launch() {
    var source=source("COMMERCIAL",301L);
    jdbc.update("INSERT INTO lp_cost_run_part_item(oa_no,product_code,cost_run_version_id,part_code,qty,amount,price_org_code,material_organization_code) VALUES('MRP1130-TEST','TOP',?,'BASE',1,12.123456,'210','COMMERCIAL')",source.sourceCostVersionId());
    return service.enqueue("COMMERCIAL",MONTH);
  }

  @Test void targetMonthKeepsCompleteRepeatedChildPathsThenNextMonthAcquiresNewStructure() {
    var batch=launch();var task=task(batch);
    var first=repricing.prepare(batch.getRepriceNo(),task.getId());assertThat(first.ready()).isTrue();
    long id=first.bom().getSyncRecordId();
    assertThat(details.load(id)).extracting(BomRawHierarchy::getPath)
        .containsExactly("/TOP/","/TOP/A/","/TOP/A/C/","/TOP/B/","/TOP/B/C/");
    var oldRows=jdbc.queryForList("SELECT * FROM lp_quote_cost_run_version");
    raw=tree("V2");item.setPackageMethod("CHANGED");form.setCustomer("CHANGED");
    var shared=spring.getBean(CurrentU9BomGateway.class).read(new QuoteBomReadContext(
        1L,301L,"MRP1130-TEST","2026-01","COMMERCIAL","TOP","成品",null,null,
        "210","COMMERCIAL",LocalDate.of(2026,1,1),LocalDateTime.of(2026,1,1,0,0)));
    assertThat(shared.monthlySnapshotId()).isEqualTo(id);
    var again=repricing.prepare(batch.getRepriceNo(),task.getId());
    assertThat(again.bom().getSyncRecordId()).isEqualTo(id);
    assertThat(details.load(id)).extracting(BomRawHierarchy::getMaterialName).containsOnly("V1");
    assertThat(jdbc.queryForList("SELECT * FROM lp_quote_cost_run_version")).isEqualTo(oldRows);
    verifyNoInteractions(STATUS);
    var ready=ready(batch);service.confirm(batch.getRepriceNo(),ready.getCheckedRevision());
    var next=service.enqueue("COMMERCIAL",MONTH.plusMonths(1));
    var newer=repricing.prepare(next.getRepriceNo(),task(next).getId());
    assertThat(newer.ready()).isTrue();assertThat(newer.bom().getSyncRecordId()).isNotEqualTo(id);
    assertThat(newer.bom().getCostPeriodMonth()).isEqualTo("2026-02");
    assertThat(details.load(newer.bom().getSyncRecordId())).extracting(BomRawHierarchy::getMaterialName).containsOnly("V2");
    assertThat(details.load(newer.bom().getSyncRecordId())).extracting(BomRawHierarchy::getPath)
        .contains("/TOP/B/D/");
    assertThat(details.load(newer.bom().getSyncRecordId())).extracting(BomRawHierarchy::getQtyPerParent)
        .containsOnly(new BigDecimal("2"));
    assertThat(details.load(id)).extracting(BomRawHierarchy::getMaterialName).containsOnly("V1");
    verify(LIVE_U9,times(2)).readLive(any());
  }

  @Test void missingU9WaitsForApprovedTargetMonthSupplementWithoutUsingLastMonthOrDraft() {
    var batch=launch();var task=task(batch);when(LIVE_U9.readLive(any())).thenReturn(CurrentU9BomResult.notFound("目标月U9无BOM，请通过图库补录并确认"));
    supplement("2025-12","APPROVED");supplement("2026-01","DRAFT");
    var before=jdbc.queryForList("SELECT * FROM lp_quote_cost_run_version");
    var pending=repricing.prepare(batch.getRepriceNo(),task.getId());
    assertThat(pending.ready()).isFalse();assertThat(pending.problem()).contains("图库");
    var input=jdbc.queryForObject("SELECT JSON_EXTRACT(request_snapshot_json,'$.bomInput') FROM lp_cost_run_task WHERE id=?",String.class,task.getId());
    supplement("2026-01","APPROVED");
    form.setCustomer("CHANGED");item.setPackageMethod("CHANGED");
    var available=repricing.prepare(batch.getRepriceNo(),task.getId());
    assertThat(available.ready()).isTrue();assertThat(available.bom().getBomSource()).isEqualTo("ELECTRONIC_DRAWING_BOM");
    assertThat(available.bom().getCustomerCode()).isEqualTo("SAVED-CUSTOMER");
    assertThat(available.bom().getPackageMethod()).isEqualTo("BOX");
    assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(request_snapshot_json,'$.bomInput') FROM lp_cost_run_task WHERE id=?",String.class,task.getId())).isEqualTo(input);
    assertThat(jdbc.queryForList("SELECT * FROM lp_quote_cost_run_version")).isEqualTo(before);
    verifyNoInteractions(STATUS);
  }

  @Test void differentBusinessUnitApprovedSnapshotCannotSatisfyMissingBom() {
    var batch=launch();when(LIVE_U9.readLive(any())).thenReturn(CurrentU9BomResult.notFound("missing"));
    jdbc.update("INSERT INTO lp_quote_bom_monthly_snapshot(product_code,price_org_code,business_unit_type,customer_code,package_method,cost_period_month,sync_type,sync_status,active_flag,bom_source,bom_batch_id) VALUES('TOP','210','HOUSEHOLD','SAVED-CUSTOMER','BOX','2026-01','AUTO','SUCCESS',1,'ELECTRONIC_DRAWING_BOM','OTHER-UNIT')");
    assertThat(repricing.prepare(batch.getRepriceNo(),task(batch).getId()).ready()).isFalse();
  }

  @Test void concurrentFirstAcquisitionPublishesOneTaskBomReference() throws Exception {
    var batch=launch();var task=task(batch);var barrier=new CyclicBarrier(2);
    try(var pool=Executors.newFixedThreadPool(2)) {
      var a=pool.submit(()->{barrier.await();return repricing.prepare(batch.getRepriceNo(),task.getId());});
      var b=pool.submit(()->{barrier.await();return repricing.prepare(batch.getRepriceNo(),task.getId());});
      assertThat(a.get(30,TimeUnit.SECONDS).bom().getSyncRecordId()).isEqualTo(b.get(30,TimeUnit.SECONDS).bom().getSyncRecordId());
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot WHERE sync_status='SUCCESS'",Integer.class)).isOne();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot_detail",Integer.class)).isEqualTo(5);
  }

  @Test void changedProductIdentityAndAmbiguousSavedOrganizationAreExplicitFailures() {
    var batch=launch();var task=task(batch);item.setMaterialNo("OTHER");
    assertThatThrownBy(()->repricing.prepare(batch.getRepriceNo(),task.getId())).hasMessageContaining("身份已变化");
    item.setMaterialNo("TOP");jdbc.update("UPDATE lp_cost_run_part_item SET material_organization_code=NULL");
    assertThatThrownBy(()->repricing.prepare(batch.getRepriceNo(),task.getId())).hasMessageContaining("组织依据");
    verifyNoInteractions(LIVE_U9,STATUS);
    assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(request_snapshot_json,'$.bomInput') FROM lp_cost_run_task WHERE id=?",String.class,task.getId())).isNull();
  }

  static void supplement(String month,String status) {
    jdbc.update("INSERT INTO lp_quote_bom_supplement_version(preparation_id,oa_no,oa_form_item_id,quote_product_code,product_type,supplement_scope,bom_source,version_no,version_status,active_flag,period_month,composition_fingerprint) VALUES(1,'MRP1130-TEST',301,'TOP','NON_BARE','NON_BARE_FULL_BOM','ELECTRONIC_DRAWING_BOM',?, ?,1,?,?)",jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM lp_quote_bom_supplement_version",Integer.class),status,month,UUID.randomUUID().toString());
  }
  static List<BomRawHierarchy> tree(String name) {
    var rows=new ArrayList<BomRawHierarchy>();
    var paths=new ArrayList<>(List.of("/TOP/","/TOP/A/","/TOP/A/C/","/TOP/B/","/TOP/B/C/"));
    if (name.equals("V2")) paths.add("/TOP/B/D/");
    for(String path:paths) {
      var row=new BomRawHierarchy();var codes=path.substring(1,path.length()-1).split("/");
      row.setTopProductCode("TOP");row.setMaterialCode(codes[codes.length-1]);row.setMaterialName(name);
      row.setParentCode(codes.length>1?codes[codes.length-2]:null);row.setLevel(codes.length-1);row.setPath(path);
      row.setQtyPerParent(name.equals("V2")?new BigDecimal("2"):BigDecimal.ONE);
      row.setQtyPerTop(row.getQtyPerParent());rows.add(row);
    }
    return rows;
  }
}
