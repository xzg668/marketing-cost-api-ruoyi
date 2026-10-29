package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MonthlyOriginalRangeSourceMapper {
  @Select("""
    <script>SELECT * FROM lp_price_range_factor_rule WHERE business_unit_type=#{unit} AND org_code=#{org}
      AND pricing_month=#{month} AND rule_status IN ('LEGACY','PUBLISHED') AND material_code IN
      <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
      </script>
    """)
  List<PriceRangeFactorRule> ruleRows(@Param("unit") String unit,@Param("org") String org,@Param("month") String month,@Param("codes") Collection<String> codes);

  /** Keep large saved JSON documents out of MySQL filesort while preserving newest-version order. */
  default List<PriceRangeFactorRule> rules(String unit,String org,String month,Collection<String> codes) {
    return ruleRows(unit,org,month,codes).stream().sorted(Comparator.comparing(PriceRangeFactorRule::getVersionNo, Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing(PriceRangeFactorRule::getId, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
  }
  @Select("""
    <script>SELECT * FROM lp_price_range_item WHERE factor_rule_id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY factor_rule_id,id</script>
    """)
  List<PriceRangeItem> bands(@Param("ids") Collection<Long> ids);
  @Select("""
    <script>SELECT id,batch_no,import_type,price_month,business_unit_type,file_name,file_sha256,content_hash,
        parser_version,status FROM lp_factor_upload_batch WHERE id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY id</script>
    """)
  List<FactorUploadBatch> batches(@Param("ids") Collection<Long> ids);
}
