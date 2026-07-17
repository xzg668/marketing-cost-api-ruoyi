package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 板换 BOM 的商用制造件接管展开。
 *
 * <p>板换 BOM 仍是主树；仅当板换节点是采购件、同料号在商用组织是制造件时，才把该采购叶子
 * 切换为商用制造件，并把商用组织当前有效的制造 BOM 嫁接到原路径下。普通板换制造件和两边均为
 * 采购件的节点不受影响。
 */
@Service
public class PlateCommercialMakeBomExpansionService {

  static final String SHAPE_PURCHASE = "采购件";
  static final String SHAPE_MAKE = "制造件";
  static final String DEFAULT_BOM_PURPOSE = "主制造";
  static final String DEFAULT_SOURCE_TYPE = "U9";

  private final BomRawHierarchyMapper bomRawHierarchyMapper;
  private final MaterialMasterRawMapper materialMasterRawMapper;

  public PlateCommercialMakeBomExpansionService(
      BomRawHierarchyMapper bomRawHierarchyMapper,
      MaterialMasterRawMapper materialMasterRawMapper) {
    this.bomRawHierarchyMapper = bomRawHierarchyMapper;
    this.materialMasterRawMapper = materialMasterRawMapper;
  }

  public ExpansionResult expand(
      List<BomRawHierarchy> sourceRows,
      String topProductCode,
      LocalDate effectiveDate,
      String requestedBomPurpose,
      String sourceType,
      QuoteDataOrganization quoteDataOrganization) {
    List<BomRawHierarchy> baseRows = copyRows(sourceRows);
    QuoteDataOrganization organization =
        MaterialOrganization.normalizeQuoteDataOrganization(quoteDataOrganization);
    if (!MaterialOrganization.PLATE.getPriceOrgCode().equals(organization.priceOrgCode())
        || baseRows.isEmpty()) {
      return new ExpansionResult(baseRows, Map.of(), Map.of(), List.of());
    }

    LinkedHashSet<String> baseCodes =
        baseRows.stream()
            .map(BomRawHierarchy::getMaterialCode)
            .map(PlateCommercialMakeBomExpansionService::trimToNull)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Map<String, MaterialMasterRaw> plateMasters =
        selectMasters(baseCodes, MaterialOrganization.PLATE.getCode());
    Map<String, MaterialMasterRaw> commercialMasters =
        new HashMap<>(selectMasters(baseCodes, MaterialOrganization.COMMERCIAL.getCode()));

    List<Candidate> candidates = new ArrayList<>();
    for (BomRawHierarchy row : baseRows) {
      String code = trimToNull(row.getMaterialCode());
      String path = normalizePath(row.getPath());
      MaterialMasterRaw plateMaster = plateMasters.get(code);
      MaterialMasterRaw commercialMaster = commercialMasters.get(code);
      if (code == null
          || path == null
          || !SHAPE_PURCHASE.equals(firstText(row.getShapeAttr(), shapeOf(plateMaster)))
          || !SHAPE_MAKE.equals(shapeOf(commercialMaster))) {
        continue;
      }
      candidates.add(
          new Candidate(
              row,
              path,
              firstText(requestedBomPurpose, row.getBomPurpose(), DEFAULT_BOM_PURPOSE),
              commercialMaster));
    }
    if (candidates.isEmpty()) {
      return new ExpansionResult(
          sorted(baseRows), Map.copyOf(plateMasters), Map.copyOf(commercialMasters), List.of());
    }

    LocalDate asOfDate = effectiveDate == null ? LocalDate.now() : effectiveDate;
    String effectiveSourceType = firstText(sourceType, DEFAULT_SOURCE_TYPE);
    Set<String> candidateCodes =
        candidates.stream()
            .map(candidate -> candidate.row().getMaterialCode())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> purposes =
        candidates.stream()
            .map(Candidate::bomPurpose)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    List<BomRawHierarchy> commercialRows =
        bomRawHierarchyMapper.selectList(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(
                    BomRawHierarchy::getPriceOrgCode,
                    MaterialOrganization.COMMERCIAL.getPriceOrgCode())
                .in(BomRawHierarchy::getTopProductCode, candidateCodes)
                .eq(BomRawHierarchy::getSourceType, effectiveSourceType)
                .in(BomRawHierarchy::getBomPurpose, purposes)
                .le(BomRawHierarchy::getEffectiveFrom, asOfDate)
                .and(
                    wrapper ->
                        wrapper
                            .isNull(BomRawHierarchy::getEffectiveTo)
                            .or()
                            .ge(BomRawHierarchy::getEffectiveTo, asOfDate))
                .orderByAsc(BomRawHierarchy::getTopProductCode)
                .orderByAsc(BomRawHierarchy::getLevel)
                .orderByAsc(BomRawHierarchy::getPath)
                .orderByAsc(BomRawHierarchy::getSortSeq)
                .orderByAsc(BomRawHierarchy::getId));
    Map<SubtreeKey, List<BomRawHierarchy>> subtreeByKey =
        effectiveSubtrees(commercialRows == null ? List.of() : commercialRows);

    LinkedHashSet<String> commercialCodes = new LinkedHashSet<>(baseCodes);
    for (List<BomRawHierarchy> subtree : subtreeByKey.values()) {
      subtree.stream()
          .map(BomRawHierarchy::getMaterialCode)
          .map(PlateCommercialMakeBomExpansionService::trimToNull)
          .filter(java.util.Objects::nonNull)
          .forEach(commercialCodes::add);
    }
    commercialMasters.putAll(
        selectMasters(commercialCodes, MaterialOrganization.COMMERCIAL.getCode()));

    Map<String, CandidateExpansion> expansionByPath = new LinkedHashMap<>();
    List<String> gaps = new ArrayList<>();
    for (Candidate candidate : candidates) {
      String code = trimToNull(candidate.row().getMaterialCode());
      List<BomRawHierarchy> subtree =
          subtreeByKey.getOrDefault(new SubtreeKey(code, candidate.bomPurpose()), List.of());
      if (subtree.isEmpty()) {
        gaps.add(
            "料号 "
                + code
                + " 在商用组织中为制造件，但截至 "
                + asOfDate
                + " 未找到有效的 "
                + candidate.bomPurpose()
                + " BOM");
        continue;
      }
      String cycleMaterialCode = firstAncestorCycle(candidate.path(), subtree);
      if (cycleMaterialCode != null) {
        gaps.add(
            "料号 "
                + code
                + " 的商用制造 BOM 与板换上游形成环，重复料号 "
                + cycleMaterialCode
                + "，路径 "
                + candidate.path());
        continue;
      }
      expansionByPath.put(
          candidate.path(), new CandidateExpansion(candidate, subtree));
    }

    List<BomRawHierarchy> expanded = new ArrayList<>();
    for (BomRawHierarchy row : baseRows) {
      String path = normalizePath(row.getPath());
      if (isDescendantOfAny(path, expansionByPath.keySet())) {
        continue;
      }
      CandidateExpansion expansion = expansionByPath.get(path);
      if (expansion == null) {
        expanded.add(copyOf(row));
        continue;
      }
      BomRawHierarchy commercialParent = copyOf(row);
      applyCommercialParent(commercialParent, expansion.candidate().commercialMaster());
      expanded.add(commercialParent);
      for (BomRawHierarchy commercialChild : expansion.subtree()) {
        expanded.add(graft(commercialParent, commercialChild, topProductCode));
      }
    }

    return new ExpansionResult(
        sorted(expanded),
        Map.copyOf(plateMasters),
        Map.copyOf(commercialMasters),
        List.copyOf(gaps));
  }

