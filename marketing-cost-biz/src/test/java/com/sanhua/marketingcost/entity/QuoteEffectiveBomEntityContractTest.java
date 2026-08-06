package com.sanhua.marketingcost.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.mapper.QuoteEffectiveBomNodeMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QEB-01 报价最终有效BOM实体契约")
class QuoteEffectiveBomEntityContractTest {

  @Test
  @DisplayName("最终节点实体和Mapper映射独立节点表")
  void entityAndMapperUseEffectiveNodeTable() {
    assertThat(QuoteEffectiveBomNode.class.getAnnotation(TableName.class).value())
        .isEqualTo("lp_quote_effective_bom_node");
    assertThat(BaseMapper.class).isAssignableFrom(QuoteEffectiveBomNodeMapper.class);
  }

  @Test
  @DisplayName("最终节点实体覆盖完整持久化契约")
  void entityCoversCompletePersistenceContract() throws Exception {
    assertField("id", Long.class);
    assertField("buildBatchId", String.class);
    assertField("originMonthlySnapshotId", Long.class);
    assertField("effectiveVariantHash", String.class);
    assertField("topProductCode", String.class);
    assertField("costPeriodMonth", String.class);
    assertField("priceOrgCode", String.class);
    assertField("nodeKey", String.class);
    assertField("parentNodeKey", String.class);
    assertField("nodeLevel", Integer.class);
    assertField("sortSeq", Integer.class);
    assertField("nodePath", String.class);
    assertField("materialCode", String.class);
    assertField("materialName", String.class);
    assertField("materialSpec", String.class);
    assertField("materialModel", String.class);
    assertField("materialUnit", String.class);
    assertField("qtyPerParent", BigDecimal.class);
    assertField("qtyPerTop", BigDecimal.class);
    assertField("sourceMaterialShape", String.class);
    assertField("effectiveMaterialShape", String.class);
    assertField("shapeResolutionSource", String.class);
    assertField("shapePolicyId", Long.class);
    assertField("shapePolicyFingerprint", String.class);
    assertField("selectedSupplierRatioId", Long.class);
    assertField("selectedSupplierCode", String.class);
    assertField("selectedSupplierName", String.class);
    assertField("selectedSupplyRatio", BigDecimal.class);
    assertField("alternativeGroupKey", String.class);
    assertField("alternativeChildType", String.class);
    assertField("alternativeSelectionId", Long.class);
    assertField("alternativeSelectionSource", String.class);
    assertField("sourceBomType", String.class);
    assertField("sourceBomBatchId", String.class);
    assertField("sourceHierarchyId", Long.class);
    assertField("sourceNodePath", String.class);
    assertField("createdAt", LocalDateTime.class);
    assertField("createdBy", Long.class);
  }

  @Test
  @DisplayName("现有三张实体补齐最终构建和月度继承字段")
  void existingEntitiesExposeNewTraceFields() throws Exception {
    assertField(QuoteBomMonthlySnapshot.class, "freezeStatus", String.class);
    assertField(QuoteBomMonthlySnapshot.class, "effectiveBuildBatchId", String.class);
    assertField(QuoteBomMonthlySnapshot.class, "effectiveVariantHash", String.class);
    assertField(QuoteBomMonthlySnapshot.class, "frozenAt", LocalDateTime.class);
    assertField(QuoteBomMonthlySnapshot.class, "frozenBy", Long.class);
    assertField(QuoteBomConfirmation.class, "costingBuildBatchId", String.class);
    assertField(
        QuoteBomAlternativeSelection.class,
        "inheritedMonthlySnapshotId",
        Long.class);
    assertThat(QuoteBomAlternativeSelection.SOURCE_INHERITED_MONTHLY)
        .isEqualTo("INHERITED_MONTHLY");
  }

  private static void assertField(String name, Class<?> type) throws Exception {
    assertField(QuoteEffectiveBomNode.class, name, type);
  }

  private static void assertField(Class<?> owner, String name, Class<?> type)
      throws Exception {
    assertThat(owner.getDeclaredField(name).getType()).isEqualTo(type);
  }
}
