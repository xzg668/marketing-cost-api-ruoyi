package com.sanhua.marketingcost.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomPackageReferenceDetailDto;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomPreparationRecordDto;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSupplementDetailDto;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("报价产品 BOM 准备实体字段契约")
class QuoteBomPreparationEntityContractTest {

  @Test
  @DisplayName("实体映射到独立补录层和追溯表")
  void entitiesMapToExpectedTables() {
    assertTable(QuoteBomPreparationRecord.class, "lp_quote_bom_preparation_record");
    assertTable(QuoteBomPackageReference.class, "lp_quote_bom_package_reference");
    assertTable(QuoteBomPackageReferenceDetail.class, "lp_quote_bom_package_reference_detail");
    assertTable(QuoteBomSupplementVersion.class, "lp_quote_bom_supplement_version");
    assertTable(QuoteBomSupplementDetail.class, "lp_quote_bom_supplement_detail");
    assertTable(BusinessChangeLog.class, "lp_business_change_log");
    assertTable(BomCostingRowSourceRef.class, "lp_bom_costing_row_source_ref");
  }

  @Test
  @DisplayName("QuoteBomStatus 扩展字段可承载产品形态、包装参考和审核追踪")
  void quoteBomStatusHasPreparationFields() {
    assertFields(
        QuoteBomStatus.class,
        "preparationRecordId",
        "productType",
        "bareProductCode",
        "needPackage",
        "referenceFinishedCode",
        "sourceTopProductCode",
        "reviewStatus",
        "reviewerUserId",
        "reviewerName",
        "reviewedAt",
        "costingBuildBatchId");
  }

  @Test
  @DisplayName("BomSupplementTodo 可保存真实 OA 推送状态")
  void bomSupplementTodoHasRealOaPushFields() {
    assertFields(
        BomSupplementTodo.class,
        "oaTodoId",
        "oaTodoUrl",
        "pushStatus",
        "pushErrorMessage",
        "lastPushAt",
        "closedAt");
  }

  @Test
  @DisplayName("包装参考实体显式保存参考成品、source_top 和调整字段")
  void packageReferenceDetailHasTraceAndAdjustedFields() {
    assertFields(
        QuoteBomPackageReferenceDetail.class,
        "referenceFinishedCode",
        "sourceTopProductCode",
        "packageParentCode",
        "packageParentMainCategoryCode",
        "packageQtyPerParent",
        "adjustedPackageQtyPerParent",
        "packageMaterialCode",
        "packageMaterialMainCategoryCode",
        "childQtyPerParent",
        "adjustedChildQtyPerParent",
        "sourceRawHierarchyId",
        "sourceU9BomId",
        "selectedFlag",
        "editedFlag");
  }

  @Test
  @DisplayName("基础 DTO 覆盖协作页和审核页需要的关键字段")
  void dtoFieldsCoverBasicPageContracts() {
    assertFields(
        QuoteBomPreparationRecordDto.class,
        "quoteProductCode",
        "productType",
        "bareProductCode",
        "referenceFinishedCode",
        "sourceTopProductCode",
        "reviewStatus",
        "reuseType",
        "reuseValidUntil");
    assertFields(
        QuoteBomPackageReferenceDetailDto.class,
        "packageParentCode",
        "packageMaterialCode",
        "childQtyPerParent",
        "adjustedChildQtyPerParent",
        "childParentBaseQty",
        "adjustedChildParentBaseQty",
        "selected",
        "edited");
    assertFields(
        QuoteBomSupplementDetailDto.class,
        "supplementScope",
        "parentCode",
        "materialCode",
        "qtyPerParent",
        "qtyPerTop",
        "parentBaseQty");
  }

  private static void assertTable(Class<?> type, String tableName) {
    TableName annotation = type.getAnnotation(TableName.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.value()).isEqualTo(tableName);
  }

  private static void assertFields(Class<?> type, String... fields) {
    Set<String> names =
        Arrays.stream(type.getDeclaredFields()).map(Field::getName).collect(Collectors.toSet());
    assertThat(names).contains(fields);
  }
}
