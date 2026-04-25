package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.RowLocalPlaceholder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 行局部占位符配置 Mapper。
 *
 * <p>只继承 {@link BaseMapper}，无自定义 SQL —— 运维日常操作走 CRUD（selectList/
 * insert/updateById），业务读取由 {@link com.sanhua.marketingcost.formula.registry.
 * RowLocalPlaceholderRegistry} 统一提供缓存视图。
 */
@Mapper
public interface RowLocalPlaceholderMapper extends BaseMapper<RowLocalPlaceholder> {
}
