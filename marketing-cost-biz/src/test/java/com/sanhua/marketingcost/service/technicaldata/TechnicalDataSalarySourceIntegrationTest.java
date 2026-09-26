package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
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
