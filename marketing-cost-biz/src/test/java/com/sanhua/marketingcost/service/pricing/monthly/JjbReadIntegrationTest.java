package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static com.sanhua.marketingcost.service.pricing.monthly.MonthlyCostVersionIntegrationTest.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.controller.CostRunDetailController;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost;
import com.sanhua.marketingcost.service.jjb.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Real source -> adoption -> production cost storage -> production version reader, no final-result fixture SQL. */
@Tag("integration")
class JjbReadIntegrationTest {
  static JjbReadService reader;
  @BeforeAll static void setup() throws Exception {
    database();
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/jjb-scope-schema.sql")).execute(jdbc.getDataSource());
    jdbc.update("INSERT INTO oa_form(id,oa_no,business_unit_type) VALUES(1,'MPP05-LINKED','COMMERCIAL')");
    jdbc.update("INSERT INTO oa_form_item(id,oa_form_id,material_no,business_unit_type,deleted) VALUES(1,1,'TOP','COMMERCIAL',0)");
    var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
    for(var type:List.of(QuoteCostRunVersionMapper.class,OaFormMapper.class,OaFormItemMapper.class,CostRunTraceSnapshotMapper.class)) config.addMapper(type);
    var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(jdbc.getDataSource());factory.setConfiguration(config);
    var sql=new SqlSessionTemplate(factory.getObject());
    reader=new JjbReadService(new JjbVersionAccess(sql.getMapper(QuoteCostRunVersionMapper.class),sql.getMapper(OaFormMapper.class),sql.getMapper(OaFormItemMapper.class)),costing,new JjbProjection(new ObjectMapper()),sql.getMapper(CostRunTraceSnapshotMapper.class));
  }
  @AfterAll static void close(){stop();}
  @BeforeEach void login(){auth("COMMERCIAL");}
  @AfterEach void logout(){SecurityContextHolder.clearContext();}
  static void auth(String unit){var auth=new UsernamePasswordAuthenticationToken("jjb-read-test","unused",List.of(new SimpleGrantedAuthority("ingest:quote:list")));auth.setDetails(Map.of("businessUnitType",unit));SecurityContextHolder.getContext().setAuthentication(auth);}
  static JjbReadModel.Row row(JjbReadModel model,String code){return model.rows().stream().filter(r->code.equals(r.code())).findFirst().orElseThrow();}
  @Test void actualPersistedGoldenHasExactDPEQFAiAndThreeSavedTotals() throws Exception {
    var f=fixture("2","92000",true);saveCompleted(f);prepared.set(0);
    var view=reader.read(f.version().getOaNo(),1L,f.version().getId());var m=view.sheet();
    assertThat(prepared.get()).isLessThanOrEqualTo(9);
    assertThat(m.identity().versionId()).isEqualTo(f.version().getId());assertThat(m.identity().monthly()).isTrue();
    var hh=m.rows().stream().filter(r->r.kind().equals("HOUSEHOLD")).findFirst().orElseThrow();
    assertThat(hh.unitPrice()).isEqualTo("20.985300");assertThat(hh.quantity()).isEqualTo("2");assertThat(hh.amount()).isEqualTo("51.641245");
    assertThat(hh.householdRate()).isEqualTo("0.085");assertThat(hh.salesRate()).isEqualTo("0.25");assertThat(hh.coefficient()).isEqualTo("1.05");
    assertThat(m.totals()).isEqualTo(new JjbReadModel.Totals("267.627207","-8.000000","259.627207"));
    assertThat(row(m,"MGMT_EXP").amount()).isEqualTo("14.847000");assertThat(row(m,"SALES_EXP").amount()).isEqualTo("37.117500");
    assertThat(m.rows().stream().filter(r->r.kind().equals("FEE")).map(JjbReadModel.Row::amount)).containsExactly("1.000000","2.000000","3.000000","4.000000");
    var api=new CostRunDetailController(reader).getDetail(f.version().getOaNo(),"TOP",f.version().getCostRunNo(),1L,f.version().getId()).getData();
    assertThat(api.getSheet()).isEqualTo(m);var wire=new ObjectMapper().writeValueAsString(m);
    assertThat(wire).doesNotContain("cells","fileKey","annualUsage","referenceWeight");assertThat(m.monthlyPrices()).allSatisfy(p->{assertThat(p.evidenceHash()).hasSize(64);assertThat(p.consumerOrganizations()).contains("210");});
  }
  @Test void noHouseholdAndNoProfileHaveNoHouseholdRowsAndExactlyFourBlankFees() throws Exception {
    var f=fixture("2","100000",false);var plan=new MonthlyAdoptionPlan(f.plan().selections().entrySet().stream()
        .filter(e->e.getValue().fact().type()==MonthlyAdoptionPlan.PricingType.NORMAL).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue)),
        f.plan().uses().stream().filter(u->u.path().equals("root/CU")).toList(),Set.of());
    costing.calculateAndSave("COMMERCIAL",f.version().getId(),plan,f.ordinary(),null,f.copper(),Map.of("root/CU",f.bindings().get("root/CU")));
    var m=reader.read(f.version().getOaNo(),1L,f.version().getId()).sheet();
    assertThat(m.rows()).noneMatch(r->r.section().equals("HOUSEHOLD"));assertThat(m.rows().stream().filter(r->r.kind().equals("FEE"))).hasSize(4).allMatch(r->r.amount()==null&&r.feeState().equals("MISSING"));
    assertThat(m.totals().cuMaterialAdjustment()).isEqualTo("0.000000");
  }
  @Test void sourcesCanDisappearAndEveryReadIsIdenticalWithoutWritingOrRepricing() throws Exception {
    var f=fixture("2.5","120000",true);saveCompleted(f);var before=reader.byRun(f.version().getCostRunNo());
    var db=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",f.version().getId());
    long prices=jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class);
    jdbc.update("DELETE FROM lp_finance_effective_price WHERE original_id=?",f.linkedRuleId());jdbc.update("DELETE FROM lp_price_linked_item WHERE id=?",f.linkedRuleId());
    for(int i=0;i<3;i++) assertThat(reader.read(f.version().getOaNo(),1L,f.version().getId()).sheet()).isEqualTo(before.sheet());
    assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",f.version().getId())).isEqualTo(db);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class)).isEqualTo(prices);
  }
  @Test void crossUnitWrongItemWrongOaAndConflictingSelectorsNeverFallBack() throws Exception {
    var f=fixture("1","100000",false);saveCompleted(f);long id=f.version().getId();String oa=f.version().getOaNo();
    assertThatThrownBy(()->reader.read(oa,999L,id)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(()->reader.read("WRONG",1L,id)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(()->reader.resolve(oa,"TOP",1L,id,"MISSING-RUN")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(()->reader.resolve(oa,"TOP",1L,null,"MISSING-RUN")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(()->reader.resolve(oa,"WRONG",1L,id,null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    auth("HOUSEHOLD");assertThatThrownBy(()->reader.read(oa,1L,id)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    SecurityContextHolder.clearContext();assertThatThrownBy(()->reader.read(oa,1L,id)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }
  @Test void ambiguousLegacyUrlRequiresExplicitItemEvenWhenSiblingHasNoCostVersion() throws Exception {
    var f=fixture("1","100000",false);saveCompleted(f);
    jdbc.update("INSERT INTO oa_form_item(id,oa_form_id,material_no,business_unit_type,deleted) VALUES(2,1,'TOP','COMMERCIAL',0)");
    try {assertThatThrownBy(()->reader.resolve(f.version().getOaNo(),"TOP",null,null,null)).hasMessageContaining("COST_VERSION_REQUIRED");
      assertThat(reader.resolve(f.version().getOaNo(),"TOP",1L,f.version().getId(),null).sheet().identity().versionId()).isEqualTo(f.version().getId());
    } finally {jdbc.update("DELETE FROM oa_form_item WHERE id=2");}
  }
  @Test void legacySuccessfulVersionKeepsUnrecordedDifferenceAndFinalBlank() {
    var v=versionService.createRunning("MPP05-LINKED",1L,"TOP","2025-01","2025-01",null,"COMMERCIAL");v.setAlgorithmVersion("LEGACY");v.setStatus("SUCCESS");v.setTotalCost(d("12.345678"));versionMapper.updateById(v);
    var m=reader.read("MPP05-LINKED",1L,v.getId()).sheet();assertThat(m.identity().monthly()).isFalse();
    assertThat(m.totals()).isEqualTo(new JjbReadModel.Totals("12.345678",null,null));assertThat(m.monthlyPrices()).isEmpty();
  }
  @Test void changedHouseholdPartAmountIsRejectedInsteadOfHiddenBehindSnapshot() throws Exception {
    var f=fixture("1","100000",false);var saved=saveCompleted(f);
    long id=saved.parts().stream().filter(p->"HOUSEHOLD_INQUIRY".equals(p.getPriceSource())).findFirst().orElseThrow().getId();
    var before=reader.read(f.version().getOaNo(),1L,f.version().getId()).sheet();
    assertThatThrownBy(()->jdbc.update("UPDATE lp_cost_run_part_item SET amount=1 WHERE id=?",id))
        .hasStackTraceContaining("MPP_PRICE_LINK_SCOPE");
    assertThat(reader.read(f.version().getOaNo(),1L,f.version().getId()).sheet()).isEqualTo(before);
    saved.parts().stream().filter(p->p.getId()==id).findFirst().orElseThrow().setAmount(BigDecimal.ONE);
    assertThatThrownBy(()->new JjbProjection(new ObjectMapper()).project(saved,new OaForm(),new OaFormItem(),List.of()))
        .hasMessageContaining("部品金额");
  }
  @Test void changedOrdinaryFeeAmountIsRejectedInsteadOfRecalculated() throws Exception {
    var f=fixture("1","100000",false);saveCompleted(f);
    jdbc.update("UPDATE lp_cost_run_cost_item SET amount=1 WHERE cost_run_version_id=? AND cost_code='MGMT_EXP'",f.version().getId());
    assertThatThrownBy(()->reader.read(f.version().getOaNo(),1L,f.version().getId())).hasMessageContaining("MGMT_EXP");
  }
  @Test void changedDetailOwnershipIsRejected() throws Exception {
    var f=fixture("1","100000",false);saveCompleted(f);
    jdbc.update("UPDATE lp_cost_run_cost_item SET oa_form_item_id=999 WHERE cost_run_version_id=? AND cost_code='MGMT_EXP'",f.version().getId());
    assertThatThrownBy(()->reader.read(f.version().getOaNo(),1L,f.version().getId())).hasMessageContaining("归属");
  }
  @Test void realSavedVersionExportsThroughBothRoutesAndSurvivesSourceReplacement() throws Exception {
    var f=fixture("2","92000",true);saveCompleted(f);
    var exporter=new com.sanhua.marketingcost.service.JjbExportService(reader,new JjbExcelRenderer());
    var m=reader.read(f.version().getOaNo(),1L,f.version().getId()).sheet();
    jdbc.update("DELETE FROM lp_finance_effective_price WHERE original_id=?",f.linkedRuleId());
    jdbc.update("DELETE FROM lp_price_linked_item WHERE id=?",f.linkedRuleId());
    var before=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",f.version().getId());
    long prices=jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class);
    for(boolean legacy:List.of(false,true)) {
      var output=new java.io.ByteArrayOutputStream();
      if(legacy) exporter.export(f.version().getOaNo(),"TOP",1L,f.version().getId(),f.version().getCostRunNo(),output);
      else exporter.exportVersion(f.version().getOaNo(),1L,f.version().getId(),output);
      try(var wb=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(output.toByteArray()))) {
        var s=wb.getSheet("见机表3");
        for(var entry:Map.of("不含税总成本1",m.totals().totalCost(),"材料成本差异",m.totals().cuMaterialAdjustment(),"不含税总成本2",m.totals().finalQuoteAmount()).entrySet())
          JjbExcelRendererTest.amount(s.getRow(JjbExcelRendererTest.find(s,entry.getKey()).getRowIndex()).getCell(5),entry.getValue());
        var hh=m.rows().stream().filter(r->r.kind().equals("HOUSEHOLD")).findFirst().orElseThrow();
        var r=s.getRow(JjbExcelRendererTest.find(s,hh.code()).getRowIndex());
        JjbExcelRendererTest.amount(r.getCell(3),"20.985300");JjbExcelRendererTest.amount(r.getCell(5),"51.641245");
        assertThat(r.getCell(4).getNumericCellValue()).isEqualTo(2);
        assertThat(wb.getNumberOfSheets()).isEqualTo(1);
        assertThat(s.getRow(0).getCell(0).getCellComment().getString().getString())
            .contains("versionId=" + f.version().getId(), "月度价格依据");
        assertThat(wb.getProperties().getCoreProperties().getDescription())
            .isEqualTo("保存成本版本 " + f.version().getId());
      }
    }
    assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",f.version().getId())).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class)).isEqualTo(prices);
  }

  @Test void actualExportRefusesConflictingSelectorsAndCrossBusinessUnitBeforeWriting() throws Exception {
    var f=fixture("1","100000",false);saveCompleted(f);
    var exporter=new com.sanhua.marketingcost.service.JjbExportService(reader,new JjbExcelRenderer());
    var out=new java.io.ByteArrayOutputStream();
    assertThatThrownBy(()->exporter.export(f.version().getOaNo(),"WRONG",1L,f.version().getId(),null,out)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(()->exporter.export(f.version().getOaNo(),"TOP",1L,f.version().getId(),"WRONG",out)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(()->exporter.exportVersion(f.version().getOaNo(),999L,f.version().getId(),out)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    auth("HOUSEHOLD");
    assertThatThrownBy(()->exporter.exportVersion(f.version().getOaNo(),1L,f.version().getId(),out)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(out.size()).isZero();
  }

  @Test void historicalExportKeepsUnrecordedTotalsAndFourFeesBlank() throws Exception {
    var v=versionService.createRunning("MPP05-LINKED",1L,"TOP","2025-01","2025-01",null,"COMMERCIAL");
    v.setAlgorithmVersion("LEGACY");v.setStatus("SUCCESS");v.setTotalCost(d("12.345678"));versionMapper.updateById(v);
    var out=new java.io.ByteArrayOutputStream();new com.sanhua.marketingcost.service.JjbExportService(reader,new JjbExcelRenderer()).exportVersion("MPP05-LINKED",1L,v.getId(),out);
    try(var wb=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(out.toByteArray()))) {
      var s=wb.getSheet("见机表3");
      for(String label:List.of("材料成本差异","不含税总成本2","模具费","认证费","折旧费","工装夹具费"))
        JjbExcelRendererTest.amount(s.getRow(JjbExcelRendererTest.find(s,label).getRowIndex()).getCell(5),null);
      JjbExcelRendererTest.amount(s.getRow(JjbExcelRendererTest.find(s,"不含税总成本1").getRowIndex()).getCell(5),"12.345678");
    }
  }

}
