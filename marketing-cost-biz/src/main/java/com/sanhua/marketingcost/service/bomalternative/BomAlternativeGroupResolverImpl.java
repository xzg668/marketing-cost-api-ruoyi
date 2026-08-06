package com.sanhua.marketingcost.service.bomalternative;

import static com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupIssue.ALT_DUPLICATE_CANDIDATE;
import static com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupIssue.ALT_GROUP_KEY_MISSING;
import static com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupIssue.ALT_MEMBER_SCOPE_MISMATCH;
import static com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupIssue.ALT_MULTIPLE_STANDARD;
import static com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupIssue.ALT_STANDARD_MISSING;
import static com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupIssue.ALT_UNKNOWN_CHILD_TYPE;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** 替代组只读解析器；仅识别和校验，不负责报价默认选择。 */
@Component
public final class BomAlternativeGroupResolverImpl
    implements BomAlternativeGroupResolver {

  private static final Comparator<BomRawHierarchy> ROW_ORDER =
      Comparator.comparingInt(BomAlternativeGroupResolverImpl::typeOrder)
          .thenComparing(
              row -> normalized(row.getMaterialCode()),
              Comparator.naturalOrder())
          .thenComparing(
              row -> normalized(row.getSourceLineKey()),
              Comparator.naturalOrder())
          .thenComparing(
              BomRawHierarchy::getId,
              Comparator.nullsLast(Comparator.naturalOrder()));

  private static final Comparator<BomAlternativeCandidate> CANDIDATE_ORDER =
      Comparator.comparingInt(
              (BomAlternativeCandidate candidate) -> candidate.childType().ordinal())
          .thenComparing(
              candidate -> normalized(candidate.materialCode()),
              Comparator.naturalOrder())
          .thenComparing(
              BomAlternativeCandidate::rawHierarchyNodeId,
              Comparator.nullsLast(Comparator.naturalOrder()));

  private final BomAlternativeGroupKeyGenerator keyGenerator;

  public BomAlternativeGroupResolverImpl(
      BomAlternativeGroupKeyGenerator keyGenerator) {
    this.keyGenerator = Objects.requireNonNull(keyGenerator, "keyGenerator");
  }

  @Override
  public BomAlternativeGroupResolution resolve(List<BomRawHierarchy> rows) {
    List<BomAlternativeGroup> groups = new ArrayList<>();
    List<BomAlternativeGroupIssue> issues = new ArrayList<>();
    Map<String, List<BomRawHierarchy>> rowsByGroupKey = new TreeMap<>();

    if (rows == null || rows.isEmpty()) {
      return new BomAlternativeGroupResolution(groups, issues);
    }

    rows.stream()
        .filter(Objects::nonNull)
        .sorted(ROW_ORDER)
        .forEach(
            row -> {
              String groupKey = groupKey(row);
              BomChildType type = sourceType(row, false);
              if (groupKey.isEmpty()) {
                if (type == BomChildType.ALTERNATIVE) {
                  issues.add(
                      issue(
                          ALT_GROUP_KEY_MISSING,
                          "U9替代件缺少替代组键，请使用最新U9批次重建正式BOM层级",
                          row,
                          null));
                }
                return;
              }
              rowsByGroupKey
                  .computeIfAbsent(groupKey, ignored -> new ArrayList<>())
                  .add(row);
            });

    rowsByGroupKey.forEach(
        (groupKey, members) -> resolveGroup(groupKey, members, groups, issues));
    return new BomAlternativeGroupResolution(groups, issues);
  }

  private void resolveGroup(
      String groupKey,
      List<BomRawHierarchy> inputMembers,
      List<BomAlternativeGroup> groups,
      List<BomAlternativeGroupIssue> issues) {
    List<BomRawHierarchy> members =
        inputMembers.stream().sorted(ROW_ORDER).toList();
    boolean containsAlternative =
        members.stream()
            .anyMatch(row -> sourceType(row, false) == BomChildType.ALTERNATIVE);
    if (!containsAlternative) {
      return;
    }

    BomRawHierarchy reference = members.get(0);
    GroupScope referenceScope = scope(reference);
    List<BomAlternativeGroupIssue> groupIssues = new ArrayList<>();

    for (BomRawHierarchy member : members) {
      GroupScope memberScope = scope(member);
      if (!referenceScope.equals(memberScope)) {
        groupIssues.add(
            issue(
                ALT_MEMBER_SCOPE_MISMATCH,
                "同一替代组键混入不同业务位置；期望"
                    + referenceScope.describe()
                    + "，实际"
                    + memberScope.describe(),
                member,
                groupKey));
      }
    }

    List<TypedRow> typedRows =
        members.stream()
            .map(row -> new TypedRow(row, sourceType(row, true)))
            .toList();
    typedRows.stream()
        .filter(
            typed ->
                typed.type() != BomChildType.STANDARD
                    && typed.type() != BomChildType.ALTERNATIVE)
        .forEach(
            typed ->
                groupIssues.add(
                    issue(
                        ALT_UNKNOWN_CHILD_TYPE,
                        "替代组成员的U9子项类型无法确定为标准或替代："
                            + display(typed.row().getChildType()),
                        typed.row(),
                        groupKey)));

    List<TypedRow> standards =
        typedRows.stream()
            .filter(typed -> typed.type() == BomChildType.STANDARD)
            .toList();
    if (standards.isEmpty()) {
      groupIssues.add(
          issue(
              ALT_STANDARD_MISSING,
              "U9替代组没有明确标准件，系统不会任选替代件作为默认值",
              firstAlternativeOrFirst(typedRows),
              groupKey));
    } else if (standards.size() > 1) {
      groupIssues.add(
          issue(
              ALT_MULTIPLE_STANDARD,
              "U9替代组存在多个标准件，系统不会把第一条当作默认值",
              standards.get(1).row(),
              groupKey));
    }

    Map<String, BomRawHierarchy> firstCandidateByMaterial = new HashMap<>();
    for (TypedRow typed : typedRows) {
      if (typed.type() != BomChildType.STANDARD
          && typed.type() != BomChildType.ALTERNATIVE) {
        continue;
      }
      String materialKey = normalized(typed.row().getMaterialCode());
      BomRawHierarchy first =
          firstCandidateByMaterial.putIfAbsent(materialKey, typed.row());
      if (first != null) {
        groupIssues.add(
            issue(
                ALT_DUPLICATE_CANDIDATE,
                "同一替代组出现重复候选料号"
                    + display(typed.row().getMaterialCode())
                    + "；来源业务行"
                    + display(first.getSourceLineKey())
                    + "与"
                    + display(typed.row().getSourceLineKey())
                    + "均被保留用于排查，未静默合并",
                typed.row(),
                groupKey));
      }
    }

    if (!groupIssues.isEmpty()) {
      issues.addAll(groupIssues);
      return;
    }

    BomAlternativeGroupIdentity identity =
        new BomAlternativeGroupIdentity(
            reference.getPriceOrgCode(),
            reference.getTopProductCode(),
            keyGenerator.parentPathFingerprint(referenceScope.parentPath()),
            reference.getParentCode(),
            reference.getBomPurpose(),
            reference.getBomVersion(),
            reference.getEffectiveFrom(),
            reference.getEffectiveTo(),
            reference.getSortSeq(),
            reference.getProcessSeq());
    List<BomAlternativeCandidate> candidates =
        typedRows.stream()
            .map(this::candidate)
            .sorted(CANDIDATE_ORDER)
            .toList();
    groups.add(new BomAlternativeGroup(identity, groupKey, candidates));
  }

  private BomAlternativeCandidate candidate(TypedRow typed) {
    BomRawHierarchy row = typed.row();
    return new BomAlternativeCandidate(
        row.getId(),
        row.getMaterialCode(),
        row.getMaterialName(),
        row.getMaterialSpec(),
        typed.type(),
        row.getQtyPerParent(),
        row.getPath(),
        row.getSourceImportBatchId(),
        row.getBuildBatchId());
  }

  private static BomRawHierarchy firstAlternativeOrFirst(
      List<TypedRow> typedRows) {
    return typedRows.stream()
        .filter(typed -> typed.type() == BomChildType.ALTERNATIVE)
        .map(TypedRow::row)
        .findFirst()
        .orElseGet(() -> typedRows.get(0).row());
  }

  private static BomAlternativeGroupIssue issue(
      String code,
      String message,
      BomRawHierarchy row,
      String groupKey) {
    return new BomAlternativeGroupIssue(
        code,
        message,
        groupKey,
        row.getTopProductCode(),
        row.getParentCode(),
        parentPath(row.getPath()),
        row.getBomPurpose(),
        row.getBomVersion(),
        row.getEffectiveFrom(),
        row.getEffectiveTo(),
        row.getSortSeq(),
        row.getProcessSeq(),
        row.getMaterialCode(),
        row.getChildType(),
        row.getId(),
        row.getSourceU9RowId(),
        row.getSourceLineKey());
  }

  private static GroupScope scope(BomRawHierarchy row) {
    return new GroupScope(
        normalized(row.getPriceOrgCode()),
        normalized(row.getTopProductCode()),
        normalized(row.getParentCode()),
        parentPath(row.getPath()),
        normalized(row.getBomPurpose()),
        normalized(row.getBomVersion()),
        row.getEffectiveFrom(),
        row.getEffectiveTo(),
        row.getSortSeq(),
        normalized(row.getProcessSeq()));
  }

  private static String parentPath(String childPath) {
    String path = normalizePath(childPath);
    if (path.isEmpty() || "/".equals(path)) {
      return path;
    }
    String withoutTrailingSlash = path.substring(0, path.length() - 1);
    int lastSlash = withoutTrailingSlash.lastIndexOf('/');
    if (lastSlash < 0) {
      return "/";
    }
    return withoutTrailingSlash.substring(0, lastSlash + 1);
  }

  private static String normalizePath(String value) {
    String normalized = normalized(value).replace('\\', '/');
    if (normalized.isEmpty()) {
      return "";
    }
    normalized = normalized.replaceAll("/+", "/");
    if (!normalized.startsWith("/")) {
      normalized = "/" + normalized;
    }
    if (!normalized.endsWith("/")) {
      normalized = normalized + "/";
    }
    return normalized;
  }

  private static int typeOrder(BomRawHierarchy row) {
    return sourceType(row, false).ordinal();
  }

  private static BomChildType sourceType(
      BomRawHierarchy row, boolean alternativeGroupContext) {
    return BomChildType.fromSource(
        row == null ? null : row.getChildType(), alternativeGroupContext);
  }

  private static String groupKey(BomRawHierarchy row) {
    return row.getAlternativeGroupKey() == null
        ? ""
        : row.getAlternativeGroupKey().strip();
  }

  private static String normalized(String value) {
    return BomChildType.normalize(value);
  }

  private static String display(String value) {
    return value == null || value.isBlank() ? "（空）" : value.strip();
  }

  private record TypedRow(BomRawHierarchy row, BomChildType type) {
  }

  private record GroupScope(
      String priceOrgCode,
      String topProductCode,
      String parentMaterialNo,
      String parentPath,
      String bomPurpose,
      String bomVersion,
      java.time.LocalDate effectiveFrom,
      java.time.LocalDate effectiveTo,
      Integer childSeq,
      String processSeq) {

    String describe() {
      return "[组织="
          + priceOrgCode
          + ", 顶层="
          + topProductCode
          + ", 父件="
          + parentMaterialNo
          + ", 父路径="
          + parentPath
          + ", 目的="
          + bomPurpose
          + ", 版本="
          + bomVersion
          + ", 生效="
          + effectiveFrom
          + ".."
          + effectiveTo
          + ", 项次="
          + childSeq
          + ", 工序="
          + processSeq
          + "]";
    }
  }
}
