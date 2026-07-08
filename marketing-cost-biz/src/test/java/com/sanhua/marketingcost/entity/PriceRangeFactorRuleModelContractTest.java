package com.sanhua.marketingcost.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MFRP-01 行情因素区间价实体和 Mapper 契约")
class PriceRangeFactorRuleModelContractTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceRangeFactorRule.class);
    TableInfoHelper.initTableInfo(assistant, PriceRangeItem.class);
  }

  @Test
  @DisplayName("实体表名与 MFRP-01 DDL 一致")
  void tableNamesMatchMfrp01Ddl() {
    assertThat(TableInfoHelper.getTableInfo(PriceRangeFactorRule.class).getTableName())
        .isEqualTo("lp_price_range_factor_rule");
    assertThat(TableInfoHelper.getTableInfo(PriceRangeItem.class).getTableName())
        .isEqualTo("lp_price_range_item");
  }

  @Test
  @DisplayName("PriceRangeItem 新字段映射齐全且 getter/setter 可用")
  void priceRangeItemIncludesFactorFields() {
    List<String> fieldNames =
        TableInfoHelper.getTableInfo(PriceRangeItem.class).getFieldList().stream()
            .map(TableFieldInfo::getProperty)
            .toList();
    assertThat(fieldNames).contains(
        "rangeBasis",
        "factorRuleId",
        "factorCode",
        "importBatchNo",
        "currentFlag");

    PriceRangeItem item = new PriceRangeItem();
    item.setRangeBasis("FACTOR");
    item.setFactorRuleId(1001L);
    item.setFactorCode("CU");
    item.setImportBatchNo("RANGE202607020001");
    item.setCurrentFlag(1);
    item.setRangeLow(new BigDecimal("87501"));
    item.setRangeHigh(new BigDecimal("92500"));

    assertThat(item.getRangeBasis()).isEqualTo("FACTOR");
    assertThat(item.getFactorRuleId()).isEqualTo(1001L);
    assertThat(item.getFactorCode()).isEqualTo("CU");
    assertThat(item.getImportBatchNo()).isEqualTo("RANGE202607020001");
    assertThat(item.getCurrentFlag()).isOne();
    assertThat(item.getRangeLow()).isEqualByComparingTo("87501");
    assertThat(item.getRangeHigh()).isEqualByComparingTo("92500");
  }

  @Test
  @DisplayName("PriceRangeFactorRule 关键字段 getter/setter 可用")
  void factorRuleFieldsAreAccessible() {
    PriceRangeFactorRule rule = new PriceRangeFactorRule();
    rule.setBusinessUnitType("COMMERCIAL");
    rule.setMaterialCode("201850160");
    rule.setMaterialName("红色引线");
    rule.setSpecModel("DRAWING-001");
    rule.setFactorCode("CU");
    rule.setFactorName("电解铜");
    rule.setFactorUnit("元/吨");
    rule.setPriceUnit("元/米");
    rule.setVersionNo(2);
    rule.setImportBatchNo("RANGE202607020001");
    rule.setSourceFile("demo.xlsx");
    rule.setSourceSheet("区间铜价");
    rule.setEffectiveFrom(LocalDate.of(2026, 7, 1));
    rule.setEffectiveTo(LocalDate.of(2026, 8, 1));
    rule.setCurrentFlag(1);

    assertThat(rule.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(rule.getMaterialCode()).isEqualTo("201850160");
    assertThat(rule.getFactorCode()).isEqualTo("CU");
    assertThat(rule.getVersionNo()).isEqualTo(2);
    assertThat(rule.getImportBatchNo()).isEqualTo("RANGE202607020001");
    assertThat(rule.getSourceSheet()).isEqualTo("区间铜价");
    assertThat(rule.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 7, 1));
    assertThat(rule.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 8, 1));
    assertThat(rule.getCurrentFlag()).isOne();
  }

  @Test
  @DisplayName("PriceRangeFactorRuleMapper 继承 BaseMapper 且 selectList 走数据隔离")
  void factorRuleMapperIsDataScoped() throws NoSuchMethodException {
    assertThat(BaseMapper.class).isAssignableFrom(PriceRangeFactorRuleMapper.class);
    assertThat(PriceRangeFactorRuleMapper.class.getMethod("selectList", Wrapper.class)
        .isAnnotationPresent(DataScope.class)).isTrue();
  }
}
