package com.sanhua.marketingcost.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceAuditLogMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceCostItemMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepricePartItemMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceResultMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;

@DisplayName("T1 月度调价实体和 Mapper 契约")
class MonthlyRepriceModelContractTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, CostRunTask.class);
    TableInfoHelper.initTableInfo(assistant, MonthlyRepriceBatch.class);
    TableInfoHelper.initTableInfo(assistant, MonthlyRepriceResult.class);
    TableInfoHelper.initTableInfo(assistant, MonthlyRepricePartItem.class);
    TableInfoHelper.initTableInfo(assistant, MonthlyRepriceCostItem.class);
    TableInfoHelper.initTableInfo(assistant, MonthlyRepriceAuditLog.class);
  }

  @Test
  @DisplayName("实体表名和月度调价/通用任务 DDL 保持一致")
  void entityTableNamesMatchV126() {
    assertThat(TableInfoHelper.getTableInfo(MonthlyRepriceBatch.class).getTableName())
        .isEqualTo("lp_monthly_reprice_batch");
    assertThat(TableInfoHelper.getTableInfo(CostRunTask.class).getTableName())
        .isEqualTo("lp_cost_run_task");
    assertThat(TableInfoHelper.getTableInfo(MonthlyRepriceResult.class).getTableName())
        .isEqualTo("lp_monthly_reprice_result");
    assertThat(TableInfoHelper.getTableInfo(MonthlyRepricePartItem.class).getTableName())
        .isEqualTo("lp_monthly_reprice_part_item");
    assertThat(TableInfoHelper.getTableInfo(MonthlyRepriceCostItem.class).getTableName())
        .isEqualTo("lp_monthly_reprice_cost_item");
    assertThat(TableInfoHelper.getTableInfo(MonthlyRepriceAuditLog.class).getTableName())
        .isEqualTo("lp_monthly_reprice_audit_log");
  }

  @Test
  @DisplayName("Mapper 绑定正确实体")
  void mappersBindExpectedEntities() {
    assertMapperEntity(MonthlyRepriceBatchMapper.class, MonthlyRepriceBatch.class);
    assertMapperEntity(CostRunTaskMapper.class, CostRunTask.class);
    assertMapperEntity(MonthlyRepriceResultMapper.class, MonthlyRepriceResult.class);
    assertMapperEntity(MonthlyRepricePartItemMapper.class, MonthlyRepricePartItem.class);
    assertMapperEntity(MonthlyRepriceCostItemMapper.class, MonthlyRepriceCostItem.class);
    assertMapperEntity(MonthlyRepriceAuditLogMapper.class, MonthlyRepriceAuditLog.class);
  }

  @Test
  @DisplayName("核算对象维度保留包装方式和客户名称")
  void calcObjectDimensionsArePresent() {
    CostRunTask task = new CostRunTask();
    task.setOaNo("OA-001");
    task.setProductCode("P-001");
    task.setPackageMethod("大包装");
    task.setCustomerName("客户A");
    task.setCalcObjectKey("hash-key");

    assertThat(task.getOaNo()).isEqualTo("OA-001");
    assertThat(task.getProductCode()).isEqualTo("P-001");
    assertThat(task.getPackageMethod()).isEqualTo("大包装");
    assertThat(task.getCustomerName()).isEqualTo("客户A");
    assertThat(task.getCalcObjectKey()).isEqualTo("hash-key");
  }

  @Test
  @DisplayName("影响因素调价批次实体暴露 adjustType 字段")
  void factorAdjustBatchHasAdjustType() {
    FactorAdjustBatch batch = new FactorAdjustBatch();
    batch.setAdjustType("MONTHLY");
    assertThat(batch.getAdjustType()).isEqualTo("MONTHLY");
  }

  private static void assertMapperEntity(Class<?> mapperClass, Class<?> entityClass) {
    Class<?> actual =
        ResolvableType.forClass(mapperClass).as(BaseMapper.class).getGeneric(0).resolve();
    assertThat(actual).isEqualTo(entityClass);
  }
}
