package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.ScrapPrice;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ScrapPriceMapper extends BaseMapper<ScrapPrice> {
  /** 来源与沿用记录一次读入，避免按行查月；写路径在统一事务中锁定同业务范围。 */
  @Select(
      "SELECT * FROM lp_scrap_price WHERE business_unit_type=#{businessUnit} ORDER BY id FOR"
          + " UPDATE")
  List<ScrapPrice> lockScope(@Param("businessUnit") String businessUnit);
}
