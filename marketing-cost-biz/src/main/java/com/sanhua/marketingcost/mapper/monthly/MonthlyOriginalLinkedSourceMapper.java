package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.PriceLinkedItem;
import java.time.LocalDate;
import java.util.*;
import org.apache.ibatis.annotations.*;

/** Existing linked table, including finance-imported technical corrections. */
@Mapper
public interface MonthlyOriginalLinkedSourceMapper {
  @Select("""
    <script>SELECT p.* FROM lp_price_linked_item p
      WHERE p.business_unit_type=#{unit} AND p.org_code=#{org} AND p.deleted=0
      AND p.rule_status IN ('LEGACY','PUBLISHED')
      AND ((p.source_kind='PUBLIC' AND p.pricing_month=#{month})
        OR (p.source_kind='TECH_SUPPLEMENTAL' AND p.technical_publication_status='AVAILABLE'
          AND p.pricing_month &lt;= #{requestedMonth}
          AND (p.effective_from IS NULL OR p.effective_from &lt;= #{date})
          AND (p.effective_to IS NULL OR p.effective_to &gt; #{date})
          AND EXISTS (SELECT 1 FROM lp_factor_upload_batch b WHERE b.id=p.source_upload_batch_id
            AND b.business_unit_type=p.business_unit_type AND b.import_purpose='TECH_PRICE_CORRECTION'
            AND b.status IN ('SUCCESS','PARTIAL'))))
      AND p.material_code IN <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
      ORDER BY p.rule_revision DESC,p.created_at DESC,p.id DESC</script>
    """)
  List<PriceLinkedItem> rules(@Param("unit") String unit,@Param("org") String org,@Param("month") String month,
      @Param("requestedMonth") String requestedMonth,@Param("date") LocalDate date,@Param("codes") Collection<String> codes);
}
