package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteTechAuxItemMapper extends BaseMapper<QuoteTechAuxItem> {

  @Insert({
      "<script>",
      "INSERT INTO lp_quote_tech_aux_item (",
      "version_id,line_no,sort_seq,subject_code,subject_name,auxiliary_material_no,",
      "auxiliary_name,auxiliary_spec,pricing_method,",
      "quantity,original_unit,standard_quantity,standard_unit,conversion_factor,",
      "reference_unit_price,price_unit,loss_rate,amount,source_reference_id,source_reference_version,",
      "source_snapshot_json,remark)",
      "SELECT candidate.* FROM (",
      "<foreach collection='items' item='item' separator=' UNION ALL '>",
      "SELECT #{versionId} AS version_id,#{item.lineNo} AS line_no,",
      "#{item.sortSeq} AS sort_seq,#{item.subjectCode} AS subject_code,",
      "#{item.subjectName} AS subject_name,#{item.auxiliaryMaterialNo} AS auxiliary_material_no,",
      "#{item.auxiliaryName} AS auxiliary_name,#{item.auxiliarySpec} AS auxiliary_spec,",
      "#{item.pricingMethod} AS pricing_method,#{item.quantity} AS quantity,",
      "#{item.originalUnit} AS original_unit,",
      "#{item.standardQuantity} AS standard_quantity,#{item.standardUnit} AS standard_unit,",
      "#{item.conversionFactor} AS conversion_factor,",
      "#{item.referenceUnitPrice} AS reference_unit_price,#{item.priceUnit} AS price_unit,",
      "#{item.lossRate} AS loss_rate,#{item.amount} AS amount,",
      "#{item.sourceReferenceId} AS source_reference_id,",
      "#{item.sourceReferenceVersion} AS source_reference_version,",
      "#{item.sourceSnapshotJson} AS source_snapshot_json,#{item.remark} AS remark",
      "</foreach>",
      ") candidate",
      "WHERE EXISTS (SELECT 1 FROM lp_quote_tech_data_version version",
      "WHERE version.id=#{versionId} AND version.version_status='DRAFT')",
      "</script>"
  })
  int insertBatchIfDraft(
      @Param("versionId") Long versionId,
      @Param("items") List<QuoteTechAuxItem> items);

  @Select("""
      SELECT * FROM lp_quote_tech_aux_item
       WHERE version_id=#{versionId}
       ORDER BY sort_seq,line_no,id
      """)
  List<QuoteTechAuxItem> selectByVersionId(@Param("versionId") Long versionId);

  @Select("SELECT COUNT(*) FROM lp_quote_tech_aux_item WHERE version_id=#{versionId}")
  int countByVersionId(@Param("versionId") Long versionId);

  @Delete("""
      DELETE item FROM lp_quote_tech_aux_item item
      INNER JOIN lp_quote_tech_data_version version ON version.id=item.version_id
       WHERE item.version_id=#{versionId} AND version.version_status='DRAFT'
      """)
  int deleteAllIfDraft(@Param("versionId") Long versionId);

  @Update("""
      UPDATE lp_quote_tech_aux_item item
      INNER JOIN lp_quote_tech_data_version version ON version.id=item.version_id
         SET item.line_no=#{item.lineNo}, item.sort_seq=#{item.sortSeq},
             item.subject_code=#{item.subjectCode}, item.subject_name=#{item.subjectName},
             item.auxiliary_material_no=#{item.auxiliaryMaterialNo},
             item.auxiliary_name=#{item.auxiliaryName}, item.auxiliary_spec=#{item.auxiliarySpec},
             item.pricing_method=#{item.pricingMethod},
             item.quantity=#{item.quantity}, item.original_unit=#{item.originalUnit},
             item.standard_quantity=#{item.standardQuantity}, item.standard_unit=#{item.standardUnit},
             item.conversion_factor=#{item.conversionFactor},
             item.reference_unit_price=#{item.referenceUnitPrice}, item.price_unit=#{item.priceUnit},
             item.loss_rate=#{item.lossRate}, item.amount=#{item.amount},
             item.source_reference_id=#{item.sourceReferenceId},
             item.source_reference_version=#{item.sourceReferenceVersion},
             item.source_snapshot_json=#{item.sourceSnapshotJson}, item.remark=#{item.remark},
             item.updated_at=CURRENT_TIMESTAMP
       WHERE item.id=#{item.id} AND item.version_id=#{item.versionId}
         AND version.version_status='DRAFT'
      """)
  int updateIfDraft(@Param("item") QuoteTechAuxItem item);

  @Delete("""
      DELETE item FROM lp_quote_tech_aux_item item
      INNER JOIN lp_quote_tech_data_version version ON version.id=item.version_id
       WHERE item.id=#{itemId} AND item.version_id=#{versionId}
         AND version.version_status='DRAFT'
      """)
  int deleteIfDraft(@Param("versionId") Long versionId, @Param("itemId") Long itemId);
}
