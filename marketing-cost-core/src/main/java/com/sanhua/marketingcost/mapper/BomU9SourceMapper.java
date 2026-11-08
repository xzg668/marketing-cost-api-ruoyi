package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.BomU9Source;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * EasyData 写入的 {@code lp_bom_u9_source} 单层 BOM 只读访问层。
 *
 * <p>报价系统不提供 Excel 导入、批量写入或 Java 展开能力；运行时只读消费来源事实。
 */
@Mapper
public interface BomU9SourceMapper extends BaseMapper<BomU9Source> {
  /** Bounded source lookup; the consumer matches the complete BOM business line. */
  @Select("""
      <script>
      SELECT id,price_org_code,parent_material_no,child_material_no,bom_purpose,bom_version,
             child_seq,process_seq,effective_from,effective_to,child_type
      FROM lp_bom_u9_source
      WHERE price_org_code=#{priceOrgCode} AND parent_material_no IN
      <foreach collection="parents" item="parent" open="(" separator="," close=")">#{parent}</foreach>
      </script>
      """)
  List<BomU9Source> selectAlternativeMetadata(
      @Param("priceOrgCode") String priceOrgCode, @Param("parents") List<String> parents);
}
