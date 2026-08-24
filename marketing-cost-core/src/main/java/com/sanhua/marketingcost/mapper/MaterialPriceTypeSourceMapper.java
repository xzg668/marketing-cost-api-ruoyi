package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.dto.MaterialPriceTypeSourceCandidate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 读取正式价格表，用于兼容推断价格类型同步上线前的历史导入数据。 */
@Mapper
public interface MaterialPriceTypeSourceMapper {

  @Select(
      """
      SELECT source.material_code AS materialCode,
             source.price_type AS priceType,
             source.business_unit_type AS businessUnitType,
             source.effective_from AS effectiveFrom,
             source.effective_to AS effectiveTo,
             source.source_system AS sourceSystem,
             source.source_time AS sourceTime,
             source.source_id AS sourceId
        FROM (
          SELECT material_code,
                 CASE
                   WHEN UPPER(COALESCE(source_type, '')) IN ('SETTLE', 'SETTLE_FIXED')
                     THEN 'SETTLE_FIXED'
                   ELSE 'FIXED'
                 END AS price_type,
                 business_unit_type,
                 effective_from,
                 effective_to,
                 'PRICE_FIXED' AS source_system,
                 COALESCE(imported_at, created_at) AS source_time,
                 id AS source_id
            FROM lp_price_fixed_item
           WHERE material_code = #{materialCode}
          UNION ALL
          SELECT material_code,
                 'LINKED' AS price_type,
                 business_unit_type,
                 effective_from,
                 effective_to,
                 'PRICE_LINKED' AS source_system,
                 created_at AS source_time,
                 id AS source_id
            FROM lp_price_linked_item
           WHERE material_code = #{materialCode}
             AND deleted = 0
          UNION ALL
          SELECT material_code,
                 'RANGE' AS price_type,
                 business_unit_type,
                 effective_from,
                 effective_to,
                 'PRICE_RANGE' AS source_system,
                 created_at AS source_time,
                 id AS source_id
            FROM lp_price_range_item
           WHERE material_code = #{materialCode}
        ) source
       WHERE (source.business_unit_type = #{businessUnitType})
          OR (source.business_unit_type IS NULL OR source.business_unit_type = '')
       ORDER BY CASE
                  WHEN source.business_unit_type = #{businessUnitType} THEN 0
                  ELSE 1
                END,
                source.source_time DESC,
                source.source_id DESC
       LIMIT 1
      """)
  MaterialPriceTypeSourceCandidate selectLatest(
      @Param("materialCode") String materialCode,
      @Param("businessUnitType") String businessUnitType);
}
