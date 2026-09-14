package com.sanhua.marketingcost.service.electronicdrawing;

/** 混合 BOM 合成和持久化的稳定业务错误。 */
public class ElectronicDrawingHybridBomException extends RuntimeException {
  public static final String COMMAND_INVALID = "COMMAND_INVALID";
  public static final String MAPPING_INCOMPLETE = "MAPPING_INCOMPLETE";
  public static final String STRUCTURE_INVALID = "STRUCTURE_INVALID";
  public static final String BOM_GAP = "BOM_GAP";
  public static final String U9_QUERY_BLOCKED = "U9_QUERY_BLOCKED";
  public static final String SOURCE_VERSION_INVALID = "SOURCE_VERSION_INVALID";
  public static final String TASK_VERSION_CONFLICT = "TASK_VERSION_CONFLICT";
  public static final String PERSISTENCE_INVALID = "PERSISTENCE_INVALID";

  private final String code;

  public ElectronicDrawingHybridBomException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
