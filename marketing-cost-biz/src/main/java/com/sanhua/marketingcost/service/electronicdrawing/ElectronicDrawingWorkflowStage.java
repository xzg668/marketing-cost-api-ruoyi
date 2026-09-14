package com.sanhua.marketingcost.service.electronicdrawing;

import java.util.Set;

/** 复用产品任务校验状态字段保存电子图库业务阶段，不为页面文案新增独立状态表。 */
public final class ElectronicDrawingWorkflowStage {

  public static final String QUERYING = "QUERYING_E_DRAWING";
  public static final String RETRY = "E_DRAWING_RETRY";
  public static final String NOT_FOUND = "E_DRAWING_NOT_FOUND";
  public static final String PARSING = "PARSING";
  public static final String MATCHING = "MATCHING";
  public static final String MAPPING_PENDING = "MAPPING_PENDING";
  public static final String COMPOSING = "COMPOSING";
  public static final String COMPOSED = "E_DRAWING_COMPOSED";
  public static final String PUBLISHED = "E_DRAWING_PUBLISHED";
  public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

  private static final Set<String> ACTIVE = Set.of(
      QUERYING, RETRY, PARSING, MATCHING, MAPPING_PENDING, COMPOSING);
  private static final Set<String> RESUMABLE = Set.of(
      QUERYING, RETRY, NOT_FOUND, PARSING, MATCHING, COMPOSING, COMPOSED, VALIDATION_FAILED);

  private ElectronicDrawingWorkflowStage() {}

  public static boolean isElectronicDrawing(String value) {
    return value != null && (ACTIVE.contains(value)
        || NOT_FOUND.equals(value) || COMPOSED.equals(value) || PUBLISHED.equals(value)
        || VALIDATION_FAILED.equals(value));
  }

  public static boolean isResumable(String value) {
    return value != null && RESUMABLE.contains(value);
  }
}
