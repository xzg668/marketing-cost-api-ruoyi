package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataQueryService;
import com.sanhua.marketingcost.service.ProductCostingPipeline;
import com.sanhua.marketingcost.service.QuoteEffectiveBomApplicationService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Real disposable MySQL/BOM/prices/cost publication; approved input is supplied at its existing port. */
@Tag("integration")
class BarePackageCostingIntegrationTest extends OriginalPricingIntegrationTestBase {
  @org.springframework.test.context.DynamicPropertySource
  static void pricingMonth(org.springframework.test.context.DynamicPropertyRegistry properties) {
    properties.add("monthly.reprice.go-live-months.COMMERCIAL",CostPricingPeriodUtils::currentPricingMonth);
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper hierarchy;
  @Autowired ProductCostingPipeline pipeline;
  @Autowired QuoteEffectiveBomApplicationService trees;
  @Autowired com.sanhua.marketingcost.service.technicaldata.QuoteTechnicalDataPersistenceService technicalPersistence;
  @MockBean EffectiveTechnicalDataQueryService technicalInput;
  @org.springframework.boot.test.mock.mockito.SpyBean
  com.sanhua.marketingcost.service.technicaldata.TechnicalDataSalarySourceQuery salarySources;
  String product, material, month;
  long form, item;
  LocalDateTime date;

  @BeforeEach void fixture() {
    product="BARE-PACK-"+UUID.randomUUID().toString().substring(0,8);
    material=product+"-PART";
    month=CostPricingPeriodUtils.currentPricingMonth();
    date=YearMonth.parse(month).atDay(1).atTime(12,0);
    var auth=new UsernamePasswordAuthenticationToken("admin",null,List.of(new SimpleGrantedAuthority("ROLE_admin")));
    auth.setDetails(Map.of("userId",1L,BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE,"COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",product);
    form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,product);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type,shipping_fee) VALUES(?,?,?,'COMMERCIAL',0)",form,product,product);
    item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
    var sources=new OrdinaryCostingTestSources(jdbc,hierarchy);
    sources.ordinarySources(product,material,form,date);
    sources.comparisonFixedPrice(material,"2",date);
    jdbc.update("DELETE FROM lp_bom_raw_hierarchy WHERE top_product_code=? AND material_code IN (?,?)",product,product+"-ZERO-PACK",product+"-ZERO-CHILD");
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=? AND level>0",product);
    jdbc.update("INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,parent_material_no,child_material_no,child_material_name,stock_unit,bom_purpose,bom_version,child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type) SELECT source_import_batch_id,'U9',NOW(),price_org_code,parent_code,material_code,material_name,'件',bom_purpose,bom_version,sort_seq,qty_per_parent,1,effective_from,effective_to,'NORMAL' FROM lp_bom_raw_hierarchy WHERE top_product_code=? AND level>0",product);
    when(technicalInput.preparationMaterials(anyLong(),anyString())).thenReturn(List.of());
  }

  @AfterEach void clearActor() { SecurityContextHolder.clearContext(); }

  @Test void bodyTreeAndPriceChecksExistBeforePackagingAndApprovedPackagingIsCostedExactlyOnce() {
    var missing=pipeline.execute(new ProductCostingRequest(product,item,month,"bare-package-test",true));
    assertThat(missing.getBlockingStatus()).isEqualTo("WAIT_TECH_DATA");
    assertThat(missing.getTechnicalDataCheck().modules()).anySatisfy(m -> {
      assertThat(m.moduleType()).isEqualTo("PACKAGE"); assertThat(m.required()).isTrue();
    });
    assertThat(trees.getEffectiveBom(product,item).nodes()).hasSize(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_bom_costing_row WHERE oa_form_item_id=?",Integer.class,item)).isPositive();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",Integer.class,item)).isZero();

