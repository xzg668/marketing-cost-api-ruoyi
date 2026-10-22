package com.sanhua.marketingcost.service.electronicdrawing;

/** 电子图库结果绑定、导入和源版本落库的稳定错误语义。 */
public class ElectronicDrawingSourceImportException extends RuntimeException {

  public static final String COMMAND_INVALID = "E_DRAWING_IMPORT_COMMAND_INVALID";
  public static final String TASK_NOT_FOUND = "E_DRAWING_IMPORT_TASK_NOT_FOUND";
  public static final String BINDING_INVALID = "E_DRAWING_IMPORT_BINDING_INVALID";
  public static final String DRAWING_MISMATCH = "E_DRAWING_IMPORT_DRAWING_MISMATCH";
  public static final String SOURCE_INVALID = "E_DRAWING_IMPORT_SOURCE_INVALID";
  public static final String PARSE_INVALID = "E_DRAWING_IMPORT_PARSE_INVALID";
  public static final String TASK_VERSION_CONFLICT = "E_DRAWING_IMPORT_TASK_VERSION_CONFLICT";

  private final String code;

  public ElectronicDrawingSourceImportException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
