package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PricePrepareBatchMapper extends BaseMapper<PricePrepareBatch> {
  /** 基价依据按需读取，批次列表无需加载完整来源JSON。 */
  @org.apache.ibatis.annotations.Select(
      "SELECT finance_factor_snapshot_json FROM lp_price_prepare_batch WHERE"
          + " prepare_no=#{prepareNo}")
  String selectFinanceFactorSnapshot(
      @org.apache.ibatis.annotations.Param("prepareNo") String prepareNo);

  @org.apache.ibatis.annotations.Update(
      "UPDATE lp_price_prepare_batch SET finance_factor_snapshot_json=#{snapshot} WHERE"
          + " prepare_no=#{prepareNo} AND scenario_type='FINANCE_QUOTE_BASE' AND"
          + " finance_factor_snapshot_json IS NULL")
  int saveFinanceFactorSnapshot(
      @org.apache.ibatis.annotations.Param("prepareNo") String prepareNo,
      @org.apache.ibatis.annotations.Param("snapshot") String snapshot);
}
