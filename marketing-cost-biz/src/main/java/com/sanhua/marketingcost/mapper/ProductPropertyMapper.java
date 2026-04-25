package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.ProductProperty;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ProductPropertyMapper extends BaseMapper<ProductProperty> {

  /** V21：selectList 走数据隔离 */
  @DataScope
  @Override
  List<ProductProperty> selectList(@Param("ew") Wrapper<ProductProperty> queryWrapper);
}