  private Map<SubtreeKey, List<BomRawHierarchy>> effectiveSubtrees(
      List<BomRawHierarchy> rows) {
    Map<SubtreeKey, List<BomRawHierarchy>> grouped =
        rows.stream()
            .filter(row -> trimToNull(row.getTopProductCode()) != null)
            .collect(
                Collectors.groupingBy(
                    row ->
                        new SubtreeKey(
                            trimToNull(row.getTopProductCode()),
                            firstText(row.getBomPurpose(), DEFAULT_BOM_PURPOSE)),
                    LinkedHashMap::new,
                    Collectors.toList()));
    Map<SubtreeKey, List<BomRawHierarchy>> result = new LinkedHashMap<>();
    for (Map.Entry<SubtreeKey, List<BomRawHierarchy>> entry : grouped.entrySet()) {
      List<BomRawHierarchy> connected = connectedEffectiveDescendants(entry.getValue(), entry.getKey().code());
      if (!connected.isEmpty()) {
        result.put(entry.getKey(), connected);
      }
    }
    return result;
  }

  private List<BomRawHierarchy> connectedEffectiveDescendants(
      List<BomRawHierarchy> rows, String topCode) {
    Map<String, BomRawHierarchy> newestByPath = new LinkedHashMap<>();
    for (BomRawHierarchy row : rows) {
      String path = normalizePath(row.getPath());
      if (path == null) {
        continue;
      }
      newestByPath.merge(path, row, this::newerVersion);
    }
    List<BomRawHierarchy> sortedRows = sorted(newestByPath.values());
    String rootPath = "/" + topCode + "/";
    Set<String> connectedPaths = new LinkedHashSet<>();
    connectedPaths.add(rootPath);
    List<BomRawHierarchy> connected = new ArrayList<>();
    for (BomRawHierarchy row : sortedRows) {
      String path = normalizePath(row.getPath());
      if (rootPath.equals(path)) {
        continue;
      }
      if (path != null
          && path.startsWith(rootPath)
          && connectedPaths.contains(parentPath(path))) {
        connectedPaths.add(path);
        connected.add(copyOf(row));
      }
    }
    return connected;
  }

