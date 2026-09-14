package com.sanhua.marketingcost.service.electronicdrawing;

import java.util.List;

/** 电子图库 Excel 绑定、解析和源版本落库的稳定错误语义。 */
public class ElectronicDrawingSourceImportException extends RuntimeException {

  public static final String COMMAND_INVALID = "E_DRAWING_IMPORT_COMMAND_INVALID";
  public static final String TASK_NOT_FOUND = "E_DRAWING_IMPORT_TASK_NOT_FOUND";
  public static final String BINDING_INVALID = "E_DRAWING_IMPORT_BINDING_INVALID";
  public static final String DRAWING_MISMATCH = "E_DRAWING_IMPORT_DRAWING_MISMATCH";
  public static final String SOURCE_INVALID = "E_DRAWING_IMPORT_SOURCE_INVALID";
  public static final String PARSE_INVALID = "E_DRAWING_IMPORT_PARSE_INVALID";
  public static final String TASK_VERSION_CONFLICT = "E_DRAWING_IMPORT_TASK_VERSION_CONFLICT";

  private final String code;
  private final List<ElectronicDrawingExcelParseResult.Issue> parseIssues;

  public ElectronicDrawingSourceImportException(String code, String message) {
    this(code, message, List.of());
  }

  public ElectronicDrawingSourceImportException(
      String code, String message, List<ElectronicDrawingExcelParseResult.Issue> parseIssues) {
    super(message);
    this.code = code;
    this.parseIssues = parseIssues == null ? List.of() : List.copyOf(parseIssues);
  }

  public String getCode() {
    return code;
  }

  public List<ElectronicDrawingExcelParseResult.Issue> getParseIssues() {
    return parseIssues;
  }
}
