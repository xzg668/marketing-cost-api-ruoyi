package com.sanhua.marketingcost.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.mapper.MaterialQuoteShapePolicyMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QEB-01 料品报价形态规则实体契约")
class MaterialQuoteShapePolicyEntityContractTest {

  @Test
  @DisplayName("形态规则实体和Mapper映射规则表")
  void entityAndMapperUseShapePolicyTable() {
    assertThat(MaterialQuoteShapePolicy.class.getAnnotation(TableName.class).value())
        .isEqualTo("lp_material_quote_shape_policy");
    assertThat(BaseMapper.class).isAssignableFrom(MaterialQuoteShapePolicyMapper.class);
  }

  @Test
  @DisplayName("实体覆盖组织料号、月份、条件动作和审计字段")
  void entityCoversCompletePersistenceContract() throws Exception {
    assertField("id", Long.class);
    assertField("materialOrgCode", String.class);
    assertField("materialCode", String.class);
    assertField("materialName", String.class);
    assertField("materialSpec", String.class);
    assertField("materialModel", String.class);
    assertField("policyMode", String.class);
    assertField("fixedTargetShape", String.class);
    assertField("conditionConfigJson", String.class);
    assertField("actionConfigJson", String.class);
    assertField("effectiveFromMonth", String.class);
    assertField("effectiveToMonth", String.class);
    assertField("enabled", Integer.class);
    assertField("remark", String.class);
    assertField("createdAt", LocalDateTime.class);
    assertField("createdBy", Long.class);
    assertField("updatedAt", LocalDateTime.class);
    assertField("updatedBy", Long.class);
  }

  @Test
  @DisplayName("规则模式和启用状态常量与数据库契约一致")
  void constantsMatchDatabaseContract() {
    assertThat(MaterialQuoteShapePolicy.MODE_FIXED).isEqualTo("FIXED");
    assertThat(MaterialQuoteShapePolicy.MODE_SUPPLIER_RATIO).isEqualTo("SUPPLIER_RATIO");
    assertThat(MaterialQuoteShapePolicy.ENABLED).isEqualTo(1);
    assertThat(MaterialQuoteShapePolicy.DISABLED).isZero();
  }

  private static void assertField(String name, Class<?> type) throws Exception {
    assertThat(MaterialQuoteShapePolicy.class.getDeclaredField(name).getType())
        .isEqualTo(type);
  }
}
