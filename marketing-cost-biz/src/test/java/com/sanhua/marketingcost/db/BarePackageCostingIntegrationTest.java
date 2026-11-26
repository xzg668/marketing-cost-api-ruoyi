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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
  @Autowired com.sanhua.marketingcost.service.QuoteProductBomPreparationService preparation;
  @Autowired com.sanhua.marketingcost.service.ingest.QuoteBomStatusService bomStatus;
  @Autowired com.sanhua.marketingcost.service.CostInputRevisionService revisions;
  @Autowired com.sanhua.marketingcost.mapper.OaFormMapper forms;
  @Autowired com.sanhua.marketingcost.mapper.OaFormItemMapper items;
  @Autowired com.sanhua.marketingcost.service.technicaldata.QuoteTechnicalDataPersistenceService technicalPersistence;
  @MockBean EffectiveTechnicalDataQueryService technicalInput;
  @org.springframework.boot.test.mock.mockito.SpyBean
  com.sanhua.marketingcost.service.technicaldata.TechnicalDataSalarySourceQuery salarySources;
  String product, material, month;
  String seededSalarySubject;
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

  @AfterEach void clearActor() {
    try {
      if (seededSalarySubject != null) {
        jdbc.update("DELETE FROM cms_subject_setting_raw WHERE second_subject_code=?", seededSalarySubject);
        jdbc.update("DELETE FROM cms_sync_publish_signal WHERE batch_no=?", product);
      }
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Autowired com.sanhua.marketingcost.service.QuoteCostingWorkbenchService workbench;
  @Autowired com.sanhua.marketingcost.service.technicaldata.TechnicalDataQuoteSourceReader sourceReader;

  @Test void recoveredBareWorkspaceBuildsRealRowsAndReplacesOldModuleChecklist() {
    jdbc.update("UPDATE lp_material_master_raw SET main_category_code='110001' WHERE material_code=?",product);
    jdbc.update("""
        INSERT INTO lp_quote_costing_workspace(oa_no,oa_form_item_id,product_code,period_month,business_unit_type,
            workspace_status,current_step,last_checked_at)
        VALUES(?,?,?,?,'COMMERCIAL','WAIT_BOM','QUOTE_BOM',NOW())
        """,product,item,"MODEL:"+product,month);
    var checked = sourceReader.recheck(item, month);
    assertThat(checked.modules()).filteredOn(row -> row.required()).extracting(row -> row.moduleType())
        .doesNotContain("PROFILE", "SOLDER", "AUXILIARY", "DRAWING_BOM").contains("PACKAGE");
    var page = workbench.getWorkbench(product,item);
    assertThat(page.getBomRows()).isNotEmpty();
    assertThat(page.getCostingWorkspace().getProductCode()).isEqualTo(product);
    sourceReader.recheck(item, month);
    assertThat(workbench.getWorkbench(product,item).getBuildBatchId()).isEqualTo(page.getBuildBatchId());
  }

  @Test void modelOnlyUsesFinishedBomAndSelectedCodeThroughoutCostingWithoutChangingOaInput() {
    String model = product + "-MODEL";
    jdbc.update("UPDATE lp_material_master_raw SET material_model=?, main_category_code='10001' WHERE material_code=?", model, product);
    jdbc.update("UPDATE oa_form_item SET material_no=NULL, sunl_model=? WHERE id=?", model, item);
    var match = workbench.getWorkbench(product,item).getModelBomMatch();
    assertThat(match.candidates()).hasSize(1);
    assertThat(match.selected()).isEqualTo(product);
    approvePackaging();
    workbench.prepareModelProduct(product,item,month,model,product);
    var page = workbench.getWorkbench(product,item);
    assertThat(page.getItem().getMaterialNo()).isEqualTo(product);
    assertThat(page.getModelBomMatch().saved()).isTrue();
    assertThat(page.getBomRows()).isNotEmpty();
    assertThat(trees.getEffectiveBom(product,item).nodes()).extracting(row -> row.materialCode()).contains(product,material);
    assertThat(items.selectById(item).getMaterialNo()).isNull();
    assertThat(bomStatus.checkItemForCostRun(product,item,month).getProductCode()).isEqualTo(product);
    var outcome = pipeline.execute(new ProductCostingRequest(product,item,month,"model-only-test",true));
    assertThat(outcome.getPipelineStatus()).as(outcome.getMessage()).isEqualTo("SUCCESS");
  }

  @ParameterizedTest
  @ValueSource(strings = {"S60BH-126HZ-04", "S60BH-28HX-01", "S60BH-30HZ-32",
      "S60BH-40HZ-57", "S60BH-46HZ-23"})
  void modelOnlyFindsU9BomForPlateHeatExchangerCategory(String model) {
    jdbc.update("UPDATE oa_form_item SET material_no=NULL, product_name='钎焊板式换热器', sunl_model=? WHERE id=?", model, item);
    jdbc.update("UPDATE lp_material_master_raw SET organization_code='PLATE' WHERE material_code IN (?,?)", product, material);
    jdbc.update("UPDATE lp_material_master_raw SET material_model=?, main_category_code='111021053' WHERE material_code=?", model, product);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET price_org_code='220' WHERE top_product_code=?", product);
    jdbc.update("UPDATE lp_bom_u9_source SET price_org_code='220' WHERE parent_material_no=?", product);

    var match = workbench.getWorkbench(product, item).getModelBomMatch();
    assertThat(match.organization()).isEqualTo("PLATE");
    assertThat(match.selected()).isEqualTo(product);
    var checked = bomStatus.checkItemForCostRun(product, item, month);
    assertThat(checked.getProductCode()).isEqualTo(product);
    assertThat(checked.getBomSource()).isEqualTo("U9");
    assertThat(checked.getBomStatus()).isNotEqualTo("NO_BOM");
    workbench.prepareModelProduct(product, item, month, model, product);
    assertThat(trees.getEffectiveBom(product, item).nodes()).extracting(row -> row.materialCode())
        .contains(product, material);
    assertThat(items.selectById(item).getMaterialNo()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"10001", "15001"})
  void multipleExactMatchesRequireThisRowsFinanceSelectionAndBuildOnlyTheChosenBom(String otherCategory) {
    String model = product + "-MODEL";
    String other = product + "-F2", child = other + "-PART";
    var sources = new OrdinaryCostingTestSources(jdbc, hierarchy);
    sources.ordinarySources(other,child,form,date);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=? AND level>0",other);
    jdbc.update("INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,parent_material_no,child_material_no,child_material_name,stock_unit,bom_purpose,bom_version,child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type) SELECT source_import_batch_id,'U9',NOW(),price_org_code,parent_code,material_code,material_name,'件',bom_purpose,bom_version,sort_seq,qty_per_parent,1,effective_from,effective_to,'NORMAL' FROM lp_bom_raw_hierarchy WHERE top_product_code=? AND level>0",other);
    jdbc.update("UPDATE lp_material_master_raw SET material_model=?,main_category_code='10001' WHERE material_code=?", model, product);
    jdbc.update("UPDATE lp_material_master_raw SET material_model=?,main_category_code=? WHERE material_code=?", model, otherCategory, other);
    jdbc.update("UPDATE oa_form_item SET material_no=NULL,sunl_model=? WHERE id=?",model,item);
    var match = workbench.getWorkbench(product,item).getModelBomMatch();
    assertThat(match.candidates()).extracting(row -> row.materialCode()).containsExactlyInAnyOrder(product,other);
    assertThat(match.selected()).isNull();
    assertThat(bomStatus.checkItemForCostRun(product,item,month).getBomStatus()).isNotEqualTo("NO_BOM");
    workbench.prepareModelProduct(product,item,month,model,other);
    assertThat(trees.getEffectiveBom(product,item).nodes()).extracting(row -> row.materialCode()).contains(other,child).doesNotContain(product,material);
    assertThat(items.selectById(item).getMaterialNo()).isNull();
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,product_name,sunl_model,business_unit_type,seq) VALUES(?,?,?,'COMMERCIAL',2)",form,product,model);
    long another = jdbc.queryForObject("SELECT MAX(id) FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
    assertThat(workbench.getWorkbench(product,another).getModelBomMatch().selected()).isNull();
  }

  @Test void modelOnlyCountsEffectiveBomsInTheQuoteOrganization() {
    String model = product + "-MODEL";
    String otherOrganization = product + "-OR", expired = product + "-EX";
    var sources = new OrdinaryCostingTestSources(jdbc, hierarchy);
    sources.ordinarySources(otherOrganization, otherOrganization + "-PART", form, date);
    sources.ordinarySources(expired, expired + "-PART", form, date);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET price_org_code='220' WHERE top_product_code=?", otherOrganization);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET effective_from='2000-01-01', effective_to='2000-12-31' WHERE top_product_code=?", expired);
    jdbc.update("UPDATE lp_material_master_raw SET material_model=?, main_category_code=NULL WHERE material_code=?", model, product);
    jdbc.update("UPDATE lp_material_master_raw SET material_model=?, main_category_code='10001' WHERE material_code IN (?,?)", model, otherOrganization, expired);
    jdbc.update("UPDATE oa_form_item SET material_no=NULL, sunl_model=? WHERE id=?", model, item);

    var match = workbench.getWorkbench(product, item).getModelBomMatch();
    assertThat(match.candidates()).extracting(row -> row.materialCode()).containsExactly(product);
    assertThat(match.selected()).isEqualTo(product);
  }

  @Test void explicitMaterialNoUsesItsOwnBomEvenWhenTheModelMatchesAnotherMaterial() {
    String model = product + "-MODEL";
    jdbc.update("UPDATE lp_material_master_raw SET material_model=? WHERE material_code IN (?,?)", model, product, material);
    jdbc.update("UPDATE oa_form_item SET material_no=?, sunl_model=? WHERE id=?", material, model, item);

    var page = workbench.getWorkbench(product, item);
    assertThat(page.getModelBomMatch()).isNull();
    assertThat(page.getItem().getMaterialNo()).isEqualTo(material);
    var missing = bomStatus.checkItemForCostRun(product, item, month);
    assertThat(missing.getProductCode()).isEqualTo(material);
    assertThat(missing.getBomStatus()).isEqualTo("NO_BOM");

    jdbc.update("UPDATE oa_form_item SET material_no=? WHERE id=?", product, item);
    var checked = bomStatus.checkItemForCostRun(product, item, month);
    assertThat(checked.getProductCode()).isEqualTo(product);
    assertThat(checked.getBomSource()).isEqualTo("U9");
    assertThat(workbench.getWorkbench(product, item).getModelBomMatch()).isNull();
  }

  @Test void formalMaterialAfterMissingModelUsesNestedBareBodyAndRechecksMissingMonthlyBom() {
    String finished = product + "-BOXED";
    jdbc.update("UPDATE oa_form_item SET material_no=NULL,sunl_model='S12BH-30L-16' WHERE id=?", item);
    preparation.prepareByOaFormItem(item, date.toLocalDate(), month);
    assertThat(jdbc.queryForObject("SELECT quote_product_code FROM lp_quote_bom_preparation_record WHERE oa_form_item_id=?",String.class,item))
        .isEqualTo("MODEL:S12BH-30L-16");
    jdbc.update("UPDATE oa_form_item SET material_no=? WHERE id=?", product, item);
    // A finished assembly contains three bodies. Until the assembly root arrives the body is disconnected.
    jdbc.update("""
        UPDATE lp_bom_raw_hierarchy SET top_product_code=?,path=CONCAT('/',?,path),
        parent_code=IF(level=0,?,parent_code), qty_per_parent=IF(level=0,3,qty_per_parent),
        qty_per_top=3*qty_per_top,level=level+1 WHERE top_product_code=?
        """, finished, finished, finished, product);
    assertThat(bomStatus.checkItemForCostRun(product,item,month).getBomStatus()).isEqualTo("NO_BOM");
    var outer = hierarchy.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.sanhua.marketingcost.entity.BomRawHierarchy>lambdaQuery()
        .eq(com.sanhua.marketingcost.entity.BomRawHierarchy::getTopProductCode,finished)
        .eq(com.sanhua.marketingcost.entity.BomRawHierarchy::getLevel,1)).getFirst();
    outer.setId(null); outer.setMaterialCode(finished); outer.setPath("/"+finished+"/"); outer.setLevel(0);
    outer.setSourceLineKey("TEST-OUTER"); outer.setQtyPerParent(BigDecimal.ONE); outer.setQtyPerTop(BigDecimal.ONE); hierarchy.insert(outer);
    outer.setId(null); outer.setMaterialCode(finished+"-CARTON"); outer.setLevel(1);
    outer.setSourceLineKey("TEST-CARTON"); outer.setPath("/"+finished+"/CARTON/"); hierarchy.insert(outer);

    var tree = trees.getEffectiveBom(product,item);
    assertThat(tree.state()).as("effective BOM issues %s",tree.blockIssues()).isEqualTo("DRAFT");
    assertThat(tree.nodes()).extracting(n -> n.materialCode()).containsExactly(product,material);
    assertThat(jdbc.queryForObject("""
        SELECT JSON_UNQUOTE(JSON_EXTRACT(raw_node_json,'$.qtyPerTop'))
        FROM lp_quote_bom_monthly_snapshot_detail WHERE monthly_snapshot_id=?
        AND JSON_UNQUOTE(JSON_EXTRACT(raw_node_json,'$.materialCode'))=?
        """,BigDecimal.class,tree.monthlySnapshotId(),material)).isEqualByComparingTo("2.5");
    var check = pipeline.execute(new ProductCostingRequest(product,item,month,"nested-bare-test",true));
    assertThat(check.getTechnicalDataCheck().modules()).filteredOn(m -> m.required())
        .extracting(m -> m.moduleType()).containsExactly("PACKAGE");
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET qty_per_parent=99 WHERE top_product_code=? AND material_code=?",finished,material);
    assertThat(trees.getEffectiveBom(product,item).monthlySnapshotId()).isEqualTo(tree.monthlySnapshotId());
    approvePackaging();
    var result = pipeline.execute(new ProductCostingRequest(product,item,month,"nested-bare-test",true));
    assertThat(result.getPipelineStatus()).as(result.getMessage()).isEqualTo("SUCCESS");
  }

  @Test void bodyTreeAndPriceChecksExistBeforePackagingAndApprovedPackagingIsCostedExactlyOnce() {
    var missing=pipeline.execute(new ProductCostingRequest(product,item,month,"bare-package-test",true));
    assertThat(missing.getBlockingStatus()).isEqualTo("WAIT_TECH_DATA");
    assertThat(missing.getTechnicalDataCheck().modules()).anySatisfy(m -> {
      assertThat(m.moduleType()).isEqualTo("PACKAGE"); assertThat(m.required()).isTrue();
    });
    assertThat(trees.getEffectiveBom(product,item).nodes()).hasSize(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_bom_costing_row WHERE oa_form_item_id=?",Integer.class,item)).isPositive();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",Integer.class,item)).isZero();

    String box=approvePackaging();
    var expanded=trees.getEffectiveBom(product,item);
    assertThat(expanded.nodes()).hasSize(3).anySatisfy(n -> assertThat(n.materialCode()).isEqualTo(box));
    var success=pipeline.execute(new ProductCostingRequest(product,item,month,"bare-package-test",true));
    assertThat(success.getPipelineStatus()).as(success.getMessage()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",Integer.class,success.getCostVersionId(),box)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT amount FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",BigDecimal.class,success.getCostVersionId(),box)).isEqualByComparingTo("6");
    var repeated=pipeline.execute(new ProductCostingRequest(product,item,month,"bare-package-test",false));
    assertThat(repeated.getCostVersionId()).isEqualTo(success.getCostVersionId());
  }

  @Test void costingWaitsForTheFinishedCodeSalaryAndThenUsesItsOwnSalaryAndAuxiliary() {
    approvePackaging();
    String model = product + "-MODEL";
    String specification = product + "-SPEC";
    String bare = product + "-BARE";
    jdbc.update("""
        UPDATE lp_material_master_raw SET material_model=?,material_spec=?,drawing_no=?,bare_code=?
        WHERE material_code=?
        """, model, specification, specification, bare, product);
    jdbc.update("""
        INSERT INTO lp_material_master_raw(material_code,material_name,material_model,material_spec,drawing_no,
          unit,organization_code,import_batch_id,active_flag)
        VALUES(?,'裸品',?,?,?,'件','COMMERCIAL',?,1)
        """, bare, model, specification, specification, product);
    jdbc.update("DELETE FROM cms_cost_source_effective WHERE parent_code=?", product);
    String subject = product + "-SALARY";
    seededSalarySubject = subject;
    jdbc.update("""
        INSERT INTO cms_subject_setting_raw(import_batch_id,row_no,first_subject_code,first_subject_name,
          second_subject_code,second_subject_name,business_unit_type)
        VALUES(1,1,'03','工资',?,'辅助人员工资','COMMERCIAL')
        """, subject);
    jdbc.update("INSERT INTO cms_sync_publish_signal(batch_no,cost_year,business_unit_type,status) VALUES(?,?,'COMMERCIAL','SUCCESS')",
        product, date.getYear());
    addCmsSalary(bare, subject, 4, 1);
    addCmsAuxiliary(bare, 11);

    var missing = pipeline.execute(new ProductCostingRequest(product,item,month,"finished-code-cms",true));
    assertThat(missing.getBlockingStatus()).isEqualTo("WAIT_TECH_DATA");
    assertThat(missing.getTechnicalDataCheck().modules().stream().filter(m -> m.required()).map(m -> m.moduleType()))
        .contains("SALARY");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",
        Integer.class, item)).isZero();

    addCmsSalary(product, subject, 7, 2);
    addCmsAuxiliary(product, 3);
    var exact = pipeline.execute(new ProductCostingRequest(product,item,month,"finished-code-cms",true));
    assertThat(exact.getPipelineStatus()).as(exact.getMessage()).isEqualTo("SUCCESS");
    long version = exact.getCostVersionId();
    assertThat(savedAmount(version, "DIRECT_LABOR")).isEqualByComparingTo("7");
    assertThat(savedAmount(version, "INDIRECT_LABOR")).isEqualByComparingTo("2");
    assertThat(savedAmount(version, "AUX_0201")).isEqualByComparingTo("3.15");
    String snapshot = snapshot(version);
    assertThat(snapshot).contains(product, "cms_workshop_labor_raw", "cms_product_subject_cost_raw");

    String revision = revisions.currentRevision(forms.selectById(form), items.selectById(item), month);
    jdbc.update("UPDATE lp_material_master_raw SET material_spec=?,drawing_no=? WHERE material_code=?",
        specification + "-OTHER", specification + "-OTHER", bare);
    assertThat(revisions.currentRevision(forms.selectById(form), items.selectById(item), month)).isEqualTo(revision);
    var repeated = pipeline.execute(new ProductCostingRequest(product,item,month,"finished-code-cms",false));
    assertThat(repeated.getCostVersionId()).isEqualTo(version);
    assertThat(snapshot(version)).isEqualTo(snapshot);
  }

  private void addCmsSalary(String code, String subject, int direct, int indirect) {
    jdbc.update("""
        INSERT INTO cms_workshop_labor_raw(import_batch_id,row_no,period,parent_code,source_row_id,
          working_cost_cent,working_cost_yuan,business_unit_type) VALUES(1,1,?,?,?, ?,?,'COMMERCIAL')
        """, month, code, code, direct * 100, direct);
    jdbc.update("""
        INSERT INTO cms_product_subject_cost_raw(import_batch_id,row_no,period,parent_code,source_row_id,
          second_subject_code,material_price,material_price_yuan,business_unit_type)
        VALUES(1,1,?,?,?,?,?,?,'COMMERCIAL')
        """, month, code, code, subject, indirect * 100, indirect);
    for (String type : List.of("SALARY_DIRECT", "SALARY_INDIRECT")) {
      boolean primary = "SALARY_DIRECT".equals(type);
      String table = primary ? "cms_workshop_labor_raw" : "cms_product_subject_cost_raw";
      var id = jdbc.queryForObject("SELECT id FROM " + table + " WHERE parent_code=?", Long.class, code);
      jdbc.update("""
          INSERT INTO cms_cost_source_effective(cost_year,source_type,parent_code,period,subject_code,subject_name,
            source_table,source_row_ids,amount_yuan,business_unit_type) VALUES(?,?,?,?,?,?,?,?,?,'COMMERCIAL')
          """, date.getYear(), type, code, month, primary ? "0301" : subject,
          primary ? "直接人工工资" : "辅助人员工资", table, id.toString(), primary ? direct : indirect);
    }
  }

  private void addCmsAuxiliary(String code, int amount) {
    jdbc.update("""
        INSERT INTO cms_product_subject_cost_raw(import_batch_id,row_no,period,parent_code,source_row_id,
          second_subject_code,material_price,material_price_yuan,business_unit_type)
        VALUES(1,2,?,?,?,'0201',?,?,'COMMERCIAL')
        """, month, code, code + "-AUX", amount * 100, amount);
    long id = jdbc.queryForObject("SELECT id FROM cms_product_subject_cost_raw WHERE source_row_id=?",
        Long.class, code + "-AUX");
    jdbc.update("""
        INSERT INTO cms_cost_source_effective(cost_year,source_type,parent_code,period,subject_code,subject_name,
          source_table,source_row_ids,amount_yuan,business_unit_type)
        VALUES(?,'AUX_SUBJECT',?,?,'0201','工艺辅料','cms_product_subject_cost_raw',?,?,'COMMERCIAL')
        """, date.getYear(), code, month, Long.toString(id), amount);
  }

  private BigDecimal savedAmount(long version, String type) {
    return jdbc.queryForObject("SELECT amount FROM lp_cost_run_cost_item WHERE cost_run_version_id=? AND cost_code=?",
        BigDecimal.class, version, type);
  }

  private String snapshot(long version) {
    return jdbc.queryForObject("SELECT jianji_snapshot_json FROM lp_quote_cost_run_version WHERE id=?", String.class, version);
  }

  private String approvePackaging() {
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
    return box;
  }

  @Test void oneSourceCheckReportsPackagingSalaryNetLossAndMissingMaterialPriceTogether() {
    jdbc.update("DELETE FROM cms_cost_source_effective WHERE parent_code=?",product);
    jdbc.update("DELETE FROM lp_quality_loss_rate WHERE bare_product_code=?",product);
    jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?",material);
    org.mockito.Mockito.doReturn(List.of())
        .when(salarySources).read(product,date.getYear(),"COMMERCIAL");
    var result=pipeline.prepare(new ProductCostingRequest(product,item,month,"bare-package-test",true));
    assertThat(trees.getEffectiveBom(product,item).nodes()).hasSize(2);
    assertThat(result.getTechnicalDataCheck().modules().stream().filter(m -> m.required()).map(m -> m.moduleType()))
        .contains("PACKAGE","SALARY","NET_LOSS","PRICE");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",Integer.class,item)).isZero();
  }

  @Test void missingNetLossExplainsWhyTheProductCannotBeCostedAndHowToContinue() {
    approvePackaging();
    jdbc.update("DELETE FROM lp_quality_loss_rate WHERE bare_product_code=?", product);
    var result = pipeline.execute(new ProductCostingRequest(product, item, month, "net-loss-message-test", true));
    String message = "未取到净损失率，无法计算成本。请补齐净损失率后重新核算。";
    assertThat(result.getBlockingStatus()).isEqualTo("WAIT_TECH_DATA");
    assertThat(result.getMessage()).contains(message);
    assertThat(result.getTechnicalDataCheck().modules()).filteredOn(module -> "NET_LOSS".equals(module.moduleType()))
        .singleElement().satisfies(module -> {
          assertThat(module.required()).isTrue();
          assertThat(module.reason()).isEqualTo(message);
        });
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?", Integer.class, item)).isZero();
  }
}
