package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

@Tag("integration")
class TechnicalDataPendingStateSqlTest {
  @Test void marksLoadsAndCompletesOnlyMatchingPendingWork() {
    try(var db=new MySQLContainer<>("mysql:8.0.36")) {
      db.start();
      var jdbc=new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()));
      jdbc.execute("CREATE TABLE oa_form_item(id BIGINT PRIMARY KEY,material_no VARCHAR(128),oa_form_id BIGINT NOT NULL DEFAULT 1,deleted TINYINT NOT NULL DEFAULT 0)");
      jdbc.execute("CREATE TABLE oa_form(id BIGINT PRIMARY KEY,oa_no VARCHAR(64),business_unit_type VARCHAR(32),deleted TINYINT NOT NULL DEFAULT 0)");
      jdbc.execute("CREATE TABLE lp_quote_tech_task(oa_form_item_id BIGINT,accounting_month VARCHAR(7),active_flag INT)");
      jdbc.execute("CREATE TABLE lp_quote_effective_bom_node(build_batch_id VARCHAR(128),material_code VARCHAR(128))");
      jdbc.execute("CREATE TABLE lp_quote_costing_workspace(id BIGINT PRIMARY KEY,oa_form_item_id BIGINT,period_month VARCHAR(7),business_unit_type VARCHAR(32),last_checked_at DATETIME,technical_check_json JSON,technical_check_fingerprint VARCHAR(64),current_bom_build_batch_id VARCHAR(128),updated_at DATETIME,oa_no VARCHAR(64) NOT NULL DEFAULT 'OA-TEST')");
      String check="""
          {"check":{"oaFormItemId":1,"accountingMonth":"2026-11","fingerprint":"saved","checkedAt":"2026-11-16T10:00:00",
            "modules":[{"moduleType":"PRICE","required":true,"reasonCode":"PRICE_SOURCE_MISSING","reason":"缺价","availability":"MISSING"}],"sharedModules":[]}}
          """;
      jdbc.update("INSERT INTO oa_form VALUES(1,'OA-TEST','COMMERCIAL',0)");
      jdbc.update("INSERT INTO oa_form_item(id,material_no) VALUES(1,'P1'),(2,'P2')");
      jdbc.update("INSERT INTO lp_quote_costing_workspace(id,oa_form_item_id,period_month,business_unit_type,last_checked_at,technical_check_json,technical_check_fingerprint,current_bom_build_batch_id,updated_at) VALUES(0,2,'2026-11','COMMERCIAL',NOW(),?,'saved',NULL,NOW())",check.replace("\"oaFormItemId\":1","\"oaFormItemId\":2").replace("PRICE_SOURCE_MISSING","PROFILE_MISSING").replace("\"PRICE\"","\"PROFILE\""));
      jdbc.update("INSERT INTO lp_quote_costing_workspace(id,oa_form_item_id,period_month,business_unit_type,last_checked_at,technical_check_json,technical_check_fingerprint,current_bom_build_batch_id,updated_at) VALUES(1,1,'2026-11','COMMERCIAL',NOW(),?,'saved',NULL,NOW())",check);
      var state=new TechnicalDataPendingState(jdbc,new ObjectMapper().findAndRegisterModules());
      assertThat(state.changed(new TechnicalDataPendingState.Change(Set.of("PRICE"),"COMMERCIAL",Set.of("OTHER"),Set.of()))).isZero();
      assertThat(state.changed(new TechnicalDataPendingState.Change(Set.of("NET_LOSS"),"COMMERCIAL",Set.of(),Set.of()))).isZero();
      assertThat(state.changed(new TechnicalDataPendingState.Change(Set.of("PRICE"),"COMMERCIAL",Set.of("P1"),Set.of()))).isEqualTo(1);
      var work=state.waiting(16);
      assertThat(work).as(jdbc.queryForList("SELECT technical_check_json FROM lp_quote_costing_workspace").toString()).hasSize(1);
      assertThat(state.complete(work.getFirst(),work.getFirst().original())).isTrue();
      assertThat(state.waiting(16)).isEmpty();
      for(int pass=0;pass<5;pass++) {
        assertThat(state.changed(new TechnicalDataPendingState.Change(Set.of("PRICE"),"COMMERCIAL",Set.of("P1"),Set.of()))).isEqualTo(1);
        var next=state.waiting(100);assertThat(next).hasSize(1);
        assertThat(state.complete(next.getFirst(),next.getFirst().original())).isTrue();
      }
      var priceChange=new TechnicalDataPendingState.Change(Set.of("PRICE"),"COMMERCIAL",Set.of("P1"),Set.of());
      state.changed(priceChange);var stale=state.waiting(16).getFirst();
      state.changed(priceChange);var latest=state.waiting(16).getFirst();
      assertThat(state.complete(stale,stale.original())).isFalse();
      state.retry(stale);assertThat(state.waiting(16)).hasSize(1);
      state.retry(latest);assertThat(state.waiting(16)).isEmpty();
      state.changed(priceChange);assertThat(state.waiting(16)).hasSize(1);
      assertThat(state.changed(new TechnicalDataPendingState.Change(Set.of("PRICE"),"HOUSEHOLD",Set.of("P1"),Set.of()))).isZero();
      var mapper=new ObjectMapper().findAndRegisterModules();
      var store=new TechnicalDataSourceCheckStore(jdbc,new com.sanhua.marketingcost.integration.oa.OaMessageCodec(mapper),mapper);
      var beforeSave=state.waiting(16).getFirst();
      store.save(1L,beforeSave.original(),java.util.Map.of("retained","yes"));
      assertThat(state.waiting(16)).singleElement().extracting(TechnicalDataPendingState.Work::token).isEqualTo(beforeSave.token());
      assertThat(state.complete(beforeSave,beforeSave.original())).isTrue();
      jdbc.update("UPDATE lp_quote_costing_workspace SET current_bom_build_batch_id='B1' WHERE id=1");
      jdbc.update("INSERT INTO lp_quote_effective_bom_node VALUES('B1','CHILD')");
      assertThat(state.changed(new TechnicalDataPendingState.Change(Set.of("PRICE"),"COMMERCIAL",Set.of("CHILD"),Set.of()))).isEqualTo(1);
      assertThat(state.waiting(16)).hasSize(1);
      jdbc.update("INSERT INTO lp_quote_tech_task VALUES(1,'2026-11',1)");
      assertThat(state.changed(priceChange)).isZero();assertThat(state.waiting(16)).isEmpty();
      assertThat(state.complete(work.getFirst(),work.getFirst().original())).isFalse();
    }
  }
}
