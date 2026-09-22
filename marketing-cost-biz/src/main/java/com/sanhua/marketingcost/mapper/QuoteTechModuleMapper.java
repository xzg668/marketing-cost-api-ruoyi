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

  // 和模块查询使用同一 Mapper，更新后清掉当前事务的 MyBatis 查询缓存，供 OA 分派读取最新缺口。
  @Update("""
      UPDATE lp_quote_tech_module SET required_flag=#{requirement.required},
        requirement_reason_code=#{requirement.reasonCode},requirement_reason=#{requirement.reason},
        source_availability=#{requirement.availability},source_reference=#{requirement.sourceReference},
        source_checked_at=#{requirement.checkedAt},module_status=#{status},
        last_validation_code='SOURCE_RECHECKED',last_validation_message='来源已复查，已有内容保留；需要补录时请重新确认',
        row_version=row_version+1,updated_at=NOW(6) WHERE id=#{moduleId} AND row_version=#{expectedVersion}
      """)
  int refreshRequirement(@Param("moduleId") Long moduleId, @Param("expectedVersion") Integer expectedVersion,
      @Param("requirement") com.sanhua.marketingcost.service.technicaldata.TechnicalDataModuleRequirement requirement,
      @Param("status") String status);

  @Select("""
      SELECT m.* FROM lp_quote_tech_module m JOIN lp_quote_tech_product p ON p.id=m.product_id
      WHERE p.task_id=#{taskId} AND p.active_flag=1 ORDER BY m.product_id,m.id
      """)
  List<QuoteTechModule> selectByTaskId(@Param("taskId") Long taskId);

  @Insert("""
      INSERT INTO lp_quote_tech_module (
        product_id,module_type,required_flag,requirement_reason_code,requirement_reason,
        entry_mode,module_status,row_version,source_availability,source_reference,source_checked_at)
      VALUES (
        #{module.productId},#{module.moduleType},#{module.requiredFlag},
        #{module.requirementReasonCode},#{module.requirementReason},#{module.entryMode},
        #{module.moduleStatus},#{module.rowVersion},#{module.sourceAvailability},
        #{module.sourceReference},#{module.sourceCheckedAt})
      ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)
      """)
  @Options(useGeneratedKeys = true, keyProperty = "module.id")
  int insertOrGet(@Param("module") QuoteTechModule module);

  @Select("""
      SELECT * FROM lp_quote_tech_module
       WHERE product_id=#{productId}
       ORDER BY FIELD(module_type,'PROFILE','DRAWING_BOM','MANUFACTURING','PACKAGE','AUXILIARY','SOLDER','SALARY','NET_LOSS','PRICE'),id
      """)
  List<QuoteTechModule> selectByProductId(@Param("productId") Long productId);

  @Select({
      "<script>",
      "SELECT * FROM lp_quote_tech_module",
      "WHERE product_id IN",
      "<foreach collection='productIds' item='id' open='(' separator=',' close=')'>",
      "#{id}",
      "</foreach>",
      "ORDER BY product_id,FIELD(module_type,'PROFILE','DRAWING_BOM','MANUFACTURING','PACKAGE','AUXILIARY','SOLDER','SALARY','NET_LOSS','PRICE'),id",
      "</script>"
  })
  List<QuoteTechModule> selectByProductIds(@Param("productIds") List<Long> productIds);

  @Select("""
      SELECT * FROM lp_quote_tech_module
       WHERE product_id=#{productId}
       ORDER BY FIELD(module_type,'PROFILE','DRAWING_BOM','MANUFACTURING','PACKAGE','AUXILIARY','SOLDER','SALARY','NET_LOSS','PRICE'),id
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
             module_status=CASE WHEN source_availability IN ('UNCONFIRMED','ERROR') THEN 'EDITING'
               WHEN required_flag=1 THEN 'READY' ELSE 'NOT_REQUIRED' END,
             current_version_id=#{versionId},
             last_validation_code='PROFILE_COMPLETE',
             last_validation_message='产品属性、新增费用选项和三项单件费用完整',
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
