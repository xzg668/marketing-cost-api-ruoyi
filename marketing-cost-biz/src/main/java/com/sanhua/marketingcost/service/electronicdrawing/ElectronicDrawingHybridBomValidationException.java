package com.sanhua.marketingcost.service.electronicdrawing;

/** 纯组树校验失败，尚未替换数据库明细；编排器仍需保存缺口及等待状态。 */
public class ElectronicDrawingHybridBomValidationException extends ElectronicDrawingHybridBomException {
  public ElectronicDrawingHybridBomValidationException(String code, String message) {
    super(code, message);
  }
}
