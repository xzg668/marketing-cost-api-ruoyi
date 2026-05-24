package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.U9MaterialImportResponse;
import com.sanhua.marketingcost.dto.U9MaterialTemplateMappingItem;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.service.MaterialMasterSyncService.BatchSummary;
import java.io.InputStream;
import java.util.List;

public interface U9MaterialMasterService {

  List<BatchSummary> listBatches();

  U9MaterialImportResponse importExcel(InputStream input, String sourceFileName, String importedBy);

  Page<MaterialMasterRaw> pageRaw(
      String materialCode,
      String materialName,
      String spec,
      String model,
      String drawingNo,
      String shapeAttr,
      String mainCategory,
      String costElement,
      String bizUnit,
      String dept,
      String batch,
      int page,
      int pageSize);

  List<U9MaterialTemplateMappingItem> templateMapping();
}
