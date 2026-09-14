package com.sanhua.marketingcost.service.electronicdrawing;

/** 电子图库 Excel 获取的稳定错误语义；技术异常不会伪装成“未找到”。 */
public class ElectronicDrawingExcelAcquisitionException extends RuntimeException {

  public static final String REQUEST_INVALID = "E_DRAWING_REQUEST_INVALID";
  public static final String CONFIG_INVALID = "E_DRAWING_CONFIG_INVALID";
  public static final String BOM_NOT_FOUND = "E_DRAWING_BOM_NOT_FOUND";
  public static final String QUERY_RETRY = "E_DRAWING_QUERY_RETRY";
  public static final String ACCESS_DENIED = "E_DRAWING_ACCESS_DENIED";
  public static final String RESPONSE_INVALID = "E_DRAWING_RESPONSE_INVALID";
  public static final String FILE_INVALID = "E_DRAWING_FILE_INVALID";
  public static final String FILE_TOO_LARGE = "E_DRAWING_FILE_TOO_LARGE";

  private final String code;
  private final boolean retryable;

  public ElectronicDrawingExcelAcquisitionException(
      String code, boolean retryable, String message) {
    super(message);
    this.code = code;
    this.retryable = retryable;
  }

  public ElectronicDrawingExcelAcquisitionException(
      String code, boolean retryable, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.retryable = retryable;
  }

  public String getCode() {
    return code;
  }

  public boolean isRetryable() {
    return retryable;
  }
}