  private BomRawHierarchy newerVersion(BomRawHierarchy left, BomRawHierarchy right) {
    LocalDate leftDate = left.getEffectiveFrom() == null ? LocalDate.MIN : left.getEffectiveFrom();
    LocalDate rightDate = right.getEffectiveFrom() == null ? LocalDate.MIN : right.getEffectiveFrom();
    int compared = leftDate.compareTo(rightDate);
    if (compared < 0) {
      return right;
    }
    if (compared > 0) {
      return left;
    }
    long leftId = left.getId() == null ? Long.MIN_VALUE : left.getId();
    long rightId = right.getId() == null ? Long.MIN_VALUE : right.getId();
    return rightId > leftId ? right : left;
  }

  private void applyCommercialParent(BomRawHierarchy row, MaterialMasterRaw master) {
    row.setPriceOrgCode(MaterialOrganization.COMMERCIAL.getPriceOrgCode());
    row.setMaterialName(firstText(master == null ? null : master.getMaterialName(), row.getMaterialName()));
    row.setMaterialSpec(firstText(master == null ? null : master.getMaterialSpec(), row.getMaterialSpec()));
    row.setShapeAttr(SHAPE_MAKE);
    row.setMaterialCategory1(
        firstText(master == null ? null : master.getMainCategoryCode(), row.getMaterialCategory1()));
    row.setMaterialCategory2(
        firstText(master == null ? null : master.getMainCategoryName(), row.getMaterialCategory2()));
    row.setIsLeaf(0);
  }

  private BomRawHierarchy graft(
      BomRawHierarchy parent, BomRawHierarchy commercialChild, String originalTopProductCode) {
    BomRawHierarchy grafted = copyOf(commercialChild);
    String commercialTop = trimToNull(commercialChild.getTopProductCode());
    grafted.setTopProductCode(firstText(originalTopProductCode, parent.getTopProductCode()));
    grafted.setLevel(
        safeInt(parent.getLevel()) + Math.max(1, safeInt(commercialChild.getLevel())));
    grafted.setPath(graftPath(parent.getPath(), commercialTop, commercialChild.getPath()));
    grafted.setQtyPerTop(multiply(parent.getQtyPerTop(), commercialChild.getQtyPerTop()));
    grafted.setPriceOrgCode(MaterialOrganization.COMMERCIAL.getPriceOrgCode());
    return grafted;
  }

  private String graftPath(String parentPath, String commercialTop, String childPath) {
    String normalizedParent = normalizePath(parentPath);
    String normalizedChild = normalizePath(childPath);
    String commercialRoot = "/" + commercialTop + "/";
    if (normalizedParent == null
        || normalizedChild == null
        || !normalizedChild.startsWith(commercialRoot)) {
      throw new IllegalArgumentException("商用 BOM 路径无法嫁接: " + childPath);
    }
    String suffix = normalizedChild.substring(commercialRoot.length());
    return normalizedParent + suffix;
  }

  private String firstAncestorCycle(String candidatePath, List<BomRawHierarchy> subtree) {
    Set<String> ancestorCodes = materialCodesInPath(candidatePath);
    for (BomRawHierarchy row : subtree) {
      String code = trimToNull(row.getMaterialCode());
      if (code != null && ancestorCodes.contains(code)) {
        return code;
      }
    }
    return null;
  }

