package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.U9BomByproductImportResponse;
import com.sanhua.marketingcost.dto.U9MaterialTemplateMappingItem;
import com.sanhua.marketingcost.entity.U9BomByproductMaster;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

public interface U9BomByproductMasterService {

  U9BomByproductImportResponse importExcel(InputStream input, String sourceFileName, String importedBy);

  Page<U9BomByproductMaster> page(
      String parentMaterialNo,
      String parentMaterialName,
      String byproductMaterialNo,
      String byproductMaterialName,
      String bomPurpose,
      String status,
      LocalDate asOfDate,
      int page,
      int pageSize);

  List<U9MaterialTemplateMappingItem> templateMapping();
}
