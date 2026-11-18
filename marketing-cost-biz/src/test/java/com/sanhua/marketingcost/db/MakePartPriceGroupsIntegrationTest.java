package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.MakePartPriceCalcQueryRequest;
import com.sanhua.marketingcost.service.pricing.MakePartPriceGroups;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@Tag("integration")
class MakePartPriceGroupsIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired MakePartPriceGroups groups;
  final String parent="GROUP-"+UUID.randomUUID();
  static final AtomicLong keys=new AtomicLong(900000000);
  @BeforeEach void login() {auth("COMMERCIAL");}
  @AfterEach void logout() {SecurityContextHolder.clearContext();jdbc.update("DELETE FROM lp_make_part_price_calc_row WHERE parent_material_no=?",parent);}
  static void auth(String unit) {
    var a=new UsernamePasswordAuthenticationToken("group-test","unused");a.setDetails(Map.of("businessUnitType",unit));SecurityContextHolder.getContext().setAuthentication(a);
  }
  MakePartPriceCalcQueryRequest query() {var q=new MakePartPriceCalcQueryRequest();q.setParentMaterialNo(parent);return q;}
  Map<String,Object> row(String oa,String batch,long occurrence,String child,String price) throws Exception {
    var m=new LinkedHashMap<String,Object>();m.put("calc_batch_id",batch);m.put("source_costing_row_id",occurrence);m.put("oa_no",oa);
    m.put("business_unit_type","COMMERCIAL");m.put("pricing_month","2026-11");m.put("price_scenario_type","OA_LOCKED");
    m.put("price_as_of_time","2026-11-14 10:00:00");
    m.put("parent_material_no",parent);m.put("parent_material_name","测试制造件");m.put("drawing_no","D-1");m.put("item_process_type","毛坯加工");
    m.put("child_material_no",child);m.put("child_material_name",child);m.put("stock_unit","只");m.put("qty_per_parent","1");
    m.put("gross_weight_g","26");m.put("net_weight_g","14");m.put("raw_unit_price","0.66070796");m.put("raw_price_type","LINKED");
    m.put("scrap_code","SCRAP");m.put("scrap_unit_price","7.21238938");m.put("scrap_price_type","SCRAP");
    m.put("raw_source_price_record_id",101);m.put("scrap_source_price_record_id",202);m.put("outsource_fee","0");
    m.put("cost_price",price);m.put("parent_total_cost_price",price);m.put("price_complete",1);m.put("status","OK");
    m.put("raw_input_snapshot_json",evidence("210",101,oa));m.put("scrap_input_snapshot_json",evidence("210",202,oa));
    return m;
  }
  String evidence(String org,long source,String quotePath) throws Exception {
    var json=new ObjectMapper();return json.writeValueAsString(Map.of("schemaVersion",1,"document",json.writeValueAsString(
        Map.of("request",Map.of("priceOrgCode",org),"selection",Map.of("monthlyPriceId",source,"basis","same"),"path",quotePath,"quantity",1))));
  }
  long insert(Map<String,Object> row) {
    long id=keys.incrementAndGet();row=new LinkedHashMap<>(row);row.put("id",id);
    jdbc.update("INSERT INTO lp_make_part_price_calc_row (`"+String.join("`,`",row.keySet())+"`) VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
    return id;
  }
  long one(String oa) throws Exception {return insert(row(oa,UUID.randomUUID().toString(),keys.incrementAndGet(),"RAW","0.57415929"));}
  @Test void legacyUtf8mb3OrganizationJoinWorksWithoutSchemaMigration() throws Exception {
    var column=jdbc.queryForMap("SHOW FULL COLUMNS FROM lp_bom_costing_row WHERE Field='business_unit_type'");
    String original=column.get("Collation").toString();
    try {
      jdbc.execute("ALTER TABLE lp_bom_costing_row MODIFY business_unit_type VARCHAR(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL");
      one("OA-LEGACY-CHARSET");assertThat(groups.page(query()).total()).isEqualTo(1);
    } finally {
      jdbc.execute("ALTER TABLE lp_bom_costing_row MODIFY business_unit_type VARCHAR(32) CHARACTER SET utf8mb4 COLLATE "+original+" NULL");
    }
  }
  @Test void sameBasisAcrossQuotesAndRepeatedRunsIsOnePriceWithDistinctQuoteLinks() throws Exception {
    one("OA-A");one("OA-B");long last=one("OA-A");
    var before=jdbc.queryForList("SELECT * FROM lp_make_part_price_calc_row WHERE parent_material_no=? ORDER BY id",parent);
    var page=groups.page(query());assertThat(page.total()).isEqualTo(1);assertThat(page.statusSummary()).containsEntry("OK",1L);
    var g=page.records().getFirst();assertThat(g.id()).isEqualTo(last);assertThat(g.quoteCount()).isEqualTo(2);assertThat(g.calculationCount()).isEqualTo(3);
    var detail=groups.details(g.id(),1,1);assertThat(detail.materials()).hasSize(1);assertThat(detail.quoteTotal()).isEqualTo(2);assertThat(detail.quotations()).hasSize(1);
    assertThat(groups.details(g.id(),2,1).quotations().getFirst().oaNo()).isNotEqualTo(detail.quotations().getFirst().oaNo());
    var q=query();q.setOaNo("OA-A");assertThat(groups.page(q).records().getFirst().quoteCount()).isEqualTo(2);
    assertThat(jdbc.queryForList("SELECT * FROM lp_make_part_price_calc_row WHERE parent_material_no=? ORDER BY id",parent)).isEqualTo(before);
  }
  @Test void weightsMonthOrganizationSourcesAndScenarioKeepSeparatePricesEvenWhenAmountsEqual() throws Exception {
    one("OA-BASE");
    for(String field:List.of("gross_weight_g","pricing_month","price_scenario_type","raw_source_price_record_id","raw_input_snapshot_json")) {
      var r=row("OA-"+field,field,keys.incrementAndGet(),"RAW","0.57415929");
      r.put(field,switch(field){case "gross_weight_g"->"24.8";case "pricing_month"->"2026-10";case "price_scenario_type"->"FINANCE_CURRENT";
        case "raw_source_price_record_id"->999;default->evidence("220",101,"different-org");});insert(r);
    }
    assertThat(groups.page(query()).total()).isEqualTo(6);
  }
  @Test void wholeManufacturingInputSetDefinesPriceAndChildFilterDoesNotDiscardSiblings() throws Exception {
    for(String oa:List.of("OA-A","OA-B")) {
      long occurrence=keys.incrementAndGet();insert(row(oa,oa,occurrence,"RAW-A","1"));insert(row(oa,oa,occurrence,"RAW-B","1"));
    }
    insert(row("OA-C","C",keys.incrementAndGet(),"RAW-A","1"));
    var q=query();q.setChildMaterialNo("RAW-B");var page=groups.page(q);assertThat(page.total()).isEqualTo(1);
    var g=page.records().getFirst();assertThat(g.quoteCount()).isEqualTo(2);assertThat(g.detailCount()).isEqualTo(2);
    assertThat(groups.details(g.id(),1,20).materials()).extracting(r->r.getChildMaterialNo()).containsExactly("RAW-A","RAW-B");
    assertThat(groups.page(query()).total()).isEqualTo(2);
  }
  @Test void groupedCountPaginationAndStatusesAreGlobalNotOnePageDeduplication() throws Exception {
    for(int i=0;i<24;i++) {
      var r=row("OA-"+i,"B-"+i,keys.incrementAndGet(),"RAW","0.57415929");r.put("gross_weight_g",26+i);
      if(i==0) {r.put("status","MISSING_WEIGHT");r.put("price_complete",0);}insert(r);
    }
    var q=query();q.setPageSize(5);q.setPage(2);var page=groups.page(q);assertThat(page.total()).isEqualTo(24);assertThat(page.records()).hasSize(5);
    assertThat(page.statusSummary()).containsEntry("OK",23L).containsEntry("MISSING_WEIGHT",1L);
    q.setPage(1);q.setOnlyError(true);assertThat(groups.page(q).total()).isEqualTo(1);
  }
  @Test void oldOpaqueEvidenceAndMissingOrganizationAreNotGuessedToBeIdentical() throws Exception {
    for(String oa:List.of("OA-A","OA-B")) {var r=row(oa,oa,keys.incrementAndGet(),"RAW","0");r.put("raw_input_snapshot_json","\"legacy-unparsed\"");insert(r);}
    assertThat(groups.page(query()).total()).isEqualTo(2);
  }
  @Test void accessCannotCrossBusinessUnitsOrUseAnUnrelatedRepresentative() throws Exception {
    long id=one("OA-A");auth("HOUSEHOLD");assertThat(groups.page(query()).total()).isZero();
    assertThatThrownBy(()->groups.details(id,1,20)).isInstanceOf(AccessDeniedException.class);
    var q=query();q.setBusinessUnitType("COMMERCIAL");assertThatThrownBy(()->groups.page(q)).isInstanceOf(AccessDeniedException.class);
    SecurityContextHolder.clearContext();assertThatThrownBy(()->groups.page(query())).isInstanceOf(AccessDeniedException.class);
  }
  @Test void excelMatchesGroupedPricesAndIncludesEveryQuoteAndOriginalInput() throws Exception {
    one("OA-A");one("OA-B");one("OA-A");var out=new ByteArrayOutputStream();assertThat(groups.export(query(),out)).isEqualTo(1);
    try(var book=WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
      assertThat(book.getNumberOfSheets()).isEqualTo(3);
      assertThat(book.getSheet("制造件价格汇总").getLastRowNum()).isEqualTo(1);
      assertThat(book.getSheet("关联报价单").getLastRowNum()).isEqualTo(2);
      assertThat(book.getSheet("原料废料明细").getLastRowNum()).isEqualTo(1);
      assertThat(book.getSheet("制造件价格汇总").getRow(1).getCell(7).getNumericCellValue()).isEqualTo(.57415929);
    }
  }
  @Test void manyInputsAreNotTruncatedAndExtraChildrenStaySeparate() throws Exception {
    for(String oa:List.of("OA-A","OA-B","OA-C")) {
      long occurrence=keys.incrementAndGet();
      for(int i=0;i<(oa.equals("OA-C")?21:20);i++) insert(row(oa,oa,occurrence,"RAW-"+i,"0"));
    }
    var page=groups.page(query());assertThat(page.total()).isEqualTo(2);
    assertThat(page.records()).anySatisfy(g->{assertThat(g.detailCount()).isEqualTo(20);assertThat(g.quoteCount()).isEqualTo(2);});
    assertThat(page.records()).anySatisfy(g->{assertThat(g.detailCount()).isEqualTo(21);assertThat(g.quoteCount()).isEqualTo(1);});
    assertThat(jdbc.queryForObject("SELECT @@session.group_concat_max_len",Long.class)).isEqualTo(1024L);
  }
  @Test void groupStatusReflectsAnyMissingChildAndZeroCostRemainsValid() throws Exception {
    long occurrence=keys.incrementAndGet();var missing=row("OA-A","A",occurrence,"RAW-A","0");missing.put("status","MISSING_RAW_PRICE");missing.put("price_complete",0);insert(missing);
    insert(row("OA-A","A",occurrence,"RAW-B","0"));
    var page=groups.page(query());var group=page.records().getFirst();
    assertThat(group.status()).isEqualTo("MISSING_RAW_PRICE");assertThat(group.row().getDisplayReason()).contains("部分原料");
    assertThat(group.row().getParentTotalCostPrice()).isEqualByComparingTo("0");
    var q=query();q.setStatus("OK");assertThat(groups.page(q).total()).isZero();
    assertThat(groups.details(group.id(),1,20).materials()).hasSize(2);
  }

  @Test void modelSearchKeepsEveryInputAndUsesTheSameGroupsForExport() throws Exception {
    long occurrence=keys.incrementAndGet();
    var a=row("OA-MODEL","MODEL",occurrence,"RAW-A","1");a.put("drawing_no","RFK-01");insert(a);
    var b=row("OA-MODEL","MODEL",occurrence,"RAW-B","1");b.put("drawing_no",null);insert(b);
    one("OTHER");
    var q=query();q.setModel(" rfk ");q.setPageSize(1);
    var page=groups.page(q);assertThat(page.total()).isEqualTo(1);
    assertThat(page.records().getFirst().detailCount()).isEqualTo(2);
    assertThat(groups.details(page.records().getFirst().id(),1,20).materials()).hasSize(2);
    var out=new ByteArrayOutputStream();assertThat(groups.export(q,out)).isEqualTo(1);
    try(var book=WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
      assertThat(book.getSheet("原料废料明细").getLastRowNum()).isEqualTo(2);
    }
    q.setModel("%");assertThat(groups.page(q).total()).isZero();
    q.setModel("  ");assertThat(groups.page(q).total()).isEqualTo(2);
  }
}
