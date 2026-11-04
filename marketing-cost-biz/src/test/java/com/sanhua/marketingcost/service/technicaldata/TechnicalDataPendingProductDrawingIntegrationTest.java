package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
class TechnicalDataPendingProductDrawingIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired TechnicalDataPendingProductQuery pending;
  @Autowired QuoteBomPreparationRecordMapper preparations;
  @Autowired QuoteBomSupplementVersionMapper versions;
  @Autowired com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceNodeRepository nodes;
  private String oaNo, savedJson;
  private Long itemId, preparationId, sourceId;

  @BeforeEach void fixture() throws Exception {
    oaNo="DRAWING-PRESENCE-"+UUID.randomUUID().toString().substring(0,8);
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,'COMMERCIAL')",oaNo);
    Long formId=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,oaNo);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type) VALUES(?,?,'COMMERCIAL')",formId,oaNo);
    itemId=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,formId);
    var prep=new QuoteBomPreparationRecord();
    prep.setOaFormId(formId);prep.setOaFormItemId(itemId);prep.setOaNo(oaNo);prep.setQuoteProductCode(oaNo);
    prep.setCostPeriodMonth("2026-10");prep.setPriceOrgCode("210");prep.setMaterialOrganizationCode("COMMERCIAL");
    prep.setPreparationStatus("NEED_TECH");prep.setActiveFlag(1);prep.setElectronicWorkflowVersion(0);
    prep.setElectronicWorkflowStage("VALIDATION_FAILED");preparations.insert(prep);preparationId=prep.getId();
    var source=new QuoteBomSupplementVersion();
    source.setPreparationId(preparationId);source.setTaskNo(oaNo);source.setOaNo(oaNo);source.setOaFormItemId(itemId);
    source.setQuoteProductCode(oaNo);source.setProductType("NON_BARE");source.setSupplementScope("NON_BARE_FULL_BOM");
    source.setBomSource("ELECTRONIC_DRAWING_PDF");source.setElectronicDrawingNo(oaNo+"-MX");
    source.setMaterialOrgCode("COMMERCIAL");source.setVersionNo(1);source.setVersionStatus("DRAFT");
    source.setActiveFlag(1);source.setPeriodMonth("2026-10");versions.insert(source);sourceId=source.getId();
    jdbc.update("UPDATE lp_quote_bom_preparation_record SET electronic_source_version_id=? WHERE id=?",sourceId,preparationId);
    var node=new ElectronicDrawingSourceNode();node.setSourceRowNo(1);node.setSourceSequence("G1:R1");
    node.setDrawingCode("T-JG-0478");node.setSourceName("接管");node.setQty(BigDecimal.ONE);
    node.setMatchStatus("AUTO_MATCHED");node.setResolvedMaterialCode("9990000051108");
    node.setResolvedBy("test");node.setResolvedAt(LocalDateTime.now());nodes.insertAll(sourceId,List.of(node));
    var time=LocalDateTime.of(2026,11,2,22,43);
    var check=new TechnicalDataSourceCheckResponse(itemId,"2026-10","saved-fingerprint",time,List.of(
        new TechnicalDataModuleRequirement("DRAWING_BOM",true,"DRAWING_SOURCE_PENDING","已有图库来源，但组树未完成",
            TechnicalDataAvailability.MISSING,"{\"sourceVersionId\":"+sourceId+"}",time),
        new TechnicalDataModuleRequirement("MANUFACTURING",true,"MANUFACTURING_RAW_MISSING","接管缺少原材料关系",
            TechnicalDataAvailability.MISSING,"unchanged-raw-gap",time)),null,List.of());
    savedJson=json.writeValueAsString(Map.of("check",check));
    jdbc.update("""
        INSERT INTO lp_quote_costing_workspace(oa_no,oa_form_item_id,product_code,period_month,business_unit_type,
          workspace_status,gap_count,last_checked_at,technical_check_json,technical_check_fingerprint)
        VALUES(?,?,?,'2026-10','COMMERCIAL','WAIT_BOM',2,NOW(),?,'saved-fingerprint')
        """,oaNo,itemId,oaNo,savedJson);
    savedJson=jdbc.queryForObject("SELECT technical_check_json FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",String.class,itemId);
  }

  @Test void listUsesReceivedDetailsWhileKeepingSavedChecksAndManufacturingGapsUntouched() {
    for(int i=0;i<2;i++) {
      var rows=pending.page("ALL",null,"2026-10",null,oaNo,0,20);
      assertThat(rows).hasSize(1);
      var modules=rows.getFirst().product().modules();
      assertThat(modules).filteredOn(m -> m.moduleType().equals("DRAWING_BOM")).allSatisfy(m -> {
        assertThat(m.required()).isFalse();assertThat(m.moduleStatus()).isEqualTo("NOT_REQUIRED");
        assertThat(m.requirementReasonCode()).isEqualTo("DRAWING_SOURCE_AVAILABLE");
        assertThat(m.sourceAvailability()).isEqualTo("AVAILABLE");
      });
      assertThat(modules).filteredOn(m -> m.moduleType().equals("MANUFACTURING")).allSatisfy(m -> {
        assertThat(m.required()).isTrue();assertThat(m.moduleStatus()).isEqualTo("PENDING");
        assertThat(m.sourceReference()).isEqualTo("unchanged-raw-gap");
      });
      assertThat(rows.getFirst().sourceCheck().fingerprint()).isEqualTo("saved-fingerprint");
    }
    assertThat(jdbc.queryForObject("SELECT technical_check_json FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",
        String.class,itemId)).isEqualTo(savedJson);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_task WHERE oa_form_item_id=?",Integer.class,itemId)).isZero();
  }

  @Test void historicalOrOtherMonthSourceDoesNotCountAsCurrentDrawingDetails() {
    jdbc.update("UPDATE lp_quote_bom_supplement_version SET period_month='2026-09' WHERE id=?",sourceId);
    assertMissing();
    jdbc.update("UPDATE lp_quote_bom_supplement_version SET period_month='2026-10',active_flag=0 WHERE id=?",sourceId);
    assertMissing();
    jdbc.update("UPDATE lp_quote_bom_supplement_version SET active_flag=1 WHERE id=?",sourceId);
    jdbc.update("UPDATE lp_quote_bom_preparation_record SET electronic_source_version_id=NULL WHERE id=?",preparationId);
    assertMissing();
  }

  @Test void aResolvedDrawingOnlyGapLeavesThePendingListWithoutRewritingHistory() {
    jdbc.update("UPDATE lp_quote_costing_workspace SET technical_check_json=JSON_REMOVE(technical_check_json,'$.check.modules[1]') WHERE oa_form_item_id=?",itemId);
    assertThat(pending.count("ALL",null,"2026-10",null,oaNo)).isZero();
    assertThat(pending.page("ALL",null,"2026-10",null,oaNo,0,20)).isEmpty();
    assertThat(pending.hasPendingItem(oaNo,itemId)).isFalse();
    jdbc.update("DELETE FROM lp_electronic_drawing_source_node WHERE supplement_version_id=?",sourceId);
    assertThat(pending.count("ALL",null,"2026-10",null,oaNo)).isEqualTo(1);
    assertMissing();
  }

  private void assertMissing() {
    assertThat(pending.page("ALL",null,"2026-10",null,oaNo,0,20).getFirst().product().modules())
        .filteredOn(m -> m.moduleType().equals("DRAWING_BOM")).allSatisfy(m -> {
          assertThat(m.required()).isTrue();assertThat(m.sourceAvailability()).isEqualTo("MISSING");
        });
  }
}
