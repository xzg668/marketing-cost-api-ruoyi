package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** T15：U9 staging 表 mapper。料品主档按组织 + 料号维护当前有效行。 */
@Mapper
public interface MaterialMasterRawMapper extends BaseMapper<MaterialMasterRaw> {

  /** 查 staging 指定组织的最近一次导入流水，仅用于追溯展示；无数据返 null。 */
  @Select({
      "<script>",
      "SELECT MAX(import_batch_id)",
      "FROM lp_material_master_raw",
      "WHERE active_flag = 1",
      "  AND organization_code = #{organizationCode}",
      "<if test='sourceType != null and sourceType != \"\"'>",
      "  AND source_type = #{sourceType}",
      "</if>",
      "</script>"
  })
  String selectLatestActiveBatchId(
      @Param("sourceType") String sourceType,
      @Param("organizationCode") String organizationCode);

  /** 兼容旧调用；默认读取商用组织。 */
  default String selectLatestActiveBatchId(String sourceType) {
    return selectLatestActiveBatchId(sourceType, MaterialOrganization.COMMERCIAL.getCode());
  }

  /** 兼容旧调用；默认读取商用组织最近一次导入流水。 */
  default String selectLatestBatchId() {
    return selectLatestActiveBatchId(null, MaterialOrganization.COMMERCIAL.getCode());
  }

  /**
   * 按当前有效组织读取指定料号。
   *
   * <p>raw 表按 organization_code + material_code 保持当前态唯一；导入流水只用于追溯本行最近一次来源。
   */
  @Select({
      "<script>",
      "SELECT *",
      "FROM lp_material_master_raw",
      "WHERE active_flag = 1",
      "  AND organization_code = #{organizationCode}",
      "<if test='sourceType != null and sourceType != \"\"'>",
      "  AND source_type = #{sourceType}",
      "</if>",
      "  AND material_code IN",
      "  <foreach collection='codes' item='code' open='(' separator=',' close=')'>",
      "    #{code}",
      "  </foreach>",
      "</script>"
  })
  List<MaterialMasterRaw> selectByLatestBatchAndCodes(
      @Param("codes") Collection<String> codes,
      @Param("sourceType") String sourceType,
      @Param("organizationCode") String organizationCode);

  /** 兼容旧调用；默认读取商用组织。 */
  default List<MaterialMasterRaw> selectByLatestBatchAndCodes(
      Collection<String> codes, String sourceType) {
    return selectByLatestBatchAndCodes(codes, sourceType, MaterialOrganization.COMMERCIAL.getCode());
  }

  /**
   * 报价核算 BOM 子件选择器：按当前有效组织，从料号、品名、型号做统一模糊搜索。
   *
   * <p>选择器必须读取 raw 当前有效行，才能带出 U9 单位、材质、形态属性等未必已同步到主档的字段。
   */
  @Select({
      "<script>",
      "SELECT *",
      "FROM lp_material_master_raw",
      "WHERE active_flag = 1",
      "  AND organization_code = #{organizationCode}",
      "<if test='sourceType != null and sourceType != \"\"'>",
      "  AND source_type = #{sourceType}",
      "</if>",
      "<if test='keyword != null and keyword != \"\"'>",
      "  AND (",
      "    material_code LIKE CONCAT('%', #{keyword}, '%')",
      "    OR material_name LIKE CONCAT('%', #{keyword}, '%')",
      "    OR material_model LIKE CONCAT('%', #{keyword}, '%')",
      "  )",
      "</if>",
      "ORDER BY material_code ASC",
      "LIMIT #{limit}",
      "</script>"
  })
  List<MaterialMasterRaw> selectOptionsByLatestBatchKeyword(
      @Param("keyword") String keyword,
      @Param("sourceType") String sourceType,
      @Param("organizationCode") String organizationCode,
      @Param("limit") int limit);

  /** 兼容旧调用；默认读取商用组织。 */
  default List<MaterialMasterRaw> selectOptionsByLatestBatchKeyword(
      String keyword, String sourceType, int limit) {
    return selectOptionsByLatestBatchKeyword(
        keyword, sourceType, MaterialOrganization.COMMERCIAL.getCode(), limit);
  }

  /**
   * 按当前有效组织读取包装组件父件候选。
   *
   * <p>包装组件父件常是 U9 虚拟件，不一定同步到主表；必须查 raw，且要限定组织。
   */
  @Select({
      "<script>",
      "SELECT *",
      "FROM lp_material_master_raw",
      "WHERE active_flag = 1",
      "  AND organization_code = #{organizationCode}",
      "<if test='sourceType != null and sourceType != \"\"'>",
      "  AND source_type = #{sourceType}",
      "</if>",
      "  AND main_category_name = #{mainCategoryName}",
      "</script>"
  })
  List<MaterialMasterRaw> selectPackageComponentParentsByLatestBatch(
      @Param("mainCategoryName") String mainCategoryName,
      @Param("sourceType") String sourceType,
      @Param("organizationCode") String organizationCode);

  /** 兼容旧调用；默认读取商用组织。 */
  default List<MaterialMasterRaw> selectPackageComponentParentsByLatestBatch(
      String mainCategoryName, String sourceType) {
    return selectPackageComponentParentsByLatestBatch(
        mainCategoryName, sourceType, MaterialOrganization.COMMERCIAL.getCode());
  }
}
