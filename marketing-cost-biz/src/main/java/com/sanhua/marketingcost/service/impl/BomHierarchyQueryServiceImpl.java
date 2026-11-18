package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sanhua.marketingcost.dto.BomHierarchyTreeDto;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.service.BomHierarchyQueryService;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 只读消费 EasyData 已写入的 BOM 层级事实，不执行 Excel 导入或 Java 层级构建。 */
@Service
public class BomHierarchyQueryServiceImpl implements BomHierarchyQueryService {

  private final BomRawHierarchyMapper bomRawHierarchyMapper;
  private final PlateCommercialMakeBomExpansionService crossOrganizationExpansionService;

  public BomHierarchyQueryServiceImpl(
      BomRawHierarchyMapper bomRawHierarchyMapper,
      PlateCommercialMakeBomExpansionService crossOrganizationExpansionService) {
    this.bomRawHierarchyMapper = bomRawHierarchyMapper;
    this.crossOrganizationExpansionService = crossOrganizationExpansionService;
  }

  @Override
  @Transactional(readOnly = true)
  public BomHierarchyTreeDto getHierarchyTree(
      String topProductCode,
      String bomPurpose,
      LocalDate asOfDate,
      String sourceType,
      String priceOrgCode) {
    if (!StringUtils.hasText(topProductCode)) {
      throw new IllegalArgumentException("topProductCode 必填");
    }
    topProductCode = topProductCode.trim();
    LocalDate effectiveDate = asOfDate != null ? asOfDate : LocalDate.now();
    String normalizedSourceType = StringUtils.hasText(sourceType) ? sourceType : "U9";
    String normalizedPriceOrgCode = requiredPriceOrgCode(priceOrgCode);

    List<BomRawHierarchy> rows =
        bomRawHierarchyMapper.selectList(
            scope(normalizedPriceOrgCode, normalizedSourceType, bomPurpose, effectiveDate)
                .eq(BomRawHierarchy::getTopProductCode, topProductCode));
    rows = BomEffectiveTreePruner.prune(rows, topProductCode);
    if (rows.isEmpty()) {
      rows = findSubtree(topProductCode, bomPurpose, effectiveDate,
          normalizedSourceType, normalizedPriceOrgCode);
    }
    if (rows.isEmpty()) {
      return null;
    }
    PlateCommercialMakeBomExpansionService.ExpansionResult expansion =
        crossOrganizationExpansionService.expand(
            rows,
            topProductCode,
            effectiveDate,
            bomPurpose,
            normalizedSourceType,
            MaterialOrganization.fromPriceOrgCode(normalizedPriceOrgCode)
                .toQuoteDataOrganization());
    if (expansion.hasGaps()) {
      throw new IllegalStateException(
          "跨组织制造 BOM 展开失败：" + String.join("；", expansion.gaps()));
    }
    return assembleTree(expansion.rows(), topProductCode);
  }

  /** 从已有有效整树提取子树；同料多处出现不能拼接为一份 BOM。 */
  private List<BomRawHierarchy> findSubtree(
      String materialCode, String bomPurpose, LocalDate effectiveDate,
      String sourceType, String priceOrgCode) {
    List<BomRawHierarchy> matches = bomRawHierarchyMapper.selectList(
        scope(priceOrgCode, sourceType, bomPurpose, effectiveDate)
            .eq(BomRawHierarchy::getMaterialCode, materialCode)
            .orderByAsc(BomRawHierarchy::getTopProductCode)
            .orderByAsc(BomRawHierarchy::getPath));
    var contexts = new LinkedHashSet<TreeContext>();
    for (BomRawHierarchy match : matches) {
      if (StringUtils.hasText(match.getTopProductCode())) {
        contexts.add(new TreeContext(match.getTopProductCode(), match.getBomPurpose()));
      }
    }
    List<BomRawHierarchy> selected = List.of();
    for (TreeContext context : contexts) {
      var query = scope(priceOrgCode, sourceType, null, effectiveDate)
          .eq(BomRawHierarchy::getTopProductCode, context.topProductCode());
      if (context.bomPurpose() == null) {
        query.isNull(BomRawHierarchy::getBomPurpose);
      } else {
        query.eq(BomRawHierarchy::getBomPurpose, context.bomPurpose());
      }
      // 先检查原树祖先仍有效，不能把已失效上级下面的孤儿节点变成有效根。
      List<BomRawHierarchy> connected = BomEffectiveTreePruner.prune(
          bomRawHierarchyMapper.selectList(query), context.topProductCode());
      for (BomRawHierarchy root : connected) {
        if (!materialCode.equals(root.getMaterialCode())) {
          continue;
        }
        List<BomRawHierarchy> candidate = BomHierarchySubtree.extract(connected, root);
        if (selected.isEmpty()) {
          selected = candidate;
        } else if (!BomHierarchySubtree.sameBom(selected, candidate)) {
          throw new IllegalArgumentException("料号 " + materialCode
              + " 存在不同的下级 BOM，请指定 BOM 目的，或输入所属顶层料号查看");
        }
      }
    }
    return selected;
  }

