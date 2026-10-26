package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import com.sanhua.marketingcost.enums.QuoteCostRunStatus;
import com.sanhua.marketingcost.service.jjb.JjbExcelRenderer;
import com.sanhua.marketingcost.service.jjb.JjbReadService;
import java.io.IOException;
import java.io.OutputStream;
import org.springframework.stereotype.Service;

/**
 * Resolve an owned saved version before rendering, without selecting prices or calculating costs.
 */
@Service
public class JjbExportService {
  private final JjbReadService reader;
  private final JjbExcelRenderer renderer;

  public JjbExportService(JjbReadService reader, JjbExcelRenderer renderer) {
    this.reader = reader;
    this.renderer = renderer;
  }

  public int export(
      String oaNo,
      String productCode,
      Long itemId,
      Long versionId,
      String costRunNo,
      OutputStream output)
      throws IOException {
    return writeSaved(
        reader.resolve(oaNo, productCode, itemId, versionId, costRunNo).sheet(), output);
  }

  public int exportVersion(String oaNo, Long itemId, Long versionId, OutputStream output)
      throws IOException {
    return writeSaved(reader.read(oaNo, itemId, versionId).sheet(), output);
  }

  private int writeSaved(JjbReadModel sheet, OutputStream output) throws IOException {
    String status = sheet.identity().status();
    if (!QuoteCostRunStatus.isCurrentSuccess(status) && !QuoteCostRunStatus.isHistorical(status)) {
      throw new IllegalArgumentException("仅可导出已成功保存的成本版本");
    }
    return renderer.write(sheet, output);
  }
}
