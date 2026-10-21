package com.sanhua.marketingcost.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Exact current-master lookup, separate from the existing product pickers' fuzzy search. */
@Mapper
public interface PlateMaterialMasterQuery {
  record Match(Long id, String model, String productionDivision) {}

  @Select("""
      <script>
      SELECT id, TRIM(material_model) COLLATE utf8mb4_0900_bin AS model,
             production_division AS productionDivision
      FROM lp_material_master_raw
      WHERE organization_code=#{organization} AND active_flag=1
        AND CAST(UNHEX(SHA2(TRIM(material_model),256)) AS BINARY(32)) IN
        <foreach collection="models" item="model" open="(" separator="," close=")">UNHEX(SHA2(#{model},256))</foreach>
        AND (TRIM(material_model) COLLATE utf8mb4_0900_bin) IN
        <foreach collection="models" item="model" open="(" separator="," close=")">#{model}</foreach>
      ORDER BY id
      </script>
      """)
  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  // The digest index narrows candidates; the complete binary comparison above remains authoritative.
  List<Match> findActiveModels(@Param("organization") String organization, @Param("models") List<String> models);
}
