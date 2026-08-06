package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FactorMonthlyPriceMapper extends BaseMapper<FactorMonthlyPrice> {

  @Select("SELECT id, factor_identity_id, price_month, price, tax_included,"
      + " source_upload_batch_id, latest_adjust_batch_id, latest_adjust_source_type,"
      + " latest_adjusted_by, latest_adjusted_at, source_tag, status,"
      + " created_by, created_at, updated_by, updated_at"
      + " FROM lp_factor_monthly_price"
      + " WHERE factor_identity_id = #{factorIdentityId}"
      + "   AND price_month = #{priceMonth}"
      + "   AND status = 'ACTIVE'"
      + " LIMIT 1")
  FactorMonthlyPrice findActiveByIdentityAndMonth(
      @Param("factorIdentityId") Long factorIdentityId,
      @Param("priceMonth") String priceMonth);
}
