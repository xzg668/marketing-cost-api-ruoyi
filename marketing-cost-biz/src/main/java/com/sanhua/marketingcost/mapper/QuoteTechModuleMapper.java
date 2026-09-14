package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteTechModuleMapper extends BaseMapper<QuoteTechModule> {

  @Insert("""
      INSERT INTO lp_quote_tech_module (
        product_id,module_type,required_flag,requirement_reason_code,requirement_reason,
        entry_mode,module_status,row_version)
      VALUES (
        #{module.productId},#{module.moduleType},#{module.requiredFlag},
        #{module.requirementReasonCode},#{module.requirementReason},#{module.entryMode},
        #{module.moduleStatus},#{module.rowVersion})
      ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)
      """)
  @Options(useGeneratedKeys = true, keyProperty = "module.id")
  int insertOrGet(@Param("module") QuoteTechModule module);

  @Select("""
      SELECT * FROM lp_quote_tech_module
       WHERE product_id=#{productId}
       ORDER BY FIELD(module_type,'PROFILE','PACKAGE','AUXILIARY','SALARY'),id
      """)
  List<QuoteTechModule> selectByProductId(@Param("productId") Long productId);

  @Select({
      "<script>",
      "SELECT * FROM lp_quote_tech_module",
      "WHERE product_id IN",
      "<foreach collection='productIds' item='id' open='(' separator=',' close=')'>",
      "#{id}",
      "</foreach>",
      "ORDER BY product_id,FIELD(module_type,'PROFILE','PACKAGE','AUXILIARY','SALARY'),id",
      "</script>"
  })
  List<QuoteTechModule> selectByProductIds(@Param("productIds") List<Long> productIds);

  @Select("""
      SELECT * FROM lp_quote_tech_module
       WHERE product_id=#{productId}
       ORDER BY FIELD(module_type,'PROFILE','PACKAGE','AUXILIARY','SALARY'),id
       FOR UPDATE
      """)
  List<QuoteTechModule> selectByProductIdForUpdate(@Param("productId") Long productId);

  @Select("""
      SELECT * FROM lp_quote_tech_module
       WHERE product_id=#{productId} AND module_type='PROFILE'
       LIMIT 1 FOR UPDATE
      """)
  QuoteTechModule selectProfileForUpdate(@Param("productId") Long productId);

  @Update("""
      UPDATE lp_quote_tech_module
         SET entry_mode = #{module.entryMode},
             module_status = #{module.moduleStatus},
             current_version_id = #{module.currentVersionId},
             reference_source_type = #{module.referenceSourceType},
             reference_source_id = #{module.referenceSourceId},
             reference_source_version = #{module.referenceSourceVersion},
             reference_fingerprint = #{module.referenceFingerprint},
             reference_snapshot_json = #{module.referenceSnapshotJson},
             referenced_at = #{module.referencedAt},
             last_validation_code = #{module.lastValidationCode},
             last_validation_message = #{module.lastValidationMessage},
             row_version = row_version + 1,
             updated_at = #{updatedAt}
       WHERE id = #{module.id}
         AND row_version = #{expectedVersion}
      """)
  int updateWithVersion(
      @Param("module") QuoteTechModule module,
      @Param("expectedVersion") int expectedVersion,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Update("""
      UPDATE lp_quote_tech_module
         SET entry_mode='MANUAL',
             module_status=CASE WHEN required_flag=1 THEN 'READY' ELSE 'NOT_REQUIRED' END,
             current_version_id=#{versionId},
             last_validation_code='PROFILE_COMPLETE',
             last_validation_message='产品型号、产品属性和新品标识完整',
             row_version=row_version+1,
             updated_at=#{updatedAt}
       WHERE id=#{moduleId} AND row_version=#{expectedVersion}
      """)
  int updateProfileReadyWithVersion(
      @Param("moduleId") Long moduleId,
      @Param("versionId") Long versionId,
      @Param("expectedVersion") int expectedVersion,
      @Param("updatedAt") LocalDateTime updatedAt);
}
