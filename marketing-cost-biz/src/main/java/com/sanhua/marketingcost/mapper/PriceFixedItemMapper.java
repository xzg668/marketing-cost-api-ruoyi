package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface PriceFixedItemMapper extends BaseMapper<PriceFixedItem> {

  /** V21：selectList 走数据隔离 */
  @DataScope
  @Override
  List<PriceFixedItem> selectList(@Param("ew") Wrapper<PriceFixedItem> queryWrapper);
}
