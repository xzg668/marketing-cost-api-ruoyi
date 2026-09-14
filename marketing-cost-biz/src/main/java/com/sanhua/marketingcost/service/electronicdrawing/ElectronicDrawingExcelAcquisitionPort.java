package com.sanhua.marketingcost.service.electronicdrawing;

import java.time.LocalDateTime;

/** 业务层获取电子图库 Excel 的唯一端口；不暴露 HTTP 客户端或响应对象。 */
public interface ElectronicDrawingExcelAcquisitionPort {

  AcquiredExcel acquire(Query query);

  record Query(String drawingNo, String requestId) {}

  record AcquiredExcel(
      byte[] content,
      String fileName,
      String contentType,
      long fileSize,
      String sha256,
      String drawingNo,
      String requestId,
      String sourceSystem,
      LocalDateTime acquiredAt) {

    public AcquiredExcel {
      content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
      return content.clone();
    }
  }
}
