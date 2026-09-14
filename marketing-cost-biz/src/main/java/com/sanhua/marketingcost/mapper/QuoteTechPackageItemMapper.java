package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteTechPackageItemMapper extends BaseMapper<QuoteTechPackageItem> {

  @Insert({
      "<script>",
      "INSERT INTO lp_quote_tech_package_item (",
      "version_id,line_no,sort_seq,component_material_no,component_name,component_spec,",
      "quantity,original_unit,standard_quantity,standard_unit,conversion_factor,",
      "price_basis_type,reference_unit_price,amount,source_reference_id,",
      "source_reference_version,source_snapshot_json,remark)",
      "SELECT candidate.* FROM (",
      "<foreach collection='items' item='item' separator=' UNION ALL '>",
      "SELECT #{versionId} AS version_id,#{item.lineNo} AS line_no,",
      "#{item.sortSeq} AS sort_seq,#{item.componentMaterialNo} AS component_material_no,",
      "#{item.componentName} AS component_name,#{item.componentSpec} AS component_spec,",
      "#{item.quantity} AS quantity,#{item.originalUnit} AS original_unit,",
      "#{item.standardQuantity} AS standard_quantity,#{item.standardUnit} AS standard_unit,",
      "#{item.conversionFactor} AS conversion_factor,",
      "#{item.priceBasisType} AS price_basis_type,",
      "#{item.referenceUnitPrice} AS reference_unit_price,#{item.amount} AS amount,",
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
      @Param("items") List<QuoteTechPackageItem> items);

  @Select("""
      SELECT * FROM lp_quote_tech_package_item
       WHERE version_id=#{versionId}
       ORDER BY sort_seq,line_no,id
      """)
  List<QuoteTechPackageItem> selectByVersionId(@Param("versionId") Long versionId);

  @Select("SELECT COUNT(*) FROM lp_quote_tech_package_item WHERE version_id=#{versionId}")
  int countByVersionId(@Param("versionId") Long versionId);

  @Delete("""
      DELETE item FROM lp_quote_tech_package_item item
      INNER JOIN lp_quote_tech_data_version version ON version.id=item.version_id
       WHERE item.version_id=#{versionId} AND version.version_status='DRAFT'
      """)
  int deleteAllIfDraft(@Param("versionId") Long versionId);

  @Update("""
      UPDATE lp_quote_tech_package_item item
      INNER JOIN lp_quote_tech_data_version version ON version.id=item.version_id
         SET item.line_no=#{item.lineNo}, item.sort_seq=#{item.sortSeq},
             item.component_material_no=#{item.componentMaterialNo},
             item.component_name=#{item.componentName}, item.component_spec=#{item.componentSpec},
             item.quantity=#{item.quantity}, item.original_unit=#{item.originalUnit},
             item.standard_quantity=#{item.standardQuantity}, item.standard_unit=#{item.standardUnit},
             item.conversion_factor=#{item.conversionFactor},
             item.price_basis_type=#{item.priceBasisType},
             item.reference_unit_price=#{item.referenceUnitPrice}, item.amount=#{item.amount},
             item.source_reference_id=#{item.sourceReferenceId},
             item.source_reference_version=#{item.sourceReferenceVersion},
             item.source_snapshot_json=#{item.sourceSnapshotJson}, item.remark=#{item.remark},
             item.updated_at=CURRENT_TIMESTAMP
       WHERE item.id=#{item.id} AND item.version_id=#{item.versionId}
         AND version.version_status='DRAFT'
      """)
  int updateIfDraft(@Param("item") QuoteTechPackageItem item);

  @Delete("""
      DELETE item FROM lp_quote_tech_package_item item
      INNER JOIN lp_quote_tech_data_version version ON version.id=item.version_id
       WHERE item.id=#{itemId} AND item.version_id=#{versionId}
         AND version.version_status='DRAFT'
      """)
  int deleteIfDraft(@Param("versionId") Long versionId, @Param("itemId") Long itemId);
}
