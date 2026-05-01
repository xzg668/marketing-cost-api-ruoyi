package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.PriceScrap;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 废料回收价 mapper (V48)。selectList 走 V21 数据隔离。 */
@Mapper
public interface PriceScrapMapper extends BaseMapper<PriceScrap> {

  @DataScope
  @Override
  List<PriceScrap> selectList(@Param("ew") Wrapper<PriceScrap> queryWrapper);
}
