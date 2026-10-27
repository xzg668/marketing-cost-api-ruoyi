package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.security.JwtUtils;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@AutoConfigureMockMvc
@Transactional
class QuoteRequestSearchIntegrationTest extends BomMapperTestBase {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired JwtUtils jwt;
  String prefix;
  long target;
  long completed;

  @BeforeEach
  void fixture() {
    prefix="SEARCH-"+UUID.randomUUID().toString().substring(0,8);
    for(int i=0;i<28;i++) form("NEW-"+i,"2026-10-01","Other customer","DIRECT_SALE","EXCEL","COMMERCIAL");
    target=form("OLD","2026-01-08","堃霖培训客户","NEW_PRODUCT","OA","COMMERCIAL");
    item(target,"已核算",false,false,"COMMERCIAL");
    item(target,"未核算",false,false,"COMMERCIAL");
    completed=form("DONE","2026-02-01","Completed customer","DIRECT_SALE","OA","COMMERCIAL");
    item(completed,"SUCCESS",false,false,"COMMERCIAL");
    item(completed,"PENDING",true,false,"COMMERCIAL");
    item(completed,"PENDING",false,true,"COMMERCIAL");
    item(completed,"PENDING",false,false,"HOUSEHOLD");
    form("EMPTY","2026-03-01","Empty customer","DIRECT_SALE","OA","COMMERCIAL");
  }

  @Test void exactOldNumberSearchesBeyondTheLoadedFirstPage() throws Exception {
    assertThat(read(request()).path("total").asInt()).isEqualTo(31);
    var result=read(request("COMMERCIAL","  "+prefix+"-OLD  "));
    assertThat(result.path("total").asInt()).isEqualTo(1);
    assertThat(result.path("list").get(0).path("id").asLong()).isEqualTo(target);
  }

  @Test void customerIsFilteredBeforePaginationAndTotal() throws Exception {
    var result=read(request().param("customer"," 堃霖 "));
    assertThat(result.path("total").asInt()).isEqualTo(1);
    assertThat(result.path("list").get(0).path("id").asLong()).isEqualTo(target);
  }

  @Test void scenarioIsFilteredBeforePaginationAndTotal() throws Exception {
    assertThat(read(request().param("quoteScenario","NEW_PRODUCT")).path("total").asInt()).isEqualTo(1);
  }

  @Test void completionUsesActiveVisibleProductLinesRatherThanTheHeader() throws Exception {
    var done=read(request().param("completionStatus","DONE"));
    assertThat(done.path("total").asInt()).isEqualTo(1);
    assertThat(done.path("list").get(0).path("id").asLong()).isEqualTo(completed);
    assertThat(done.path("list").get(0).path("calcStatus").asText()).isEqualTo("已核算");
    var pending=read(request().param("completionStatus","NOT_DONE").param("pageSize","100"));
    assertThat(pending.path("total").asInt()).isEqualTo(30);
    for(var row:pending.path("list")) assertThat(row.path("calcStatus").asText()).isEqualTo("未核算");
  }

  @Test void runningLinesRemainIncompleteDespiteAnEarlierCalculationDate() throws Exception {
    jdbc.update("UPDATE oa_form_item SET calc_status='RUNNING',calc_at=NOW() WHERE oa_form_id=? AND deleted=0 AND business_unit_type='COMMERCIAL'",completed);
    assertThat(read(request().param("completionStatus","DONE")).path("total").asInt()).isZero();
  }

  @Test void combinedFiltersFindAnOldRowAndRespectPagination() throws Exception {
    var result=read(request().param("processCode","FI-SC").param("sourceType","OA")
        .param("quoteScenario","NEW_PRODUCT").param("customer","堃霖")
        .param("completionStatus","NOT_DONE").param("classificationStatus","CONFIRMED").param("pageSize","1"));
    assertThat(result.path("total").asInt()).isEqualTo(1);
    assertThat(result.path("list").get(0).path("id").asLong()).isEqualTo(target);
    assertThat(read(request().param("customer","堃霖").param("pageNo","2").param("pageSize","1")).path("list").size()).isZero();
  }

  @Test void searchPreservesBusinessUnitIsolation() throws Exception {
    form("HOUSEHOLD","2026-01-08","堃霖培训客户","NEW_PRODUCT","OA","HOUSEHOLD");
    assertThat(read(request().param("customer","堃霖")).path("total").asInt()).isEqualTo(1);
    assertThat(read(request("HOUSEHOLD",prefix).param("customer","堃霖"))
        .path("list").get(0).path("oaNo").asText()).isEqualTo(prefix+"-HOUSEHOLD");
  }

  MockHttpServletRequestBuilder request() {
    return request("COMMERCIAL",prefix);
  }

  MockHttpServletRequestBuilder request(String unit,String number) {
    return get("/api/v1/quote-requests").param("oaNo",number)
        .header("Authorization","Bearer "+jwt.generateToken("admin",unit));
  }

  JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
    var response=mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
    var result=json.readTree(response.getContentAsByteArray());
    assertThat(result.path("code").asInt()).isZero();
    return result.path("data");
  }

  long form(String suffix,String date,String customer,String scenario,String source,String unit) {
    String number=prefix+"-"+suffix;
    jdbc.update("INSERT INTO oa_form(oa_no,apply_date,customer,quote_scenario,source_type,process_code,business_unit_type,classification_status,calc_status,deleted) VALUES(?,?,?,?,?,'FI-SC-006',?,'CONFIRMED','已核算',0)",
        number,date,customer,scenario,source,unit);
    return jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,number);
  }

  void item(long form,String state,boolean calculated,boolean deleted,String unit) {
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type,calc_status,calc_at,deleted) VALUES(?,'1047900000031',?,?,IF(?,NOW(),NULL),?)",
        form,unit,state,calculated,deleted);
  }
}
