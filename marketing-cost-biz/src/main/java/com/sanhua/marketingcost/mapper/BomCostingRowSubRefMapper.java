package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.BomCostingRowSubRef;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * T8 新增：{@code lp_bom_costing_row_sub_ref} 访问层。
 *
 * <p>Flatten 阶段写入，T9 取价阶段按 {@code costing_row_id} 查子件清单读取。
 * 当前除 BaseMapper CRUD 外，为上卷父件价格类型确认提供一次性批量读取，避免逐结算行查询。
 */
@Mapper
public interface BomCostingRowSubRefMapper extends BaseMapper<BomCostingRowSubRef> {

  @Select({
    "<script>",
    "SELECT sr.*",
    "FROM lp_bom_costing_row_sub_ref sr",
    "WHERE sr.ref_type = 'SPECIAL_ROLLUP_CHILD'",
    "  AND sr.costing_row_id IN",
    "  <foreach collection='costingRowIds' item='id' open='(' separator=',' close=')'>",
    "    #{id}",
    "  </foreach>",
    "ORDER BY sr.costing_row_id, sr.id",
    "</script>"
  })
  @DataScope(alias = "sr")
  List<BomCostingRowSubRef> selectSpecialRollupChildren(
      @Param("costingRowIds") Collection<Long> costingRowIds);
}
