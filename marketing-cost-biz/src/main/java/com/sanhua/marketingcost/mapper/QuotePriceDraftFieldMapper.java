package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

@Mapper
public interface QuotePriceDraftFieldMapper extends BaseMapper<QuotePriceDraftField> {

  @Select("""
      SELECT f.* FROM lp_quote_price_draft_field f
      JOIN lp_quote_price_draft d ON d.id = f.price_draft_id
      WHERE f.price_draft_id = #{priceDraftId}
        AND d.business_unit_type = #{businessUnitType} AND d.org_code = #{orgCode}
      ORDER BY f.sort_seq, f.id
      """)
  List<QuotePriceDraftField> selectByDraft(
      @Param("priceDraftId") Long priceDraftId,
      @Param("businessUnitType") String businessUnitType,
      @Param("orgCode") String orgCode);

  @Delete("""
      DELETE f FROM lp_quote_price_draft_field f
      JOIN lp_quote_price_draft d ON d.id = f.price_draft_id
      WHERE f.price_draft_id = #{priceDraftId}
        AND d.business_unit_type = #{businessUnitType} AND d.org_code = #{orgCode}
        AND d.draft_status IN ('EDITING', 'VALIDATED', 'REJECTED')
      """)
  int deleteEditableByDraft(
      @Param("priceDraftId") Long priceDraftId,
      @Param("businessUnitType") String businessUnitType,
      @Param("orgCode") String orgCode);
}
