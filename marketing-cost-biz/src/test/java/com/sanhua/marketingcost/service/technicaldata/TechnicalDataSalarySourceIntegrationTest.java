package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.costing.CmsCostCompositions;
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
        VALUES(1,1,'2026-08',?,?,'COMMERCIAL')
        """, product, product);
    assertThat(query.read(product, 2026, "COMMERCIAL").confirmedBatchId()).isNull();
  }

  @Test void rawIndirectSalaryIsNotAnAbsenceEvenAfterBatchSucceeded() {
    dictionary();
    publish("COMMERCIAL", 2026, "SUCCESS");
    jdbc.update("""
        INSERT INTO cms_product_subject_cost_raw(import_batch_id,row_no,period,parent_code,source_row_id,second_subject_code,business_unit_type)
        VALUES(1,1,'2026-08',?,?,'TW05-INDIRECT','COMMERCIAL')
        """, product, product);
    assertThat(query.read(product, 2026, "COMMERCIAL").confirmedBatchId()).isNull();
  }

  @Test void confirmedAbsenceLooksUpTheExactModelAndUsesTheOtherCodesEffectiveSalary() {
    dictionary();
    publish("COMMERCIAL", 2026, "SUCCESS");
    material(product, product + "-MODEL", "COMMERCIAL", true);
    String reference = product + "-REF";
    material(reference, product + "-MODEL", "COMMERCIAL", true);
    material(product + "-NEAR", product + "-MODELA", "COMMERCIAL", true);
    material(product + "-OTHER", product + "-MODEL", "PLATE", true);
    material(product + "-OFF", product + "-MODEL", "COMMERCIAL", false);
    for (String code : List.of(reference, product + "-NEAR", product + "-OTHER", product + "-OFF")) {
      salary(code, "DIRECT", "4.050877");
      salary(code, "INDIRECT", "0.099600");
    }

    var result = query.read(product, 2026, "COMMERCIAL", "COMMERCIAL");
    assertThat(result.rows()).hasSize(2)
        .extracting(CmsCostSourceEffective::getParentCode).containsOnly(reference);
    var fact = publicSources.checkDataSources(context(), Set.of(TechnicalDataModuleType.SALARY)).getFirst();
    assertThat(fact.availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE);
    assertThat(fact.sourceReference()).contains(reference, product + "-MODEL");
    assertThat(fact.reason()).contains("同型号", reference);
    var prepared = manufacturing.readBatch(List.of(
        new CostRunManufacturingSources.Demand(2026, "COMMERCIAL", "2026-08",
            Set.of(product, reference), true, false, "COMMERCIAL"),
        new CostRunManufacturingSources.Demand(2026, "COMMERCIAL", "2026-08",
            Set.of(product), true, false, "PLATE"))).getFirst().salary();
    assertThat(prepared).hasSize(4);
    var fallbackRows = prepared.stream().filter(row -> row.getSalaryModelMatch() != null).toList();
    assertThat(fallbackRows).hasSize(2).allSatisfy(row -> {
      assertThat(row.getSalaryModelMatch().productCode()).isEqualTo(product);
      assertThat(row.getParentCode()).isEqualTo(reference);
    });
    var evidence = CmsCostCompositions.labor(fallbackRows);
    assertThat(evidence.sourceAmount()).isEqualTo("4.150477");
    assertThat(evidence.note()).contains("同型号", reference);
    assertThat(evidence.sources()).extracting(com.sanhua.marketingcost.dto.CostAmountComposition.Source::parentCode)
        .containsOnly(reference);
  }

  @Test void anyOriginalEffectiveSalaryWinsIncludingAZeroAndAConfirmedSingleItem() {
    String reference = availableReference();
    salary(product, "DIRECT", "0");
    var original = query.read(product, 2026, "COMMERCIAL", "COMMERCIAL").rows();
    assertThat(original).singleElement().satisfies(row -> {
      assertThat(row.getParentCode()).isEqualTo(product);
      assertThat(row.getAmountYuan()).isZero();
      assertThat(row.getSalaryModelMatch()).isNull();
    });
    assertThat(publicSources.checkDataSources(context(), Set.of(TechnicalDataModuleType.SALARY)).getFirst().reason())
        .contains("已有单项").doesNotContain(reference);
  }

  @Test void originalRawSalaryAndUnconfirmedPublicationBothPreventFallback() {
    availableReference();
    jdbc.update("""
        INSERT INTO cms_workshop_labor_raw(import_batch_id,row_no,period,parent_code,source_row_id,business_unit_type)
        VALUES(1,1,'2026-08',?,?,'COMMERCIAL')
        """, product, product);
    assertThat(query.read(product, 2026, "COMMERCIAL", "COMMERCIAL").rows()).isEmpty();
    assertThat(publicSources.checkDataSources(context(), Set.of(TechnicalDataModuleType.SALARY)).getFirst().reasonCode())
        .isEqualTo("SALARY_SOURCE_UNCONFIRMED");
    jdbc.update("DELETE FROM cms_workshop_labor_raw WHERE parent_code=?", product);
    publish("COMMERCIAL", 2026, "RUNNING");
    assertThat(query.read(product, 2026, "COMMERCIAL", "COMMERCIAL").rows()).isEmpty();
  }

  @Test void twoUsableSameModelCodesRemainMissingInsteadOfMixingOrChoosingArbitrarily() {
    availableReference();
    String other = product + "-OTHER";
    material(other, product + "-MODEL", "COMMERCIAL", true);
    salary(other, "DIRECT", "9");
    salary(other, "INDIRECT", "1");
    assertThat(query.read(product, 2026, "COMMERCIAL", "COMMERCIAL").rows()).isEmpty();
  }

  private String availableReference() {
    dictionary();
    publish("COMMERCIAL", 2026, "SUCCESS");
    material(product, product + "-MODEL", "COMMERCIAL", true);
    String reference = product + "-REF";
    material(reference, product + "-MODEL", "COMMERCIAL", true);
    salary(reference, "DIRECT", "4.050877");
    salary(reference, "INDIRECT", "0.099600");
    return reference;
  }

  private QuoteBomReadContext context() {
    return new QuoteBomReadContext(1L, 2L, "OA-1", "2026-08", "COMMERCIAL", product,
        null, null, null, "210", "COMMERCIAL", LocalDate.of(2026, 8, 1),
        LocalDateTime.of(2026, 8, 1, 0, 0));
  }

  private void material(String code, String model, String organization, boolean active) {
    jdbc.update("""
        INSERT INTO lp_material_master_raw(import_batch_id,source_type,material_code,material_model,organization_code,active_flag)
        VALUES('salary-model-test','EXCEL',?,?,?,?)
        """, code, model, organization, active ? 1 : 0);
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
