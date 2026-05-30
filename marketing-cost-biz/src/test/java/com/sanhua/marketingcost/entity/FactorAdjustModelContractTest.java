package com.sanhua.marketingcost.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.FactorAdjustBatchDto;
import com.sanhua.marketingcost.dto.FactorAdjustPriceDto;
import com.sanhua.marketingcost.enums.FactorAdjustPriceStatus;
import com.sanhua.marketingcost.enums.FactorAdjustUsageScope;
import com.sanhua.marketingcost.enums.PriceLinkedImportEffectiveStrategy;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;

@DisplayName("V5-03 月度调价实体和 DTO 契约")
class FactorAdjustModelContractTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FactorAdjustBatch.class);
    TableInfoHelper.initTableInfo(assistant, FactorAdjustPrice.class);
    TableInfoHelper.initTableInfo(assistant, FactorMonthlyPrice.class);
    TableInfoHelper.initTableInfo(assistant, FactorUploadBatch.class);
  }

  @Test
  @DisplayName("实体表名和 V80 DDL 保持一致")
  void entityTableNamesMatchV80() {
    assertThat(TableInfoHelper.getTableInfo(FactorAdjustBatch.class).getTableName())
        .isEqualTo("lp_factor_adjust_batch");
    assertThat(TableInfoHelper.getTableInfo(FactorAdjustPrice.class).getTableName())
        .isEqualTo("lp_factor_adjust_price");
  }

  @Test
  @DisplayName("月度调价用途和联动导入策略枚举对齐接口值")
  void enumsExposeStableCodes() {
    assertThat(FactorAdjustUsageScope.REPRICE_ONLY.getCode()).isEqualTo("REPRICE_ONLY");
    assertThat(FactorAdjustUsageScope.REPRICE_AND_DAILY.getCode())
        .isEqualTo("REPRICE_AND_DAILY");
    assertThat(PriceLinkedImportEffectiveStrategy.APPEND_ONLY.getCode())
        .isEqualTo("APPEND_ONLY");
    assertThat(PriceLinkedImportEffectiveStrategy.OVERRIDE_EFFECTIVE.getCode())
        .isEqualTo("OVERRIDE_EFFECTIVE");
    assertThat(FactorAdjustPriceStatus.FAILED.getLabel()).contains("失败");
  }

  @Test
  @DisplayName("DTO 快照复制保留调价批次和明细关键字段")
  void dtoFromEntityCopiesImportantFields() {
    FactorAdjustBatch batch = new FactorAdjustBatch();
    batch.setId(1001L);
    batch.setAdjustBatchNo("FAB202605160001");
    batch.setAdjustType("MONTHLY");
    batch.setPricingMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setUsageScope("REPRICE_ONLY");
    batch.setChangedCount(2);
    FactorAdjustBatchDto batchDto = FactorAdjustBatchDto.fromEntity(batch);

    assertThat(batchDto.getId()).isEqualTo(1001L);
    assertThat(batchDto.getAdjustBatchNo()).isEqualTo("FAB202605160001");
    assertThat(batchDto.getAdjustType()).isEqualTo("MONTHLY");
    assertThat(batchDto.getUsageScope()).isEqualTo("REPRICE_ONLY");
    assertThat(batchDto.getChangedCount()).isEqualTo(2);

    FactorAdjustPrice price = new FactorAdjustPrice();
    price.setId(2001L);
    price.setAdjustBatchId(1001L);
    price.setFactorIdentityId(3001L);
    price.setShortName("Cu");
    price.setAdjustedPrice(new BigDecimal("65000.00"));
    price.setApplyToDaily(0);
    price.setStatus("CHANGED");
    FactorAdjustPriceDto priceDto = FactorAdjustPriceDto.fromEntity(price);

    assertThat(priceDto.getId()).isEqualTo(2001L);
    assertThat(priceDto.getFactorIdentityId()).isEqualTo(3001L);
    assertThat(priceDto.getAdjustedPrice()).isEqualByComparingTo("65000");
    assertThat(priceDto.getApplyToDaily()).isZero();
    assertThat(priceDto.getStatus()).isEqualTo("CHANGED");
  }
}
