package com.sanhua.marketingcost.service.pricing.inquiry;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class InquirySupplierNamesTest {
  @Test
  void actualAbbreviationAndWhitespaceResolveToTheFormalCode() {
    var supplier = new InquirySupplierNames.Supplier("S001069", "浙江东信机械有限公司");
    var result = InquirySupplierNames.resolve("浙江　东信", List.of(supplier), List.of());
    assertThat(result.code()).isEqualTo("S001069");
    assertThat(result.name()).isEqualTo(supplier.name());
  }

  @Test
  void exactNameTakesPriorityAndMaterialRelationsDisambiguateAbbreviations() {
    var local = new InquirySupplierNames.Supplier("A", "浙江东信机械有限公司");
    var other = new InquirySupplierNames.Supplier("B", "浙江东信配件有限公司");
    assertThat(
            InquirySupplierNames.resolve(other.name(), List.of(local), List.of(local, other))
                .code())
        .isEqualTo("B");
    assertThat(InquirySupplierNames.resolve("浙江东信", List.of(local), List.of(local, other)).code())
        .isEqualTo("A");
  }

  @Test
  void equallySpecificDifferentCodesRemainAmbiguous() {
    var suppliers =
        List.of(
            new InquirySupplierNames.Supplier("A", "浙江东信机械有限公司"),
            new InquirySupplierNames.Supplier("B", "浙江东信配件有限公司"));
    assertThatThrownBy(() -> InquirySupplierNames.resolve("浙江东信", suppliers, List.of()))
        .hasMessageContaining("无法唯一匹配");
  }

  @Test
  void emptyAndUnmatchedNamesRemainAvailableForExistingSingleSupplierPolicy() {
    assertThat(InquirySupplierNames.resolve("", List.of(), List.of()).basis()).isEqualTo("EMPTY");
    assertThat(InquirySupplierNames.resolve("新供应商", List.of(), List.of()).basis())
        .isEqualTo("NAME_ONLY");
  }
}
