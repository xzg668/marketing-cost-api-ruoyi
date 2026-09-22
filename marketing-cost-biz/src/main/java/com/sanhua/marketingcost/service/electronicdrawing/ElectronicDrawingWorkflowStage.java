package com.sanhua.marketingcost.service.electronicdrawing;

import java.util.Set;

/** 电子图库取得、料号确认、组树和发布阶段，保存在产品及月份对应的 BOM 准备记录中。 */
public final class ElectronicDrawingWorkflowStage {

  public static final String QUERYING = "QUERYING_E_DRAWING";
  public static final String RETRY = "E_DRAWING_RETRY";
  public static final String NOT_FOUND = "E_DRAWING_NOT_FOUND";
  public static final String PARSING = "PARSING";
  public static final String MATCHING = "MATCHING";
  public static final String MAPPING_PENDING = "MAPPING_PENDING";
  public static final String MATCHED = "MATERIALS_MATCHED";
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
        || NOT_FOUND.equals(value) || MATCHED.equals(value) || COMPOSED.equals(value) || PUBLISHED.equals(value)
        || VALIDATION_FAILED.equals(value));
  }

  public static boolean isResumable(String value) {
    return value != null && RESUMABLE.contains(value);
  }
}
