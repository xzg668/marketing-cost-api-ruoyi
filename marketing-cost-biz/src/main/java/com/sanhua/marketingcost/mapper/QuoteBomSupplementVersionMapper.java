package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteBomSupplementVersionMapper extends BaseMapper<QuoteBomSupplementVersion> {

  /** 电子图库 DRAFT 混合树指纹乐观锁；已发布版本不能重建。 */
  @Update("""
      <script>
      UPDATE lp_quote_bom_supplement_version
      SET composition_fingerprint = #{compositionFingerprint}, updated_at = #{updatedAt}
      WHERE id = #{id}
        AND version_status = 'DRAFT'
        AND bom_source = 'ELECTRONIC_DRAWING_EXCEL'
        AND material_org_code = #{materialOrgCode}
        AND
        <choose>
          <when test='expectedFingerprint == null'>composition_fingerprint IS NULL</when>
          <otherwise>composition_fingerprint = #{expectedFingerprint}</otherwise>
        </choose>
      </script>
      """)
  int updateElectronicDrawingCompositionFingerprint(
      @Param("id") Long id,
      @Param("expectedFingerprint") String expectedFingerprint,
      @Param("compositionFingerprint") String compositionFingerprint,
      @Param("materialOrgCode") String materialOrgCode,
      @Param("updatedAt") LocalDateTime updatedAt);
}
