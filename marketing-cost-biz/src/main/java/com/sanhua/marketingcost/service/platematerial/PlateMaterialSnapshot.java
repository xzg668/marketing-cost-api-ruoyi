package com.sanhua.marketingcost.service.platematerial;

import java.util.List;
import java.util.Objects;

/** 已保存版本采用的材质和来源；SQL NULL 单独表示历史未采集。 */
public record PlateMaterialSnapshot(
    PlateMaterialMode mode, PlateMaterial material, Source source, String revision) {

  public PlateMaterialSnapshot {
    Objects.requireNonNull(mode, "板片材质模式不能为空");
    Objects.requireNonNull(source, "板片材质来源不能为空");
    if (revision == null || revision.isBlank()) {
      throw new IllegalArgumentException("板片材质判定版本不能为空");
    }
    if ((mode == PlateMaterialMode.HIDDEN) != (material == null)) {
      throw new IllegalArgumentException("隐藏材质应为空，适用的已保存版本必须有材质");
    }
    boolean mapped = source.mappingId() != null && source.mappingVersion() != null;
    if (mode == PlateMaterialMode.CONFIG_LOCKED && !mapped
        || mode != PlateMaterialMode.CONFIG_LOCKED
            && (source.mappingId() != null || source.mappingVersion() != null)) {
      throw new IllegalArgumentException("板片材质配置来源与判定模式不符");
    }
    if (mode == PlateMaterialMode.MANUAL_REQUIRED && source.materialMasterIds().isEmpty()) {
      throw new IllegalArgumentException("手选材质必须保留板换档案依据");
    }
    if (mode != PlateMaterialMode.HIDDEN && source.normalizedModel() == null) {
      throw new IllegalArgumentException("适用板片材质的版本必须有来源型号");
    }
  }

  public record Source(
      String businessUnitType, String normalizedModel, String materialOrganizationCode,
      Long mappingId, Integer mappingVersion, List<Long> materialMasterIds) {
    public Source {
      if (businessUnitType == null || businessUnitType.isBlank()) {
        throw new IllegalArgumentException("板片材质业务单元不能为空");
      }
      if (!Objects.equals(normalizedModel, PlateMaterialModel.normalize(normalizedModel))) {
        throw new IllegalArgumentException("板片材质型号必须已规范化");
      }
      if (mappingId != null && mappingId <= 0 || mappingVersion != null && mappingVersion < 0) {
        throw new IllegalArgumentException("板片材质配置版本无效");
      }
      materialMasterIds = materialMasterIds == null ? List.of()
          : materialMasterIds.stream().distinct().sorted().toList();
      if (materialMasterIds.stream().anyMatch(id -> id <= 0)) {
        throw new IllegalArgumentException("板片材质档案依据无效");
      }
    }
  }
}
