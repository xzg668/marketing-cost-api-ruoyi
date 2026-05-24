package com.sanhua.marketingcost.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PriceLinkedCalcItem 场景字段映射")
class PriceLinkedCalcItemMappingTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceLinkedCalcItem.class);
  }

  @Test
  @DisplayName("LPE-02：场景、月份、批次、指纹和状态字段映射到 calc_item")
  void mapsSceneContextColumns() {
    TableInfo tableInfo = TableInfoHelper.getTableInfo(PriceLinkedCalcItem.class);

    Map<String, String> columnsByProperty =
        tableInfo.getFieldList().stream()
            .collect(Collectors.toMap(field -> field.getProperty(), field -> field.getColumn()));

    assertThat(tableInfo.getTableName()).isEqualTo("lp_price_linked_calc_item");
    assertThat(columnsByProperty)
        .containsEntry("calcScene", "calc_scene")
        .containsEntry("pricingMonth", "pricing_month")
        .containsEntry("adjustBatchId", "adjust_batch_id")
        .containsEntry("factorSource", "factor_source")
        .containsEntry("calcFingerprint", "calc_fingerprint")
        .containsEntry("calcStatus", "calc_status")
        .containsEntry("calcMessage", "calc_message");
  }
}
