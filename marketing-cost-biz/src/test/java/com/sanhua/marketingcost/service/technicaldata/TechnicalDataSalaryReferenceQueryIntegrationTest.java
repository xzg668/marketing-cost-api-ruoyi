package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
class TechnicalDataSalaryReferenceQueryIntegrationTest extends BomMapperTestBase {
  @Autowired TechnicalDataSalarySourceQuery query;
  @Autowired CmsCostSourceEffectiveMapper cms;
  @Autowired MaterialMasterRawMapper materials;
  private final String key = "TW13-" + UUID.randomUUID().toString().substring(0, 8);
  private final QuoteTechTask task = new QuoteTechTask();
  private final QuoteTechProduct target = new QuoteTechProduct();

  @BeforeEach void setup() {
    task.setApplicableOrgCode("210"); task.setBusinessUnitType("COMMERCIAL");
    target.setMaterialNo(key + "-TARGET"); target.setAccountingMonth("2026-09");
    var material = new MaterialMasterRaw(); material.setMaterialCode(key + "-REF");
    material.setMaterialName("工资参考成品"); material.setMaterialModel(key + "-MODEL");
    material.setOrganizationCode("COMMERCIAL"); material.setActiveFlag(1);
    material.setSourceType("EXCEL"); material.setImportBatchId(key); materials.insert(material);
  }

  @Test void referenceAndCostingUseSelectedAnnualSourcesWithIndependentPeriodsAndUnscaledYuan() {
    source("DIRECT", "4.050877", 2026, "2026-11", "COMMERCIAL");
    source("INDIRECT", "0.099600", 2026, "2026-01", "COMMERCIAL");
    source("DIRECT", "999", 2025, "2025-01", "COMMERCIAL");
    source("DIRECT", "888", 2026, "2026-01", "HOUSEHOLD");
    var selected = query.searchReferences(task, target, key + "-MODEL", "REFERENCE").getFirst();
    assertThat(selected.materialNo()).isEqualTo(key + "-REF");
    assertThat(selected.issues()).isEmpty();
    assertThat(selected.direct().sourcePeriod()).isEqualTo("2026-11");
    assertThat(selected.indirect().sourcePeriod()).isEqualTo("2026-01");
    assertThat(selected.direct().amountYuan().add(selected.indirect().amountYuan())).isEqualByComparingTo("4.150477");
    assertThat(query.requireReference(task, target, selected.materialNo(), selected.fingerprint(), "REFERENCE")).isEqualTo(selected);
    assertThat(cms.selectSalarySources(2026, "COMMERCIAL", List.of(key + "-REF")))
        .extracting(CmsCostSourceEffective::getId).containsExactly(selected.direct().sourceId(), selected.indirect().sourceId());
    assertThat(query.read(target.getMaterialNo(), 2026, "COMMERCIAL").rows()).isEmpty();
  }

  @Test void singleSalaryCannotStandInForBothButUploadCanReferenceOnlyIndirectAndZeroIsValid() {
    source("INDIRECT", "0", 2026, "2026-02", "COMMERCIAL");
    var incomplete = query.searchReferences(task, target, key, "REFERENCE").getFirst();
    assertThat(incomplete.issues()).contains("参考成品缺少直接人工工资");
    assertThatThrownBy(() -> query.requireReference(task, target, incomplete.materialNo(), incomplete.fingerprint(), "REFERENCE"))
        .hasMessageContaining("缺少直接人工工资");
    var indirect = query.searchReferences(task, target, key, "UPLOAD").getFirst();
    assertThat(indirect.issues()).isEmpty();
    assertThat(indirect.direct()).isNull();
    assertThat(indirect.indirect().amountYuan()).isZero();
    assertThat(query.requireReference(task, target, indirect.materialNo(), indirect.fingerprint(), "UPLOAD")).isEqualTo(indirect);
  }

  @Test void changedAmountsInvalidPeriodsAndForeignYearCannotReuseTheSelectedReference() {
    var direct = source("DIRECT", "4", 2026, "2026-01", "COMMERCIAL");
    source("INDIRECT", "0.1", 2026, "2026-02", "COMMERCIAL");
    var chosen = query.searchReferences(task, target, key, "REFERENCE").getFirst();
    direct.setAmountYuan(new BigDecimal("5")); cms.updateById(direct);
    assertThatThrownBy(() -> query.requireReference(task, target, chosen.materialNo(), chosen.fingerprint(), "REFERENCE"))
        .isInstanceOf(TechnicalDataTaskException.class).hasMessageContaining("已变化");
    direct.setPeriod("2025-12"); cms.updateById(direct);
    assertThat(query.searchReferences(task, target, key, "REFERENCE").getFirst().issues())
        .contains("直接人工工资来源期间不属于本核算年度");
    target.setAccountingMonth("2027-01");
    assertThat(query.searchReferences(task, target, key, "REFERENCE")).isEmpty();
    assertThatThrownBy(() -> query.requireReference(task, target, chosen.materialNo(), chosen.fingerprint(), "REFERENCE"))
        .hasMessageContaining("缺少");
  }

  private CmsCostSourceEffective source(String type, String amount, int year, String period, String unit) {
    var row = new CmsCostSourceEffective(); row.setCostYear(year); row.setBusinessUnitType(unit);
    row.setSourceType("SALARY_" + type); row.setParentCode(key + "-REF"); row.setPeriod(period);
    row.setSubjectCode("DIRECT".equals(type) ? "0301" : "0302");
    row.setSubjectName("DIRECT".equals(type) ? "直接人工工资" : "辅助人员工资");
    row.setSourceTable("DIRECT".equals(type) ? "cms_workshop_labor_raw" : "cms_product_subject_cost_raw");
    row.setSourceRowIds("11,12"); row.setAmountYuan(new BigDecimal(amount)); cms.insert(row); return row;
  }
}
