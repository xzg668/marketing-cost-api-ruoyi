package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.BomCostingRowSubRef;
import org.apache.ibatis.annotations.Mapper;

/**
 * T8 新增：{@code lp_bom_costing_row_sub_ref} 访问层。
 *
 * <p>Flatten 阶段写入，T9 取价阶段按 {@code costing_row_id} 查子件清单读取。
 * 当前只需要 BaseMapper 的 CRUD；未来若有批量写入性能问题再补自定义 @Insert。
 */
@Mapper
public interface BomCostingRowSubRefMapper extends BaseMapper<BomCostingRowSubRef> {}
