package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MakePartSpecImportRequest;
import com.sanhua.marketingcost.dto.MakePartSpecUpdateRequest;
import com.sanhua.marketingcost.entity.MakePartSpec;
import java.util.List;

/** 自制件工艺规格 service (V48 暴露 UI) */
public interface MakePartSpecService {

  Page<MakePartSpec> page(String materialCode, String period, int page, int pageSize);

  MakePartSpec create(MakePartSpecUpdateRequest request);

  MakePartSpec update(Long id, MakePartSpecUpdateRequest request);

  boolean delete(Long id);

  /** 批量导入：按 (material_code, period) 去重 upsert */
  List<MakePartSpec> importItems(MakePartSpecImportRequest request);
}
