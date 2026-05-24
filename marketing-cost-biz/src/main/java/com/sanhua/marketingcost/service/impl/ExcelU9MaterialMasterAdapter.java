package com.sanhua.marketingcost.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.sanhua.marketingcost.dto.U9MaterialImportResponse;
import com.sanhua.marketingcost.dto.U9MaterialMasterIngestRequest;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.U9MaterialMasterSourceType;
import com.sanhua.marketingcost.service.U9MaterialMasterIngestAdapter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ExcelU9MaterialMasterAdapter implements U9MaterialMasterIngestAdapter {
  private static final int BATCH_SIZE = 1000;
  private static final int ERROR_PREVIEW_LIMIT = 500;
  private static final int HEADER_SCAN_LIMIT = 5;
  private static final DateTimeFormatter BATCH_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  @Override
  public U9MaterialMasterSourceType sourceType() {
    return U9MaterialMasterSourceType.EXCEL;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public U9MaterialImportResponse ingest(U9MaterialMasterIngestRequest request) {
    if (request == null || request.input() == null) {
      throw new IllegalArgumentException("Excel 文件流为空");
    }
    U9MaterialImportResponse response = new U9MaterialImportResponse();
    String batchNo = resolveBatchNo(request.sourceBatchNo());
    response.setBatchNo(batchNo);
    response.setDatasetCode(U9MaterialMasterFieldContract.DATASET_CODE);
    response.setSourceType(sourceType().getCode());
    response.setMappingVersion(U9MaterialMasterFieldContract.MAPPING_VERSION);
    response.setStatus("PARSING");

    U9MaterialRowListener listener = new U9MaterialRowListener(batchNo, response);
    EasyExcel.read(request.input(), listener)
        .sheet(U9MaterialMasterFieldContract.SHEET_NAME)
        .headRowNumber(0)
        .doRead();
    listener.finish();
    response.setStatus(response.getFailCount() > 0 ? "PARTIAL_SUCCESS" : "PARSED");
    response.setMessage("导入完成: " + request.sourceFileName());
    return response;
  }

  private static String resolveBatchNo(String sourceBatchNo) {
    if (StringUtils.hasText(sourceBatchNo)) {
      return sourceBatchNo.trim();
    }
    return "u9-item-master-" + LocalDateTime.now().format(BATCH_TIME);
  }

  private static String normalizeCell(Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString().trim();
    if (text.endsWith(".0")) {
      text = text.substring(0, text.length() - 2);
    }
    return text.isEmpty() ? null : text;
  }

  private static String trimMax(String value, int maxLength) {
    return value != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
  }

  private final class U9MaterialRowListener extends AnalysisEventListener<Map<Integer, Object>> {
    private final String batchNo;
    private final U9MaterialImportResponse response;
    private final Map<Integer, String> columnToField = new HashMap<>();
    private final Set<String> seenMaterialCodes = new HashSet<>();
    private final List<MaterialMasterRaw> buffer = new ArrayList<>(BATCH_SIZE);
    private boolean headerParsed;

    private U9MaterialRowListener(String batchNo, U9MaterialImportResponse response) {
      this.batchNo = batchNo;
      this.response = response;
    }

    private boolean tryParseHeader(Map<Integer, Object> headMap) {
      Map<Integer, String> candidateColumnToField = new HashMap<>();
      Set<String> mappedFields = new HashSet<>();
      List<String> duplicates = new ArrayList<>();
      for (Map.Entry<Integer, Object> entry : headMap.entrySet()) {
        String field = U9MaterialMasterFieldContract.headerToField()
            .get(U9MaterialMasterFieldContract.canonicalHeader(entry.getValue()));
        if (field == null) {
          continue;
        }
        if (!mappedFields.add(field)) {
          duplicates.add(field);
          continue;
        }
        candidateColumnToField.put(entry.getKey(), field);
      }
      if (!duplicates.isEmpty()) {
        throw new IllegalArgumentException("表头重复: " + duplicates);
      }
      if (!mappedFields.contains("material_code") || !mappedFields.contains("material_name")) {
        return false;
      }
      // 字段映射必须按表头匹配，避免 U9 导出列顺序变化时把供应商、分类等字段写错位。
      columnToField.clear();
      columnToField.putAll(candidateColumnToField);
      headerParsed = true;
      return true;
    }

    private String missingHeaderMessage() {
      return "缺少必填表头: 物料代码*(material_code), 物料名称*(material_name)";
    }

    @Override
    public void invoke(Map<Integer, Object> data, AnalysisContext context) {
      int excelRow = context.readRowHolder().getRowIndex() + 1;
      if (!headerParsed) {
        if (tryParseHeader(data)) {
          return;
        }
        if (excelRow >= HEADER_SCAN_LIMIT) {
          throw new IllegalArgumentException(missingHeaderMessage());
        }
        return;
      }
      response.setTotalCount(response.getTotalCount() + 1);
      MaterialMasterRaw row = new MaterialMasterRaw();
      for (Map.Entry<Integer, Object> cell : data.entrySet()) {
        String field = columnToField.get(cell.getKey());
        if (field != null) {
          assign(row, field, normalizeCell(cell.getValue()));
        }
      }
      String materialCode = row.getMaterialCode();
      if (!StringUtils.hasText(materialCode)) {
        addError(excelRow, null, "物料代码为空");
        return;
      }
      if (!seenMaterialCodes.add(materialCode)) {
        addError(excelRow, materialCode, "物料代码重复，已按首次出现行导入");
        return;
      }
      row.setImportBatchId(batchNo);
      row.setSourceType(sourceType().getCode());
      row.setSourceBatchNo(batchNo);
      row.setMappingVersion(U9MaterialMasterFieldContract.MAPPING_VERSION);
      row.setActiveFlag(1);
      buffer.add(row);
      if (buffer.size() >= BATCH_SIZE) {
        flush();
      }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
      flush();
    }

    @Override
    public void onException(Exception exception, AnalysisContext context) throws Exception {
      Integer excelRow = context == null || context.readRowHolder() == null
          ? null
          : context.readRowHolder().getRowIndex() + 1;
      addError(excelRow, null, "Excel 解析异常: " + exception.getMessage());
      throw exception;
    }

    private void finish() {
      response.setFailCount(response.getFailCount());
    }

    private void addError(Integer excelRow, String materialCode, String reason) {
      response.setFailCount(response.getFailCount() + 1);
      if (response.getErrors().size() < ERROR_PREVIEW_LIMIT) {
        response.getErrors().add(new U9MaterialImportResponse.RowError(excelRow, materialCode, reason));
      }
    }

    private void flush() {
      if (buffer.isEmpty()) {
        return;
      }
      Db.saveBatch(buffer);
      response.setSuccessCount(response.getSuccessCount() + buffer.size());
      buffer.clear();
    }

    private void assign(MaterialMasterRaw row, String field, String value) {
      value = trimMax(value, maxLength(field));
      switch (field) {
        case "finance_category" -> row.setFinanceCategory(value);
        case "purchase_category" -> row.setPurchaseCategory(value);
        case "production_category" -> row.setProductionCategory(value);
        case "sales_category" -> row.setSalesCategory(value);
        case "bare_code" -> row.setBareCode(value);
        case "material_code" -> row.setMaterialCode(value);
        case "material_name" -> row.setMaterialName(value);
        case "material_spec" -> row.setMaterialSpec(value);
        case "material_model" -> row.setMaterialModel(value);
        case "drawing_no" -> row.setDrawingNo(value);
        case "main_category_code" -> row.setMainCategoryCode(value);
        case "main_category_name" -> row.setMainCategoryName(value);
        case "unit" -> row.setUnit(value);
        case "shape_attr" -> row.setShapeAttr(value);
        case "min_eco_batch" -> row.setMinEcoBatch(value);
        case "department_code" -> row.setDepartmentCode(value);
        case "department_name" -> row.setDepartmentName(value);
        case "production_division" -> row.setProductionDivision(value);
        case "default_supplier" -> row.setDefaultSupplier(value);
        case "purchase_lead_time" -> row.setPurchaseLeadTime(value);
        case "purchase_post_lead_time" -> row.setPurchasePostLeadTime(value);
        case "legacy_u9_code" -> row.setLegacyU9Code(value);
        case "global_seg_14_customs_unit" -> row.setGlobalSeg14CustomsUnit(value);
        case "global_seg_15_package_size" -> row.setGlobalSeg15PackageSize(value);
        case "global_seg_17_replace_strategy" -> row.setGlobalSeg17ReplaceStrategy(value);
        case "global_seg_18_purchase_type" -> row.setGlobalSeg18PurchaseType(value);
        case "global_seg_19_in_out_ratio" -> row.setGlobalSeg19InOutRatio(value);
        case "global_seg_2_logistics_type" -> row.setGlobalSeg2LogisticsType(value);
        case "global_seg_20_internal_threshold" -> row.setGlobalSeg20InternalThreshold(value);
        case "private_seg_21_customs_name" -> row.setPrivateSeg21CustomsName(value);
        case "private_seg_22_customs_code" -> row.setPrivateSeg22CustomsCode(value);
        case "private_seg_23_customs_desc" -> row.setPrivateSeg23CustomsDesc(value);
        case "private_seg_24_product_property" -> row.setPrivateSeg24ProductProperty(value);
        case "private_seg_25_daily_capacity" -> row.setPrivateSeg25DailyCapacity(value);
        case "private_seg_26_lead_time" -> row.setPrivateSeg26LeadTime(value);
        case "global_seg_3_status" -> row.setGlobalSeg3Status(value);
        case "global_seg_4_material" -> row.setGlobalSeg4Material(value);
        case "global_seg_5_net_weight" -> row.setGlobalSeg5NetWeight(value);
        case "global_seg_6_valid_period" -> row.setGlobalSeg6ValidPeriod(value);
        case "global_seg_7_product_property_class" -> row.setGlobalSeg7ProductPropertyClass(value);
        case "global_seg_8_loss_rate" -> row.setGlobalSeg8LossRate(value);
        case "global_seg_9_gross_weight" -> row.setGlobalSeg9GrossWeight(value);
        case "purchase_multiple" -> row.setPurchaseMultiple(value);
        case "min_order_qty" -> row.setMinOrderQty(value);
        case "default_buyer" -> row.setDefaultBuyer(value);
        case "plan_method" -> row.setPlanMethod(value);
        case "forecast_control_type" -> row.setForecastControlType(value);
        case "demand_trace" -> row.setDemandTrace(value);
        case "demand_category_control" -> row.setDemandCategoryControl(value);
        case "demand_category_compare_rule" -> row.setDemandCategoryCompareRule(value);
        case "default_planner" -> row.setDefaultPlanner(value);
        case "engineering_change_control" -> row.setEngineeringChangeControl(value);
        case "allow_over_pick" -> row.setAllowOverPick(value);
        case "prepare_over_type" -> row.setPrepareOverType(value);
        case "over_complete_type" -> row.setOverCompleteType(value);
        case "over_complete_ratio" -> row.setOverCompleteRatio(value);
        case "inventory_planning_method" -> row.setInventoryPlanningMethod(value);
        case "code_inventory_account" -> row.setCodeInventoryAccount(value);
        case "cost_element" -> row.setCostElement(value);
        case "producible" -> row.setProducible(value);
        case "purchase_receive_principle" -> row.setPurchaseReceivePrinciple(value);
        case "mrp_purchase_pre_lead_time" -> row.setMrpPurchasePreLeadTime(value);
        case "global_seg_3_theoretical_net_weight" -> row.setGlobalSeg3TheoreticalNetWeight(value);
        default -> {
        }
      }
    }
  }

  private static int maxLength(String field) {
    return switch (field) {
      case "material_code" -> 64;
      default -> 255;
    };
  }
}
