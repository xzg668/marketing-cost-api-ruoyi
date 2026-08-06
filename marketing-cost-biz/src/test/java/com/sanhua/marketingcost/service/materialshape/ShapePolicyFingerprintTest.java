package com.sanhua.marketingcost.service.materialshape;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.MaterialQuoteShapePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShapePolicyFingerprintTest {

  private ShapePolicyFingerprint fingerprint;

  @BeforeEach
  void setUp() {
    fingerprint = new ShapePolicyFingerprint(new ObjectMapper());
  }

  @Test
  @DisplayName("同一规则重复计算得到稳定的 64 位 SHA-256")
  void samePolicyHasStableFingerprint() {
    MaterialQuoteShapePolicy policy =
        MaterialQuoteShapeTestSupport.fixed(
            1L, "COMMERCIAL", "A-100", "PURCHASE", "2026-08", null);

    assertThat(fingerprint.calculate(policy))
        .hasSize(64)
        .isEqualTo(fingerprint.calculate(policy));
  }

  @Test
  @DisplayName("目标形态或生效月份变化会改变规则指纹")
  void behavioralChangeChangesFingerprint() {
    MaterialQuoteShapePolicy purchase =
        MaterialQuoteShapeTestSupport.fixed(
            1L, "COMMERCIAL", "A-100", "PURCHASE", "2026-08", null);
    MaterialQuoteShapePolicy manufacture =
        MaterialQuoteShapeTestSupport.fixed(
            1L, "COMMERCIAL", "A-100", "MANUFACTURE", "2026-08", null);
    MaterialQuoteShapePolicy nextMonth =
        MaterialQuoteShapeTestSupport.fixed(
            1L, "COMMERCIAL", "A-100", "PURCHASE", "2026-09", null);

    assertThat(fingerprint.calculate(purchase))
        .isNotEqualTo(fingerprint.calculate(manufacture))
        .isNotEqualTo(fingerprint.calculate(nextMonth));
  }

  @Test
  @DisplayName("JSON 对象字段顺序不同但语义相同时指纹不变")
  void jsonObjectOrderDoesNotChangeFingerprint() {
    MaterialQuoteShapePolicy first =
        MaterialQuoteShapeTestSupport.supplierRatio(
            2L, "COMMERCIAL", "A-100", "2026-08");
    MaterialQuoteShapePolicy reordered =
        MaterialQuoteShapeTestSupport.supplierRatio(
            2L, "COMMERCIAL", "A-100", "2026-08");
    reordered.setActionConfigJson(
        "{\"excludedDirectChildMaterialCodes\":[\"311034930\"],"
            + "\"externalTargetShape\":\"OUTSOURCE\","
            + "\"internalTargetShape\":\"MANUFACTURE\"}");

    assertThat(fingerprint.calculate(first))
        .isEqualTo(fingerprint.calculate(reordered));
  }

  @Test
  @DisplayName("名称、规格和备注等展示信息变化不制造新业务指纹")
  void descriptiveMetadataDoesNotChangeFingerprint() {
    MaterialQuoteShapePolicy first =
        MaterialQuoteShapeTestSupport.fixed(
            3L, "COMMERCIAL", "A-100", "PURCHASE", "2026-08", null);
    MaterialQuoteShapePolicy renamed =
        MaterialQuoteShapeTestSupport.fixed(
            3L, "COMMERCIAL", "A-100", "PURCHASE", "2026-08", null);
    first.setMaterialName("旧名称");
    first.setRemark("旧说明");
    renamed.setMaterialName("新名称");
    renamed.setMaterialSpec("新规格");
    renamed.setRemark("新说明");

    assertThat(fingerprint.calculate(first))
        .isEqualTo(fingerprint.calculate(renamed));
  }
}