    String box=product+"-BOX";
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,main_category_name,shape_attr) VALUES(?,'包装纸箱','件','COMMERCIAL',?,1,'25','包装材料','采购件')",box,product);
    new OrdinaryCostingTestSources(jdbc,hierarchy).comparisonFixedPrice(box,"3",date);
    var taskRecord=new com.sanhua.marketingcost.entity.QuoteTechTask();
    taskRecord.setTaskNo(product);taskRecord.setOaFormId(form);taskRecord.setOaFormItemId(item);
    taskRecord.setOaNo(product);taskRecord.setAccountingMonth(month);taskRecord.setBusinessUnitType("COMMERCIAL");
    taskRecord.setApplicableOrgCode("210");taskRecord.setAssigneeUserId(1L);taskRecord.setAssigneeName("隔离验收");
    taskRecord.setSourceSystem("QUOTE_ADMIN_DIRECT");taskRecord.setTaskStatus("SUBMITTED");
    long task=technicalPersistence.createTask(taskRecord).getId();
    var productRecord=new com.sanhua.marketingcost.entity.QuoteTechProduct();
    productRecord.setTaskId(task);productRecord.setOaFormItemId(item);productRecord.setMaterialNo(product);
    productRecord.setQuoteNo(product);productRecord.setAccountingMonth(month);productRecord.setContentSchemaVersion(2);
    productRecord.setSourceSnapshotJson("{}");productRecord.setSourceFingerprint("a".repeat(64));
    long technicalProduct=technicalPersistence.createProduct(productRecord).getId();
    jdbc.update("INSERT INTO lp_quote_tech_data_version(product_id,version_no,version_status,content_fingerprint) VALUES(?,1,'SUBMITTED',?)",technicalProduct,"a".repeat(64));
    long version=jdbc.queryForObject("SELECT id FROM lp_quote_tech_data_version WHERE product_id=?",Long.class,technicalProduct);
    jdbc.update("UPDATE lp_quote_tech_product SET product_status='SUBMITTED',effective_version_id=?,latest_submitted_version_id=? WHERE id=?",version,version,technicalProduct);
    var line=new EffectiveTechnicalDataInput.MaterialLine("PACKAGE","PACKAGE:"+version+":71",box,"包装纸箱",new BigDecimal("2"),"件",version);
    var approved=new EffectiveTechnicalDataInput(technicalProduct,version,1,month,
        EffectiveTechnicalDataInput.SOURCE_EFFECTIVE_VERSION,"a".repeat(64),LocalDateTime.now(),
        false,false,false,null,null,null,List.of(),List.of(),List.of(),null,null,null,
        List.of(new EffectiveTechnicalDataInput.ModuleSource("PACKAGE",technicalProduct,version,version,"a".repeat(64))),List.of(line));
    when(technicalInput.preparationMaterials(item,month)).thenReturn(List.of(line));
    when(technicalInput.resolve(item,month)).thenReturn(approved);
    var expanded=trees.getEffectiveBom(product,item);
    assertThat(expanded.nodes()).hasSize(3).anySatisfy(n -> assertThat(n.materialCode()).isEqualTo(box));
    var success=pipeline.execute(new ProductCostingRequest(product,item,month,"bare-package-test",true));
    assertThat(success.getPipelineStatus()).as(success.getMessage()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",Integer.class,success.getCostVersionId(),box)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT amount FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",BigDecimal.class,success.getCostVersionId(),box)).isEqualByComparingTo("6");
    var repeated=pipeline.execute(new ProductCostingRequest(product,item,month,"bare-package-test",false));
    assertThat(repeated.getCostVersionId()).isEqualTo(success.getCostVersionId());
  }

  @Test void oneSourceCheckReportsPackagingSalaryNetLossAndMissingMaterialPriceTogether() {
    jdbc.update("DELETE FROM cms_cost_source_effective WHERE parent_code=?",product);
    jdbc.update("DELETE FROM lp_quality_loss_rate WHERE bare_product_code=?",product);
    jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?",material);
    org.mockito.Mockito.doReturn(new com.sanhua.marketingcost.service.technicaldata.TechnicalDataSalarySourceQuery.Source(List.of(),51L))
        .when(salarySources).read(product,date.getYear(),"COMMERCIAL");
    var result=pipeline.prepare(new ProductCostingRequest(product,item,month,"bare-package-test",true));
    assertThat(trees.getEffectiveBom(product,item).nodes()).hasSize(2);
    assertThat(result.getTechnicalDataCheck().modules().stream().filter(m -> m.required()).map(m -> m.moduleType()))
        .contains("PACKAGE","SALARY","NET_LOSS","PRICE");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",Integer.class,item)).isZero();
  }
}
