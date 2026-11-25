package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** EasyData 写入的 {@code lp_bom_raw_hierarchy} 完整 BOM 层级事实只读访问层。 */
@Mapper
public interface BomRawHierarchyMapper extends BaseMapper<BomRawHierarchy> {
  /** Only the finished product's direct U9 assembly lines can identify its bare body. */
  @Select("""
      <script>
      SELECT top_product_code, material_code, material_category_1, material_category_2, cost_element_code
      FROM lp_bom_raw_hierarchy
      WHERE price_org_code = #{priceOrgCode} AND source_type = 'U9'
        AND level = 1 AND parent_code = top_product_code
        AND bom_purpose = '主制造' AND bom_status = '已核准'
        AND effective_from &lt;= #{asOf} AND (effective_to IS NULL OR effective_to &gt;= #{asOf})
        AND top_product_code IN
        <foreach collection="products" item="product" open="(" separator="," close=")">#{product}</foreach>
      </script>
      """)
  List<BomRawHierarchy> selectNetLossDirectChildren(@Param("priceOrgCode") String priceOrgCode,
      @Param("products") Collection<String> products, @Param("asOf") LocalDate asOf);
}
