package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.costing.CostRunManufacturingSources;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
class TechnicalDataSalarySourceIntegrationTest extends BomMapperTestBase {
  @Autowired TechnicalDataSalarySourceQuery query;
  @Autowired TechnicalDataPublicSourceCheck publicSources;
  @Autowired CostRunManufacturingSources manufacturing;
  @Autowired JdbcTemplate jdbc;
  private final String product = "TW05-" + UUID.randomUUID();

  @Test void successfulPublishWithoutSubjectDictionaryDoesNotProveIndirectSalaryAbsent() {
    publish("COMMERCIAL", 2026, "SUCCESS");
    assertThat(query.read(product, 2026, "COMMERCIAL").confirmedBatchId()).isNull();
  }

  @Test void successAndKnownSubjectWithNoRawRowsProveAbsenceOnlyInSameYearAndBusinessUnit() {
    dictionary();
    publish("HOUSEHOLD", 2026, "SUCCESS");
    publish("COMMERCIAL", 2025, "SUCCESS");
    assertThat(query.read(product, 2026, "COMMERCIAL").confirmedBatchId()).isNull();
    publish("COMMERCIAL", 2026, "SUCCESS");
    assertThat(query.read(product, 2026, "COMMERCIAL").confirmedBatchId()).isNotNull();
    publish("COMMERCIAL", 2026, "RUNNING");
    assertThat(query.read(product, 2026, "COMMERCIAL").confirmedBatchId()).isNull();
  }

  @Test void blockedOrUnpublishedRawSalaryCannotBeTreatedAsAbsent() {
    dictionary();
    publish("COMMERCIAL", 2026, "SUCCESS");
    assertThat(query.read(product, 2026, "COMMERCIAL").confirmedBatchId()).isNotNull();
    jdbc.update("""
        INSERT INTO cms_workshop_labor_raw(import_batch_id,row_no,period,parent_code,source_row_id,business_unit_type)
        VALUES(1,1,'2025-11',?,?,'COMMERCIAL')
        """, product, product + "-nov");
    assertThat(query.read(product, 2026, "COMMERCIAL").confirmedBatchId()).isNotNull();
    jdbc.update("""
        INSERT INTO cms_workshop_labor_raw(import_batch_id,row_no,period,parent_code,source_row_id,business_unit_type)
        VALUES(1,1,'2025-12',?,?,'COMMERCIAL')
        """, product, product);
    assertThat(query.read(product, 2026, "COMMERCIAL").confirmedBatchId()).isNull();
  }

  @Test void rawIndirectSalaryIsNotAnAbsenceEvenAfterBatchSucceeded() {
    dictionary();
    publish("COMMERCIAL", 2026, "SUCCESS");
    jdbc.update("""
        INSERT INTO cms_product_subject_cost_raw(import_batch_id,row_no,period,parent_code,source_row_id,second_subject_code,business_unit_type)
        VALUES(1,1,'2025-12',?,?,'TW05-INDIRECT','COMMERCIAL')
        """, product, product);
    assertThat(query.read(product, 2026, "COMMERCIAL").confirmedBatchId()).isNull();
  }

