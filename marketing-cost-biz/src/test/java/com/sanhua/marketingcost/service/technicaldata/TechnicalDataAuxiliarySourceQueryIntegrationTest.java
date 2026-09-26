package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
class TechnicalDataAuxiliarySourceQueryIntegrationTest extends BomMapperTestBase {
  @Autowired private TechnicalDataAuxiliarySourceQuery query;
  @Autowired private CmsCostSourceEffectiveMapper cms;
  @Autowired private MaterialMasterRawMapper materials;
  @Autowired private com.sanhua.marketingcost.mapper.AuxCostItemMapper costingAux;
  private final QuoteTechTask task = new QuoteTechTask();
  private final QuoteTechProduct product = new QuoteTechProduct();
  private String key;

  @BeforeEach void setup() {
    key = "TW11-" + UUID.randomUUID().toString().substring(0, 8);
    task.setBusinessUnitType("COMMERCIAL"); task.setApplicableOrgCode("210");
    product.setAccountingMonth("2026-09"); product.setMaterialNo(key + "-TARGET");
    for (String suffix : new String[]{"A", "B"}) {
      var material = new MaterialMasterRaw(); material.setMaterialCode(key + "-" + suffix);
      material.setMaterialName("参考成品" + suffix); material.setMaterialModel(key + "-MODEL-" + suffix);
      material.setOrganizationCode("COMMERCIAL"); material.setActiveFlag(1); material.setSourceType("EXCEL");
      material.setImportBatchId(key); materials.insert(material);
    }
  }

  @Test void selectedProductUsesOnlyCurrentYearUnitAndNonfutureAuxSubjectsWithoutScalingAmounts() {
    source("A", "OK1", "清洗辅料", "1.25", 2026, "2026-08", "COMMERCIAL", "AUX_SUBJECT");
    source("A", "OK2", "工具辅料", "0.5", 2026, "2026-09", "COMMERCIAL", "AUX_SUBJECT");
    source("A", "PKG", " 包装辅料 ", "999", 2026, "2026-09", "COMMERCIAL", "AUX_SUBJECT");
    source("A", "FUTURE", "未来辅料", "999", 2026, "2026-10", "COMMERCIAL", "AUX_SUBJECT");
    source("A", "OLD", "往年辅料", "999", 2025, "2025-09", "COMMERCIAL", "AUX_SUBJECT");
    source("A", "FOREIGN", "其他事业部", "999", 2026, "2026-09", "PLATE", "AUX_SUBJECT");
    source("A", "SALARY", "直接人工", "999", 2026, "2026-09", "COMMERCIAL", "SALARY_DIRECT");
    source("B", "OTHER", "另一成品", "999", 2026, "2026-09", "COMMERCIAL", "AUX_SUBJECT");
    var candidates = query.search(task, product, key + "-MODEL-A");
    assertThat(candidates).hasSize(1);
    var source = candidates.getFirst(); assertThat(source.materialNo()).isEqualTo(key + "-A");
    assertThat(source.items()).hasSize(2).extracting(row -> row.subjectCode()).containsExactly("OK1", "OK2");
    assertThat(source.items().get(0).sourceAmount()).isEqualByComparingTo("1.25");
    assertThat(source.items().get(1).sourceAmount()).isEqualByComparingTo("0.5");
    assertThat(query.require(task, product, source.materialNo(), source.fingerprint())).isEqualTo(source);
    assertThat(costingAux.selectEffectiveAuxCostItems(2026, java.util.List.of(key + "-A"), "COMMERCIAL", "2026-09"))
        .extracting(row -> row.getAuxSubjectCode()).containsExactly("OK1", "OK2");
    assertThat(costingAux.selectEffectiveAuxCostItems(2026, java.util.List.of(key + "-A"), "COMMERCIAL", "2026-08"))
        .extracting(row -> row.getAuxSubjectCode()).containsExactly("OK1");
    assertThat(query.search(task, product, key + "-B").getFirst().items()).hasSize(1);
  }

  @Test void sourceChangeEmptyScopeAndMissingSubjectCannotBecomeValidReference() {
    var sourceRow = source("A", "CLEAN", "清洗辅料", "0", 2026, "2026-08", "COMMERCIAL", "AUX_SUBJECT");
    var selected = query.search(task, product, key + "-A").getFirst();
    assertThat(query.require(task, product, selected.materialNo(), selected.fingerprint()).items().getFirst().sourceAmount()).isZero();
    cms.update(null, Wrappers.<CmsCostSourceEffective>lambdaUpdate().eq(CmsCostSourceEffective::getId, sourceRow.getId())
        .set(CmsCostSourceEffective::getAmountYuan, new BigDecimal("12")));
    assertThatThrownBy(() -> query.require(task, product, selected.materialNo(), selected.fingerprint())).hasMessageContaining("已变化");
    product.setAccountingMonth("2026-07");
    assertThatThrownBy(() -> query.require(task, product, selected.materialNo(), selected.fingerprint())).hasMessageContaining("没有可用");
    product.setAccountingMonth("2026-09");
    cms.update(null, Wrappers.<CmsCostSourceEffective>lambdaUpdate().eq(CmsCostSourceEffective::getId, sourceRow.getId())
        .set(CmsCostSourceEffective::getSubjectName, ""));
    var incomplete = query.search(task, product, key + "-A").getFirst();
    assertThatThrownBy(() -> query.require(task, product, incomplete.materialNo(), incomplete.fingerprint())).hasMessageContaining("科目编码或名称");
    task.setBusinessUnitType("PLATE"); task.setApplicableOrgCode("220");
    assertThat(query.search(task, product, key + "-A")).isEmpty();
    assertThatThrownBy(() -> query.require(task, product, selected.materialNo(), selected.fingerprint())).hasMessageContaining("没有可用");
  }

  private CmsCostSourceEffective source(String suffix, String subject, String name, String amount,
      int year, String period, String businessUnit, String type) {
    var row = new CmsCostSourceEffective(); row.setCostYear(year); row.setSourceType(type); row.setParentCode(key + "-" + suffix);
    row.setPeriod(period); row.setSubjectCode(subject); row.setSubjectName(name); row.setAmountYuan(new BigDecimal(amount));
    row.setSourceTable("cms_aux_material_cost"); row.setSourceRowIds("[1]"); row.setDefaultFlag(0);
    row.setConfirmedBy("TW11"); row.setBusinessUnitType(businessUnit); cms.insert(row); return row;
  }
}
