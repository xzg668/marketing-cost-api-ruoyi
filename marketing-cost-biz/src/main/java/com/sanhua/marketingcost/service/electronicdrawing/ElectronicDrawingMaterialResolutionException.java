package com.sanhua.marketingcost.service.electronicdrawing;

/** 电子图库料号解析的可识别业务错误。 */
public class ElectronicDrawingMaterialResolutionException extends RuntimeException {
  public static final String COMMAND_INVALID = "COMMAND_INVALID";
  public static final String TASK_NOT_FOUND = "TASK_NOT_FOUND";
  public static final String SOURCE_VERSION_INVALID = "SOURCE_VERSION_INVALID";
  public static final String SOURCE_NODE_INVALID = "SOURCE_NODE_INVALID";
  public static final String MATERIAL_NOT_FOUND = "MATERIAL_NOT_FOUND";
  public static final String TASK_VERSION_CONFLICT = "TASK_VERSION_CONFLICT";

  private final String code;

  public ElectronicDrawingMaterialResolutionException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
