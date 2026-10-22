package com.sanhua.marketingcost.service.electronicdrawing;

/** 新来源使用 PDF；Excel 标识仅识别已经保存的历史，不再作为获取协议。 */
public final class ElectronicDrawingSourceKind {
  public static final String PDF = "ELECTRONIC_DRAWING_PDF";
  public static final String HISTORICAL_EXCEL = "ELECTRONIC_DRAWING_EXCEL";

  private ElectronicDrawingSourceKind() {}

  public static boolean isDrawing(String value) {
    return PDF.equals(value) || HISTORICAL_EXCEL.equals(value);
  }
}
