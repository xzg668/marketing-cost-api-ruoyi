package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.U9MaterialImportResponse;
import com.sanhua.marketingcost.dto.U9MaterialTemplateMappingItem;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingMaterialOptionResponse;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import java.io.InputStream;
import java.util.List;

public interface U9MaterialMasterService {

  U9MaterialImportResponse importExcel(
      InputStream input, String sourceFileName, String importedBy, String organizationCode);

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
      String organizationCode,
      int page,
      int pageSize);

  List<QuoteCostingMaterialOptionResponse> options(String keyword, String organizationCode, int limit);

  List<U9MaterialTemplateMappingItem> templateMapping();
}
