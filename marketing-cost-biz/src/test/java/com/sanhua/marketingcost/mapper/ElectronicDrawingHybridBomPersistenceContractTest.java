package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("电子图库混合 BOM 持久化 SQL 契约")
class ElectronicDrawingHybridBomPersistenceContractTest {

  @Test
  @DisplayName("替换明细只允许命中电子图库 DRAFT 版本")
  void deleteOnlyTargetsElectronicDrawingDraft() throws Exception {
    Method method = QuoteBomSupplementDetailMapper.class.getMethod(
        "deleteElectronicDrawingHybridDraft", Long.class);
    String sql = method.getAnnotation(Delete.class).value()[0];

    assertThat(sql).contains(
        "INNER JOIN lp_quote_bom_supplement_version",
        "detail.supplement_version_id = #{supplementVersionId}",
        "version.version_status = 'DRAFT'",
        "version.bom_source = 'ELECTRONIC_DRAWING_EXCEL'");
  }

  @Test
  @DisplayName("批量写入保存完整树、两类来源指针和显式业务时间")
  void batchInsertPersistsTreeAndTraceabilityFields() throws Exception {
    Method method = QuoteBomSupplementDetailMapper.class.getMethod(
        "insertElectronicDrawingHybridBatch", List.class);
    String sql = String.join("\n", method.getAnnotation(Insert.class).value());

    assertThat(sql).contains(
        "supplement_version_id,preparation_id",
        "qty_per_parent,qty_per_top,parent_base_qty",
        "source_raw_hierarchy_id,source_u9_bom_id,node_source_type,source_electronic_node_id",
        "mapping_status,manual_flag,remark,created_at,updated_at",
        "<foreach collection='details'",
        "#{row.sourceElectronicNodeId}",
        "#{row.createdAt}",
        "#{row.updatedAt}");
  }

  @Test
  @DisplayName("指纹更新受版本状态、来源、组织和旧指纹乐观锁共同保护")
  void fingerprintUpdateIsOrganizationScopedAndOptimistic() throws Exception {
    Method method = QuoteBomSupplementVersionMapper.class.getMethod(
        "updateElectronicDrawingCompositionFingerprint",
        Long.class, String.class, String.class, String.class, LocalDateTime.class);
    String sql = method.getAnnotation(Update.class).value()[0];

    assertThat(sql).contains(
        "composition_fingerprint = #{compositionFingerprint}",
        "updated_at = #{updatedAt}",
        "version_status = 'DRAFT'",
        "bom_source = 'ELECTRONIC_DRAWING_EXCEL'",
        "material_org_code = #{materialOrgCode}",
        "composition_fingerprint IS NULL",
        "composition_fingerprint = #{expectedFingerprint}");
  }

  @Test
  @DisplayName("分批保存料号只推进乐观锁，不清除电子图库待匹配阶段")
  void partialMaterialResolutionKeepsWorkflowStage() throws Exception {
    Method method = QuoteBomPreparationRecordMapper.class.getMethod(
        "touchElectronicWorkflow",
        Long.class, int.class, Long.class, LocalDateTime.class);
    String sql = method.getAnnotation(Update.class).value()[0];

    assertThat(sql).contains(
        "electronic_workflow_version=electronic_workflow_version+1",
        "electronic_source_version_id=#{sourceVersionId}",
        "active_flag=1");
    assertThat(sql).doesNotContain(
        "electronic_workflow_stage=",
        "preparation_status=");
  }

  @Test
  @DisplayName("混合 BOM 继续复用既有 supplement detail 表，不引入第二张业务表")
  void mapperUsesExistingSupplementDetailEntity() {
    assertThat(QuoteBomSupplementDetailMapper.class.getGenericInterfaces())
        .anySatisfy(type -> assertThat(type.getTypeName())
            .contains(QuoteBomSupplementDetail.class.getName()));
  }
}
