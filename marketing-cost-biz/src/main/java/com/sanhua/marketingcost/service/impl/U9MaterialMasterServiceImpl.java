package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.U9MaterialImportResponse;
import com.sanhua.marketingcost.dto.U9MaterialMasterIngestRequest;
import com.sanhua.marketingcost.dto.U9MaterialTemplateMappingItem;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.U9MaterialMasterSourceType;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import com.sanhua.marketingcost.service.MaterialMasterSyncService.BatchSummary;
import com.sanhua.marketingcost.service.U9MaterialMasterIngestService;
import com.sanhua.marketingcost.service.U9MaterialMasterService;
import java.io.InputStream;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class U9MaterialMasterServiceImpl implements U9MaterialMasterService {
  public static final String SOURCE_TYPE_EXCEL = U9MaterialMasterSourceType.EXCEL.getCode();

  private final MaterialMasterRawMapper rawMapper;
  private final MaterialMasterSyncService syncService;
  private final U9MaterialMasterIngestService ingestService;

  public U9MaterialMasterServiceImpl(
      MaterialMasterRawMapper rawMapper,
      MaterialMasterSyncService syncService,
      U9MaterialMasterIngestService ingestService) {
    this.rawMapper = rawMapper;
    this.syncService = syncService;
    this.ingestService = ingestService;
  }

  @Override
  public List<BatchSummary> listBatches() {
    return syncService.listBatchSummaries();
  }

  @Override
  public U9MaterialImportResponse importExcel(
      InputStream input, String sourceFileName, String importedBy) {
    return ingestService.ingest(
        U9MaterialMasterSourceType.EXCEL,
        new U9MaterialMasterIngestRequest(input, sourceFileName, importedBy, null));
  }

  @Override
  public Page<MaterialMasterRaw> pageRaw(
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
      int pageSize) {
    QueryWrapper<MaterialMasterRaw> query = new QueryWrapper<>();
    if (StringUtils.hasText(batch)) {
      query.eq("import_batch_id", batch.trim());
    } else {
      query.eq("active_flag", 1)
          .eq("source_type", SOURCE_TYPE_EXCEL)
          .apply("import_batch_id = (SELECT MAX(import_batch_id) FROM lp_material_master_raw WHERE active_flag = 1 AND source_type = 'EXCEL')");
    }
    like(query, "material_code", materialCode);
    like(query, "material_name", materialName);
    like(query, "material_spec", spec);
    like(query, "material_model", model);
    like(query, "drawing_no", drawingNo);
    like(query, "shape_attr", shapeAttr);
    like(query, "main_category_name", mainCategory);
    like(query, "cost_element", costElement);
    like(query, "production_division", bizUnit);
    like(query, "department_name", dept);
    query.orderByDesc("import_batch_id").orderByAsc("material_code");
    return rawMapper.selectPage(new Page<>(safePage(page), safeSize(pageSize)), query);
  }

  @Override
  public List<U9MaterialTemplateMappingItem> templateMapping() {
    return U9MaterialMasterFieldContract.fieldMappings().stream()
        .map(m -> new U9MaterialTemplateMappingItem(m.field(), m.header(), m.excelColumn()))
        .toList();
  }

  private static void like(QueryWrapper<MaterialMasterRaw> query, String column, String value) {
    if (StringUtils.hasText(value)) {
      query.like(column, value.trim());
    }
  }

  private static int safePage(int page) {
    return page < 1 ? 1 : page;
  }

  private static int safeSize(int pageSize) {
    if (pageSize < 1) {
      return 20;
    }
    return Math.min(pageSize, 200);
  }
}
