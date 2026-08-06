package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceLinkedType2MergedRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2RowMatchSummary;
import java.time.YearMonth;
import java.util.List;

/** 把一对一成功的匹配结果映射为字段来源明确的类型 2 业务行。 */
public interface PriceLinkedType2MergedRowMapper {

  List<PriceLinkedType2MergedRow> map(
      PriceLinkedType2RowMatchSummary matchSummary, YearMonth pricingMonth);
}
