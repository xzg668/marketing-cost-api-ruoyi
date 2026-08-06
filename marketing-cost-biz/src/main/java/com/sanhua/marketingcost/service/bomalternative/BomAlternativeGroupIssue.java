package com.sanhua.marketingcost.service.bomalternative;

import java.time.LocalDate;

/** 可直接定位到 U9 来源业务行的替代组结构问题。 */
public record BomAlternativeGroupIssue(
    String code,
    String message,
    String alternativeGroupKey,
    String topProductCode,
    String parentMaterialNo,
    String parentPath,
    String bomPurpose,
    String bomVersion,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    Integer childSeq,
    String processSeq,
    String candidateMaterialCode,
    String rawChildType,
    Long rawHierarchyNodeId,
    Long sourceU9RowId,
    String sourceLineKey) {

  public static final String ALT_STANDARD_MISSING = "ALT_STANDARD_MISSING";
  public static final String ALT_MULTIPLE_STANDARD = "ALT_MULTIPLE_STANDARD";
  public static final String ALT_DUPLICATE_CANDIDATE = "ALT_DUPLICATE_CANDIDATE";
  public static final String ALT_UNKNOWN_CHILD_TYPE = "ALT_UNKNOWN_CHILD_TYPE";
  public static final String ALT_GROUP_KEY_MISSING = "ALT_GROUP_KEY_MISSING";
  public static final String ALT_MEMBER_SCOPE_MISMATCH = "ALT_MEMBER_SCOPE_MISMATCH";
}
