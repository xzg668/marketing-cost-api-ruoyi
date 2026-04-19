package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MaterialMasterImportRequest;
import com.sanhua.marketingcost.dto.MaterialMasterRequest;
import com.sanhua.marketingcost.entity.MaterialMaster;
import java.util.List;

public interface MaterialMasterService {
  Page<MaterialMaster> page(String materialCode, String materialName, String itemSpec,
      String itemModel, String drawingNo, String shapeAttr, String material, String bizUnit,
      String productionDept, String productionWorkshop, int page, int pageSize);

  MaterialMaster create(MaterialMasterRequest request);

  MaterialMaster update(Long id, MaterialMasterRequest request);

  boolean delete(Long id);

  List<MaterialMaster> importItems(MaterialMasterImportRequest request);
}
