package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.PriceSettleItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PriceSettleItemMapper extends BaseMapper<PriceSettleItem> {

  /** V21：selectList 走数据隔离 */
  @DataScope
  @Override
  List<PriceSettleItem> selectList(@Param("ew") Wrapper<PriceSettleItem> queryWrapper);
}