  private Set<String> materialCodesInPath(String path) {
    Set<String> codes = new LinkedHashSet<>();
    String normalized = normalizePath(path);
    if (normalized == null) {
      return codes;
    }
    for (String segment : normalized.split("/")) {
      String value = trimToNull(segment);
      if (value == null) {
        continue;
      }
      int discriminator = value.indexOf('@');
      codes.add(discriminator < 0 ? value : value.substring(0, discriminator));
    }
    return codes;
  }

  private boolean isDescendantOfAny(String path, Collection<String> parentPaths) {
    if (path == null) {
      return false;
    }
    for (String parentPath : parentPaths) {
      if (!path.equals(parentPath) && path.startsWith(parentPath)) {
        return true;
      }
    }
    return false;
  }

  private Map<String, MaterialMasterRaw> selectMasters(
      Collection<String> materialCodes, String organizationCode) {
    if (materialCodes == null || materialCodes.isEmpty()) {
      return Map.of();
    }
    List<MaterialMasterRaw> rows =
        materialMasterRawMapper.selectByLatestBatchAndCodes(
            materialCodes, null, organizationCode);
    if (rows == null || rows.isEmpty()) {
      return Map.of();
    }
    return rows.stream()
        .filter(row -> trimToNull(row.getMaterialCode()) != null)
        .collect(
            Collectors.toMap(
                row -> trimToNull(row.getMaterialCode()),
                Function.identity(),
                (first, ignored) -> first,
                LinkedHashMap::new));
  }

  private List<BomRawHierarchy> copyRows(List<BomRawHierarchy> rows) {
    if (rows == null || rows.isEmpty()) {
      return new ArrayList<>();
    }
    return rows.stream().map(this::copyOf).collect(Collectors.toCollection(ArrayList::new));
  }

  private BomRawHierarchy copyOf(BomRawHierarchy source) {
    BomRawHierarchy copy = new BomRawHierarchy();
    BeanUtils.copyProperties(source, copy);
    return copy;
  }

  private List<BomRawHierarchy> sorted(Collection<BomRawHierarchy> rows) {
    return rows.stream().sorted(rowComparator()).toList();
  }

  private Comparator<BomRawHierarchy> rowComparator() {
    return Comparator
        .comparing(
            (BomRawHierarchy row) ->
                row.getLevel() == null ? Integer.MAX_VALUE : row.getLevel())
        .thenComparing(row -> normalizePath(row.getPath()) == null ? "" : normalizePath(row.getPath()))
        .thenComparing(
            row -> row.getSortSeq() == null ? Integer.MAX_VALUE : row.getSortSeq())
        .thenComparing(row -> row.getId() == null ? Long.MAX_VALUE : row.getId());
  }

  private static BigDecimal multiply(BigDecimal first, BigDecimal second) {
    if (first == null) {
      return second;
    }
    if (second == null) {
      return first;
    }
    return first.multiply(second);
  }

  private static int safeInt(Integer value) {
    return value == null ? 0 : value;
  }

  private static String shapeOf(MaterialMasterRaw master) {
    return master == null ? null : trimToNull(master.getShapeAttr());
  }

  private static String parentPath(String path) {
    String normalized = normalizePath(path);
    if (normalized == null) {
      return null;
    }
    int parentEnd = normalized.lastIndexOf('/', normalized.length() - 2);
    return parentEnd <= 0 ? null : normalized.substring(0, parentEnd + 1);
  }

  private static String normalizePath(String path) {
    String value = trimToNull(path);
    return value == null ? null : (value.endsWith("/") ? value : value + "/");
  }

  private static String firstText(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      String normalized = trimToNull(value);
      if (normalized != null) {
        return normalized;
      }
    }
    return null;
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  public record ExpansionResult(
      List<BomRawHierarchy> rows,
      Map<String, MaterialMasterRaw> plateMasters,
      Map<String, MaterialMasterRaw> commercialMasters,
      List<String> gaps) {

    public boolean hasGaps() {
      return gaps != null && !gaps.isEmpty();
    }
  }

  private record Candidate(
      BomRawHierarchy row,
      String path,
      String bomPurpose,
      MaterialMasterRaw commercialMaster) {}

  private record CandidateExpansion(Candidate candidate, List<BomRawHierarchy> subtree) {}

  private record SubtreeKey(String code, String bomPurpose) {}
}
