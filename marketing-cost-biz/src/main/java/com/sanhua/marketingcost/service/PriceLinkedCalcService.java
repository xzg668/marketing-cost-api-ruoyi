package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceLinkedCalcRow;
import com.sanhua.marketingcost.dto.PriceLinkedCalcTraceResponse;

/** 联动结果和计算依据查询。 */
public interface PriceLinkedCalcService {

  /**
   * 只读查询已落库的联动价计算结果。
   *
   * <p>查询页用于追溯核对，不能在列表查询时补写 calc_item，也不能触发联动价重算。
   */
  Page<PriceLinkedCalcRow> resultPage(
      String oaNo,
      String customer,
      String businessUnitType,
      String itemCode,
      String calcScene,
      String pricingMonth,
      String calcStatus,
      String factorSource,
      int page,
      int pageSize);

  /** 按规则ID试算当前输入，不修改报价或冻结结果。 */
  PriceLinkedCalcTraceResponse getTrace(Long id);

  /** 按结果ID读取已保存的计算依据，历史无底稿时返回空字段。 */
  PriceLinkedCalcTraceResponse getCalcTrace(Long calcId);
}
