package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteTechSalaryItemMapper extends BaseMapper<QuoteTechSalaryItem> {

  @Insert({
      "<script>",
      "INSERT INTO lp_quote_tech_salary_item (",
      "version_id,line_no,sort_seq,process_code,process_name,labor_type,working_hours,",
      "original_time_unit,standard_hours,standard_time_unit,conversion_factor,hourly_rate,",
      "wage_rate,rate_unit,person_coefficient,amount,source_reference_id,source_reference_version,source_snapshot_json,remark)",
      "SELECT candidate.* FROM (",
      "<foreach collection='items' item='item' separator=' UNION ALL '>",
      "SELECT #{versionId} AS version_id,#{item.lineNo} AS line_no,",
      "#{item.sortSeq} AS sort_seq,#{item.processCode} AS process_code,",
      "#{item.processName} AS process_name,#{item.laborType} AS labor_type,",
      "#{item.workingHours} AS working_hours,#{item.originalTimeUnit} AS original_time_unit,",
      "#{item.standardHours} AS standard_hours,",
      "#{item.standardTimeUnit} AS standard_time_unit,",
      "#{item.conversionFactor} AS conversion_factor,#{item.hourlyRate} AS hourly_rate,",
      "COALESCE(#{item.wageRate},#{item.hourlyRate}) AS wage_rate,",
      "COALESCE(#{item.rateUnit},'元/小时') AS rate_unit,",
      "COALESCE(#{item.personCoefficient},1) AS person_coefficient,",
      "#{item.amount} AS amount,#{item.sourceReferenceId} AS source_reference_id,",
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
      @Param("items") List<QuoteTechSalaryItem> items);

  @Select("""
      SELECT * FROM lp_quote_tech_salary_item
       WHERE version_id=#{versionId}
       ORDER BY sort_seq,line_no,id
      """)
  List<QuoteTechSalaryItem> selectByVersionId(@Param("versionId") Long versionId);

  @Select("SELECT COUNT(*) FROM lp_quote_tech_salary_item WHERE version_id=#{versionId}")
  int countByVersionId(@Param("versionId") Long versionId);

  @Delete("""
      DELETE item FROM lp_quote_tech_salary_item item
      INNER JOIN lp_quote_tech_data_version version ON version.id=item.version_id
       WHERE item.version_id=#{versionId} AND version.version_status='DRAFT'
      """)
  int deleteAllIfDraft(@Param("versionId") Long versionId);

  @Update("""
      UPDATE lp_quote_tech_salary_item item
      INNER JOIN lp_quote_tech_data_version version ON version.id=item.version_id
         SET item.line_no=#{item.lineNo}, item.sort_seq=#{item.sortSeq},
             item.process_code=#{item.processCode}, item.process_name=#{item.processName},
             item.labor_type=#{item.laborType}, item.working_hours=#{item.workingHours},
             item.original_time_unit=#{item.originalTimeUnit},
             item.standard_hours=#{item.standardHours},
             item.standard_time_unit=#{item.standardTimeUnit},
             item.conversion_factor=#{item.conversionFactor}, item.hourly_rate=#{item.hourlyRate},
             item.wage_rate=#{item.wageRate}, item.rate_unit=#{item.rateUnit},
             item.person_coefficient=#{item.personCoefficient},
             item.amount=#{item.amount}, item.source_reference_id=#{item.sourceReferenceId},
             item.source_reference_version=#{item.sourceReferenceVersion},
             item.source_snapshot_json=#{item.sourceSnapshotJson}, item.remark=#{item.remark},
             item.updated_at=CURRENT_TIMESTAMP
       WHERE item.id=#{item.id} AND item.version_id=#{item.versionId}
         AND version.version_status='DRAFT'
      """)
  int updateIfDraft(@Param("item") QuoteTechSalaryItem item);

  @Delete("""
      DELETE item FROM lp_quote_tech_salary_item item
      INNER JOIN lp_quote_tech_data_version version ON version.id=item.version_id
       WHERE item.id=#{itemId} AND item.version_id=#{versionId}
         AND version.version_status='DRAFT'
      """)
  int deleteIfDraft(@Param("versionId") Long versionId, @Param("itemId") Long itemId);
}
