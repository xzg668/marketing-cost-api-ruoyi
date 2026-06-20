package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.U9MaterialImportResponse;
import com.sanhua.marketingcost.dto.U9MaterialMasterIngestRequest;
import com.sanhua.marketingcost.dto.U9MaterialTemplateMappingItem;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingMaterialOptionResponse;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.U9MaterialMasterSourceType;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
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
  private final U9MaterialMasterIngestService ingestService;

  public U9MaterialMasterServiceImpl(
      MaterialMasterRawMapper rawMapper,
      U9MaterialMasterIngestService ingestService) {
    this.rawMapper = rawMapper;
    this.ingestService = ingestService;
  }

  @Override
  public U9MaterialImportResponse importExcel(
      InputStream input, String sourceFileName, String importedBy, String organizationCode) {
    String org = MaterialOrganization.normalize(organizationCode);
    return ingestService.ingest(
        U9MaterialMasterSourceType.EXCEL,
        new U9MaterialMasterIngestRequest(input, sourceFileName, importedBy, org, null));
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
      String organizationCode,
      int page,
      int pageSize) {
    String org = MaterialOrganization.normalize(organizationCode);
    QueryWrapper<MaterialMasterRaw> query = new QueryWrapper<>();
    query.eq("organization_code", org)
        .eq("active_flag", 1)
        .eq("source_type", SOURCE_TYPE_EXCEL);
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
    query.orderByAsc("material_code");
    return rawMapper.selectPage(new Page<>(safePage(page), safeSize(pageSize)), query);
  }

  @Override
  public List<QuoteCostingMaterialOptionResponse> options(String keyword, String organizationCode, int limit) {
    String normalized = trimToNull(keyword);
    String org = MaterialOrganization.normalize(organizationCode);
    return rawMapper.selectOptionsByLatestBatchKeyword(
            normalized, SOURCE_TYPE_EXCEL, org, safeOptionLimit(limit))
        .stream()
        .map(U9MaterialMasterServiceImpl::toOption)
        .toList();
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

  private static int safeOptionLimit(int limit) {
    if (limit < 1) {
      return 20;
    }
    return Math.min(limit, 50);
  }

  private static QuoteCostingMaterialOptionResponse toOption(MaterialMasterRaw row) {
    QuoteCostingMaterialOptionResponse response = new QuoteCostingMaterialOptionResponse();
    response.setId(row.getId());
    response.setMaterialCode(row.getMaterialCode());
    response.setMaterialName(row.getMaterialName());
    response.setMaterialSpec(row.getMaterialSpec());
    response.setMaterialModel(row.getMaterialModel());
    response.setChildModel(firstText(row.getMaterialModel(), row.getMaterialSpec()));
    response.setUnit(row.getUnit());
    response.setMaterialAttribute(row.getGlobalSeg4Material());
    response.setShapeAttribute(row.getShapeAttr());
    return response;
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static String firstText(String first, String second) {
    String normalized = trimToNull(first);
    return normalized == null ? trimToNull(second) : normalized;
  }
}
