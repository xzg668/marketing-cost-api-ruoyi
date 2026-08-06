package com.sanhua.marketingcost.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.mapper.QuoteBomAlternativeSelectionMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-01 报价BOM标准/替代选择实体契约")
class QuoteBomAlternativeSelectionEntityContractTest {

  @Test
  @DisplayName("实体和Mapper映射选择版本表")
  void entityAndMapperUseSelectionTable() {
    assertThat(QuoteBomAlternativeSelection.class.getAnnotation(TableName.class).value())
        .isEqualTo("lp_quote_bom_alternative_selection");
    assertThat(BaseMapper.class)
        .isAssignableFrom(QuoteBomAlternativeSelectionMapper.class);
  }

  @Test
  @DisplayName("实体覆盖报价作用域、替代组、选择版本、来源和审计字段")
  void entityCoversCompletePersistenceContract() throws Exception {
    assertField("selectionNo", String.class);
    assertField("oaNo", String.class);
    assertField("oaFormItemId", Long.class);
    assertField("topProductCode", String.class);
    assertField("periodMonth", String.class);
    assertField("priceOrgCode", String.class);
    assertField("alternativeGroupKey", String.class);
    assertField("parentPath", String.class);
    assertField("parentMaterialCode", String.class);
    assertField("parentMaterialName", String.class);
    assertField("childSeq", Integer.class);
    assertField("processSeq", String.class);
    assertField("bomPurpose", String.class);
    assertField("bomVersion", String.class);
    assertField("sourceEffectiveFrom", LocalDate.class);
    assertField("sourceEffectiveTo", LocalDate.class);
    assertField("standardMaterialCode", String.class);
    assertField("selectedMaterialCode", String.class);
    assertField("selectedChildType", String.class);
    assertField("selectionSource", String.class);
    assertField("selectionVersion", Integer.class);
    assertField("selectionStatus", String.class);
    assertField("currentSlot", Integer.class);
    assertField("candidateSnapshotJson", String.class);
    assertField("sourceImportBatchId", String.class);
    assertField("sourceBuildBatchId", String.class);
    assertField("selectedBy", String.class);
    assertField("selectedAt", LocalDateTime.class);
    assertField("selectionRemark", String.class);
    assertField("businessUnitType", String.class);
    assertField("createdAt", LocalDateTime.class);
    assertField("updatedAt", LocalDateTime.class);
  }

  @Test
  @DisplayName("实体常量冻结标准/替代、选择来源、状态和当前槽位")
  void constantsMatchDatabaseContract() {
    assertThat(QuoteBomAlternativeSelection.CHILD_TYPE_STANDARD).isEqualTo("STANDARD");
    assertThat(QuoteBomAlternativeSelection.CHILD_TYPE_ALTERNATIVE).isEqualTo("ALTERNATIVE");
    assertThat(QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD).isEqualTo("AUTO_STANDARD");
    assertThat(QuoteBomAlternativeSelection.SOURCE_MANUAL_STANDARD)
        .isEqualTo("MANUAL_STANDARD");
    assertThat(QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE)
        .isEqualTo("MANUAL_ALTERNATIVE");
    assertThat(QuoteBomAlternativeSelection.STATUS_ACTIVE).isEqualTo("ACTIVE");
    assertThat(QuoteBomAlternativeSelection.STATUS_SUPERSEDED).isEqualTo("SUPERSEDED");
    assertThat(QuoteBomAlternativeSelection.STATUS_STALE).isEqualTo("STALE");
    assertThat(QuoteBomAlternativeSelection.CURRENT_SLOT).isEqualTo(1);
  }

  private static void assertField(String name, Class<?> type) throws Exception {
    assertThat(QuoteBomAlternativeSelection.class.getDeclaredField(name).getType())
        .isEqualTo(type);
  }
}
