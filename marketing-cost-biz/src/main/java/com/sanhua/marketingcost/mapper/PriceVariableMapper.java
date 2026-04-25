package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.PriceVariable;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface PriceVariableMapper extends BaseMapper<PriceVariable> {

  /**
   * V21：selectList 走数据隔离；
   * V32：{@code includeShared=true} 允许看到 {@code business_unit_type IS NULL} 的
   * 跨 BU 共享变量（例如 Cu/Zn 等公共物价维度），避免把共享行"过滤没了"。
   */
  @DataScope(includeShared = true)
  @Override
  List<PriceVariable> selectList(@Param("ew") Wrapper<PriceVariable> queryWrapper);
}