  private LambdaQueryWrapper<BomRawHierarchy> scope(
      String priceOrgCode, String sourceType, String bomPurpose, LocalDate date) {
    return Wrappers.<BomRawHierarchy>lambdaQuery()
        .eq(BomRawHierarchy::getPriceOrgCode, priceOrgCode)
        .eq(BomRawHierarchy::getSourceType, sourceType)
        .eq(StringUtils.hasText(bomPurpose), BomRawHierarchy::getBomPurpose, bomPurpose)
        .le(BomRawHierarchy::getEffectiveFrom, date)
        .and(wrapper -> wrapper.ge(BomRawHierarchy::getEffectiveTo, date)
            .or().isNull(BomRawHierarchy::getEffectiveTo));
  }

  private record TreeContext(String topProductCode, String bomPurpose) {}

  private static String requiredPriceOrgCode(String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException("priceOrgCode 必须由上游显式传入");
    }
    return MaterialOrganization.fromPriceOrgCode(value).getPriceOrgCode();
  }

  private BomHierarchyTreeDto assembleTree(
      List<BomRawHierarchy> rows, String topProductCode) {
    Map<String, BomHierarchyTreeDto> byPath = new HashMap<>();
    for (BomRawHierarchy row : rows) {
      byPath.putIfAbsent(normalizePath(row.getPath()), toDto(row));
    }
    for (BomRawHierarchy row : rows) {
      if (row.getLevel() != null && row.getLevel() == 0) {
        continue;
      }
      BomHierarchyTreeDto child = byPath.get(normalizePath(row.getPath()));
      BomHierarchyTreeDto parent = byPath.get(parentPathOf(row.getPath()));
      if (parent != null && child != null && !parent.getChildren().contains(child)) {
        parent.getChildren().add(child);
      }
    }
    return byPath.get("/" + topProductCode + "/");
  }

  private BomHierarchyTreeDto toDto(BomRawHierarchy row) {
    BomHierarchyTreeDto dto = new BomHierarchyTreeDto();
    dto.setMaterialCode(row.getMaterialCode());
    dto.setMaterialName(row.getMaterialName());
    dto.setMaterialSpec(row.getMaterialSpec());
    dto.setLevel(row.getLevel());
    dto.setPath(row.getPath());
    dto.setQtyPerParent(row.getQtyPerParent());
    dto.setQtyPerTop(row.getQtyPerTop());
    dto.setShapeAttr(row.getShapeAttr());
    dto.setSourceCategory(row.getSourceCategory());
    dto.setBomPurpose(row.getBomPurpose());
    dto.setBomVersion(row.getBomVersion());
    dto.setSourceU9RowId(row.getSourceU9RowId());
    dto.setChildType(row.getChildType());
    dto.setAlternativeGroupKey(row.getAlternativeGroupKey());
    dto.setIsLeaf(row.getIsLeaf());
    dto.setEffectiveFrom(row.getEffectiveFrom());
    dto.setEffectiveTo(row.getEffectiveTo());
    return dto;
  }

  private static String normalizePath(String path) {
    if (!StringUtils.hasText(path)) {
      return null;
    }
    return path.endsWith("/") ? path : path + "/";
  }

  private static String parentPathOf(String path) {
    String normalized = normalizePath(path);
    if (normalized == null || normalized.length() < 2) {
      return null;
    }
    String trimmed = normalized.substring(0, normalized.length() - 1);
    int lastSlash = trimmed.lastIndexOf('/');
    if (lastSlash <= 0) {
      return null;
    }
    return trimmed.substring(0, lastSlash + 1);
  }
}
