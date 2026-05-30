package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("U9 BOM副产品字段契约")
class U9BomByproductFieldContractTest {

  @Test
  @DisplayName("模板映射包含五字段自然键和重复生产部门列")
  void mappingsContainNaturalKeyAndDuplicatedDeptHeaders() {
    assertThat(U9BomByproductFieldContract.DATASET_CODE).isEqualTo("U9_BOM_BYPRODUCT_MASTER");
    assertThat(U9BomByproductFieldContract.SHEET_NAME).isEqualTo("BOM母项");

    assertThat(U9BomByproductFieldContract.fieldMappings())
        .extracting(U9BomByproductFieldContract.FieldMapping::field)
        .contains(
            "bom_purpose",
            "parent_material_no",
            "byproduct_material_no",
            "effective_from",
            "effective_to",
            "production_dept_code",
            "production_dept_name");

    assertThat(U9BomByproductFieldContract.headerToField())
        .containsEntry(
            U9BomByproductFieldContract.canonicalHeader("母件料品.生产部门") + "#1",
            "production_dept_code")
        .containsEntry(
            U9BomByproductFieldContract.canonicalHeader("母件料品.生产部门") + "#2",
            "production_dept_name");
  }
}