  @Test void confirmedAbsenceStaysMissingWhenTheBareAndSameSpecificationCodesHaveCmsCosts() {
    String bare = availableReference();
    String sameSpecification = product + "-SPEC";
    material(sameSpecification, product + "-OTHER-MODEL");
    salary(sameSpecification, "DIRECT", "9");
    salary(sameSpecification, "INDIRECT", "1");
    jdbc.update("UPDATE lp_material_master_raw SET bare_code=? WHERE material_code=?", bare, product);
    for (String code : List.of(bare, sameSpecification)) {
      jdbc.update("""
          INSERT INTO cms_cost_source_effective(cost_year,source_type,parent_code,period,subject_code,subject_name,
            source_table,source_row_ids,amount_yuan,business_unit_type)
          VALUES(2026,'AUX_SUBJECT',?,'2026-01','0201','工艺辅料','cms_product_subject_cost_raw','1',3,'COMMERCIAL')
          """, code);
    }

    var result = query.read(product, 2026, "COMMERCIAL");
    assertThat(result.confirmedBatchId()).isNotNull();
    assertThat(result.rows()).isEmpty();
    var fact = publicSources.checkDataSources(context(), Set.of(TechnicalDataModuleType.SALARY)).getFirst();
    assertThat(fact.availability()).isEqualTo(TechnicalDataAvailability.MISSING);
    assertThat(fact.reasonCode()).isEqualTo("SALARY_SOURCE_MISSING");

    var prepared = manufacturing.readBatch(List.of(
        new CostRunManufacturingSources.Demand(2026, "COMMERCIAL", "2026-08", Set.of(product), true, true),
        new CostRunManufacturingSources.Demand(2026, "COMMERCIAL", "2026-08", Set.of(bare), true, true)));
    assertThat(prepared.getFirst().salary()).isEmpty();
    assertThat(prepared.getFirst().auxiliary()).isEmpty();
    assertThat(prepared.getLast().salary()).hasSize(2)
        .extracting(CmsCostSourceEffective::getParentCode).containsOnly(bare);
    assertThat(prepared.getLast().auxiliary()).singleElement()
        .satisfies(row -> assertThat(row.getMaterialCode()).isEqualTo(bare));
  }

  @Test void anyOriginalEffectiveSalaryWinsIncludingAZeroAndAConfirmedSingleItem() {
    String reference = availableReference();
    salary(product, "DIRECT", "0");
    var original = query.read(product, 2026, "COMMERCIAL").rows();
    assertThat(original).singleElement().satisfies(row -> {
      assertThat(row.getParentCode()).isEqualTo(product);
      assertThat(row.getAmountYuan()).isZero();
    });
    assertThat(publicSources.checkDataSources(context(), Set.of(TechnicalDataModuleType.SALARY)).getFirst().reason())
        .contains("已有单项").doesNotContain(reference);
  }

  private String availableReference() {
    dictionary();
    publish("COMMERCIAL", 2026, "SUCCESS");
    material(product, product + "-MODEL");
    String reference = product + "-REF";
    material(reference, product + "-MODEL");
    salary(reference, "DIRECT", "4.050877");
    salary(reference, "INDIRECT", "0.099600");
    return reference;
  }

  private QuoteBomReadContext context() {
    return new QuoteBomReadContext(1L, 2L, "OA-1", "2026-08", "COMMERCIAL", product,
        null, null, null, "210", "COMMERCIAL", LocalDate.of(2026, 8, 1),
        LocalDateTime.of(2026, 8, 1, 0, 0));
  }

  private void material(String code, String model) {
    jdbc.update("""
        INSERT INTO lp_material_master_raw(import_batch_id,source_type,material_code,material_model,
          material_spec,drawing_no,organization_code,active_flag)
        VALUES('salary-finished-code-test','EXCEL',?,?,?,?,'COMMERCIAL',1)
        """, code, model, product + "-SPEC", product + "-DRAWING");
  }

  private void salary(String code, String type, String amount) {
    boolean direct = "DIRECT".equals(type);
    jdbc.update("""
        INSERT INTO cms_cost_source_effective(cost_year,source_type,parent_code,period,subject_code,subject_name,
          source_table,source_row_ids,amount_yuan,business_unit_type)
        VALUES(2026,?,?,'2026-01',?,?,?,'1',?,'COMMERCIAL')
        """, "SALARY_" + type, code, direct ? "0301" : "TW05-INDIRECT",
        direct ? "直接人工工资" : "辅助人员工资",
        direct ? "cms_workshop_labor_raw" : "cms_product_subject_cost_raw", new java.math.BigDecimal(amount));
  }

  private void dictionary() {
    jdbc.update("""
        INSERT INTO cms_subject_setting_raw(import_batch_id,row_no,first_subject_code,first_subject_name,
          second_subject_code,second_subject_name,business_unit_type)
        VALUES(1,1,'TW05-SALARY','工资','TW05-INDIRECT','辅助人员工资','COMMERCIAL')
        """);
  }

  private void publish(String businessUnit, int year, String status) {
    jdbc.update("INSERT INTO cms_sync_publish_signal(batch_no,cost_year,business_unit_type,status) VALUES(?,?,?,?)",
        UUID.randomUUID().toString(), year, businessUnit, status);
  }
}
