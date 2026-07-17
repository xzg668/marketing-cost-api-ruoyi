package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PricePrepareSettlementKeyGeneratorTest {

  @Test
  @DisplayName("同一BOM行改变数量或价格场景输入时稳定键不变")
  void sameSettlementInputHasSameKeyRegardlessOfAmounts() {
    PricePreparePlanItem first = planItem(11L, "/TOP/PARENT/MAT/", "MAT-CU", "NORMAL");
    first.getBomRow().setQtyPerTop(new BigDecimal("1.25"));
    PricePreparePlanItem second = planItem(11L, "/TOP/PARENT/MAT/", "MAT-CU", "NORMAL");
    second.getBomRow().setQtyPerTop(new BigDecimal("999.99"));

    String firstKey = PricePrepareSettlementKeyGenerator.settlementKey(101L, "TOP", first);
    String secondKey = PricePrepareSettlementKeyGenerator.settlementKey(101L, "TOP", second);

    assertThat(firstKey).isEqualTo(secondKey).startsWith("SET:v1:").hasSize(71);
  }

  @Test
  @DisplayName("同料号位于不同BOM路径时稳定键不同")
  void sameMaterialAtDifferentBomPathsHasDifferentKeys() {
    PricePreparePlanItem left = planItem(11L, "/TOP/PARENT-A/MAT/", "MAT-CU", "NORMAL");
    PricePreparePlanItem right = planItem(12L, "/TOP/PARENT-B/MAT/", "MAT-CU", "NORMAL");

    assertThat(PricePrepareSettlementKeyGenerator.settlementKey(101L, "TOP", left))
        .isNotEqualTo(PricePrepareSettlementKeyGenerator.settlementKey(101L, "TOP", right));
  }

  @Test
  @DisplayName("bomRowId为空时优先使用BOM路径生成非空稳定键")
  void pathKeepsKeyStableWhenBomRowIdIsNull() {
    PricePreparePlanItem first = planItem(null, "/TOP/MAKE-1/", "MAKE-1", "MAKE_PART");
    PricePreparePlanItem second = planItem(null, "/TOP/MAKE-1/", "MAKE-1", "MAKE_PART");

    assertThat(PricePrepareSettlementKeyGenerator.settlementKey(101L, "TOP", first))
        .isEqualTo(PricePrepareSettlementKeyGenerator.settlementKey(101L, "TOP", second))
        .startsWith("SET:v1:");
  }

  @Test
  @DisplayName("路径和bomRowId都为空时仍按父项层级生成确定性键")
  void fallbackPositionIsDeterministic() {
    PricePreparePlanItem item = planItem(null, null, "MAT-FALLBACK", "NORMAL");
    item.getBomRow().setParentCode("PARENT-X");
    item.getBomRow().setLevel(3);

    assertThat(PricePrepareSettlementKeyGenerator.settlementKey(101L, "TOP", item))
        .isEqualTo(PricePrepareSettlementKeyGenerator.settlementKey(101L, "TOP", item))
        .isNotBlank();
  }

  @Test
  @DisplayName("制造件父结算行和子原材料解释行使用不同类型稳定键")
  void makePartParentAndChildHaveIndependentKeys() {
    PricePreparePlanItem parent = planItem(null, "/TOP/MAKE-1/", "MAKE-1", "MAKE_PART");
    String parentKey = PricePrepareSettlementKeyGenerator.settlementKey(101L, "TOP", parent);
    String copperChild = PricePrepareSettlementKeyGenerator.componentKey(
        parentKey, "MAKE-1", "CU-TUBE", "/TOP/MAKE-1/CU-TUBE/");
    String solderChild = PricePrepareSettlementKeyGenerator.componentKey(
        parentKey, "MAKE-1", "SOLDER", "/TOP/MAKE-1/SOLDER/");

    assertThat(parentKey).startsWith("SET:v1:");
    assertThat(copperChild).startsWith("CMP:v1:").isNotEqualTo(parentKey);
    assertThat(solderChild).isNotEqualTo(copperChild);
  }

  @Test
  @DisplayName("稳定键拒绝缺少顶级产品或料号的新增明细")
  void requiredIdentityCannotBeBlank() {
    PricePreparePlanItem item = planItem(1L, "/TOP/UNKNOWN/", null, "NORMAL");

    assertThatThrownBy(
        () -> PricePrepareSettlementKeyGenerator.settlementKey(101L, "TOP", item))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("materialCode");
  }

  private PricePreparePlanItem planItem(
      Long bomRowId, String path, String materialCode, String itemType) {
    BomCostingRow row = new BomCostingRow();
    row.setId(bomRowId);
    row.setOaFormItemId(101L);
    row.setTopProductCode("TOP");
    row.setPath(path);
    row.setMaterialCode(materialCode);
    row.setSettlementRowType("DEFAULT_LEAF");
    PricePreparePlanItem item = new PricePreparePlanItem();
    item.setBomRow(row);
    item.setBomRowId(bomRowId);
    item.setTopProductCode("TOP");
    item.setMaterialCode(materialCode);
    item.setItemType(itemType);
    return item;
  }
}
