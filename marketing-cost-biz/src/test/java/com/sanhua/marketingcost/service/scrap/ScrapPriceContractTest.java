package com.sanhua.marketingcost.service.scrap;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.entity.ScrapPrice;
import com.sanhua.marketingcost.service.pricing.ScrapPriceIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScrapPriceContractTest {
  @Test
  void blankSuppliersAreOneIdentity() {
    var a = new ScrapPriceIdentity("commercial", "2026-10", " 301990044 ", null, null);
    var b = new ScrapPriceIdentity("COMMERCIAL", "2026-10", "301990044", "  ", "\t");
    assertThat(a).isEqualTo(b);
    assertThat(a.supplierKey()).isEqualTo(ScrapPriceIdentity.hash("COMMON"));
  }

  @Test
  void supplierCodeOwnsIdentityWhileNameOnlyRemainsExact() {
    var a = new ScrapPriceIdentity("COMMERCIAL", "2026-10", "A", "S1", "旧名称");
    var b = new ScrapPriceIdentity("COMMERCIAL", "2026-10", "A", "S1", "新名称");
    assertThat(a.materialSupplierKey()).isEqualTo(b.materialSupplierKey());
    assertThat(new ScrapPriceIdentity("COMMERCIAL", "2026-10", "A", null, "S1").supplierKey())
        .isNotEqualTo(a.supplierKey());
    assertThat(new ScrapPriceIdentity("COMMERCIAL", "2026-10", "A", "s1", null).supplierKey())
        .isNotEqualTo(a.supplierKey());
  }

  @Test
  void rejectsInvalidMonthAndIdentity() {
    assertThatThrownBy(() -> new ScrapPriceIdentity("COMMERCIAL", "2026-13", "A", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ScrapPriceIdentity("PLATE", "2026-10", "A", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ScrapPriceIdentity("COMMERCIAL", "2026-10", " ", null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void generatedMonthIsNotAnExplicitRuleAndFutureRuleCannotLeakBack() {
    var october = row(1L, "2026-10", "A", "IMPORT");
    var november = row(2L, "2026-11", "A", "CARRY_FORWARD");
    var december = row(3L, "2026-12", "A", "MANUAL");
    assertThat(
            ScrapRuleMonthPolicy.select(
                    List.of(december, november, october), "COMMERCIAL", "2026-11")
                .values())
        .containsExactly(october);
    assertThat(
            ScrapRuleMonthPolicy.select(
                    List.of(october, november, december), "COMMERCIAL", "2026-12")
                .values())
        .containsExactly(december);
    assertThat(ScrapRuleMonthPolicy.select(List.of(october), "COMMERCIAL", "2026-09")).isEmpty();
  }

  @Test
  void partialMonthDoesNotHideOtherMaterialsOrSuppliers() {
    var a = row(1L, "2026-10", "A", "IMPORT");
    var b = row(2L, "2026-10", "B", "IMPORT");
    var changedA = row(3L, "2026-11", "A", "MANUAL");
    var secondSupplier = row(4L, "2026-10", "A", "IMPORT");
    secondSupplier.setSupplierCode("S2");
    var otherUnit = row(5L, "2026-11", "C", "IMPORT");
    otherUnit.setBusinessUnitType("HOUSEHOLD");
    assertThat(
            ScrapRuleMonthPolicy.select(
                    List.of(a, b, changedA, secondSupplier, otherUnit), "COMMERCIAL", "2026-11")
                .values())
        .containsExactlyInAnyOrder(b, changedA, secondSupplier);
  }

  @Test
  void duplicateRuleDoesNotDependOnInputOrder() {
    var a = row(1L, "2026-10", "A", "IMPORT");
    var b = row(2L, "2026-10", "A", "MANUAL");
    assertThatThrownBy(() -> ScrapRuleMonthPolicy.select(List.of(a, b), "COMMERCIAL", "2026-10"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("身份重复");
  }

  static ScrapPrice row(Long id, String month, String code, String origin) {
    var row = new ScrapPrice();
    row.setId(id);
    row.setBusinessUnitType("COMMERCIAL");
    row.setPriceMonth(month);
    row.setMaterialCode(code);
    row.setOriginType(origin);
    return row;
  }
}
