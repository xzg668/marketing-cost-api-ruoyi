package com.sanhua.marketingcost.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CostRunContextContractTest {

  @Test
  void quoteFactoryKeepsExistingSemantics() {
    CostRunContext context =
        CostRunContext.quote(
            "OA-001",
            7L,
            "P-001",
            "箱装",
            "客户A",
            "COMMERCIAL",
            "2026-05",
            LocalDateTime.of(2026, 5, 1, 9, 30),
            "OA-001:P-001");

    assertThat(context.getScene()).isEqualTo(CostRunContext.SCENE_QUOTE);
    assertThat(context.getOaNo()).isEqualTo("OA-001");
    assertThat(context.getOaFormItemId()).isEqualTo(7L);
    assertThat(context.getProductCode()).isEqualTo("P-001");
    assertThat(context.getPackageMethod()).isEqualTo("箱装");
    assertThat(context.getCustomerName()).isEqualTo("客户A");
    assertThat(context.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(context.getPricingMonth()).isEqualTo("2026-05");
    assertThat(context.getPriceAsOfTime()).isEqualTo(LocalDateTime.of(2026, 5, 1, 9, 30));
    assertThat(context.getCalcObjectKey()).isEqualTo("OA-001:P-001");
  }

  @Test
  void monthlyRepriceFactoryKeepsExistingSemantics() {
    CostRunContext context =
        CostRunContext.monthlyReprice(
            "2026-05",
            88L,
            "MRP-001",
            "COMMERCIAL",
            LocalDateTime.of(2026, 5, 1, 9, 30),
            CostRunContext.BOM_SOURCE_POLICY_HISTORICAL_OA_BOM,
            "OA-001",
            7L,
            "P-001",
            "箱装",
            "客户A",
            "MRP-001:OA-001:P-001");

    assertThat(context.getScene()).isEqualTo(CostRunContext.SCENE_MONTHLY_REPRICE);
    assertThat(context.getPricingMonth()).isEqualTo("2026-05");
    assertThat(context.getAdjustBatchId()).isEqualTo(88L);
    assertThat(context.getRepriceNo()).isEqualTo("MRP-001");
    assertThat(context.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(context.getPriceAsOfTime()).isEqualTo(LocalDateTime.of(2026, 5, 1, 9, 30));
    assertThat(context.getBomSourcePolicy())
        .isEqualTo(CostRunContext.BOM_SOURCE_POLICY_HISTORICAL_OA_BOM);
    assertThat(context.getOaNo()).isEqualTo("OA-001");
    assertThat(context.getOaFormItemId()).isEqualTo(7L);
    assertThat(context.getProductCode()).isEqualTo("P-001");
    assertThat(context.getPackageMethod()).isEqualTo("箱装");
    assertThat(context.getCustomerName()).isEqualTo("客户A");
    assertThat(context.getCalcObjectKey()).isEqualTo("MRP-001:OA-001:P-001");
  }

  @Test
  void progressCallbackRemainsTransientAndIsNotSerialized() throws Exception {
    CostRunContext context =
        CostRunContext.quote("OA-001", 7L, "P-001", "箱装", "客户A", "COMMERCIAL", "2026-05", "OBJ-1");
    int progressModifiers = CostRunContext.class.getDeclaredField("progress").getModifiers();

    String json = new ObjectMapper().writeValueAsString(context);

    assertThat(Modifier.isTransient(progressModifiers)).isTrue();
    assertThat(json).doesNotContain("progress");
  }
}
