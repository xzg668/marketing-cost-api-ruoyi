package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import org.apache.ibatis.annotations.Mapper;

/** 报价 BOM 标准件/替代件选择及版本历史访问契约。 */
@Mapper
public interface QuoteBomAlternativeSelectionMapper
    extends BaseMapper<QuoteBomAlternativeSelection> {
}
