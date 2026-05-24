package com.sanhua.marketingcost.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MakePartPriceCalcPageResponse;
import com.sanhua.marketingcost.dto.MakePartPriceCalcQueryRequest;
import com.sanhua.marketingcost.dto.MakePartPriceGapPageResponse;
import com.sanhua.marketingcost.dto.MakePartPriceGenerateRequest;
import com.sanhua.marketingcost.dto.MakePartPriceGenerateResponse;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.MakePartPriceGapItem;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceGapItemMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.MakePartPriceCalcService;
import com.sanhua.marketingcost.service.MakePartPriceGenerationService;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MakePartPriceCalcServiceImpl implements MakePartPriceCalcService {

  private static final int MAX_EXPORT_ROWS = 10000;
  private static final List<String> EXPORT_HEADERS = List.of(
      "价格月份",
      "生成时间",
      "OA单号",
      "料号",
      "名称",
      "图号",
      "料件类型",
      "毛重(g)",
      "净重(g)",
      "零件价格",
      "原材料代码",
      "原材料/毛坯",
      "原材料价格",
      "回收代码",
      "回收名称",
      "回收单价",
      "委外加工费",
      "是否完整取价",
      "状态",
      "备注");
  private static final List<String> GAP_EXPORT_HEADERS = List.of(
      "价格月份",
      "生成时间",
      "批次",
      "OA单号",
      "业务单元",
      "制造件料号",
      "制造件名称",
      "原材料料号",
      "原材料名称",
      "原材料规格",
      "废料料号",
      "废料名称",
      "缺价类型",
      "要补价的料号",
      "要补价的物料名称",
      "价格类型",
      "缺价原因",
      "OA推送状态");

  private final MakePartPriceCalcRowMapper mapper;
  private final MakePartPriceGapItemMapper gapItemMapper;
  private final MakePartPriceGenerationService generationService;

  public MakePartPriceCalcServiceImpl(
      MakePartPriceCalcRowMapper mapper,
      MakePartPriceGapItemMapper gapItemMapper,
      MakePartPriceGenerationService generationService) {
    this.mapper = mapper;
    this.gapItemMapper = gapItemMapper;
    this.generationService = generationService;
  }

  @Override
  public MakePartPriceCalcPageResponse page(MakePartPriceCalcQueryRequest request) {
    MakePartPriceCalcQueryRequest safe = request == null ? new MakePartPriceCalcQueryRequest() : request;
    Page<MakePartPriceCalcRow> page =
        mapper.selectPage(
            new Page<>(pageNo(safe.getPage()), pageSize(safe.getPageSize())),
            buildQuery(safe).orderByDesc(MakePartPriceCalcRow::getCreatedAt)
                .orderByDesc(MakePartPriceCalcRow::getId));
    return new MakePartPriceCalcPageResponse(page.getTotal(), page.getRecords());
  }

  @Override
  public MakePartPriceGapPageResponse gapPage(MakePartPriceCalcQueryRequest request) {
    MakePartPriceCalcQueryRequest safe = request == null ? new MakePartPriceCalcQueryRequest() : request;
    Page<MakePartPriceGapItem> page =
        gapItemMapper.selectPage(
            new Page<>(pageNo(safe.getPage()), pageSize(safe.getPageSize())),
            buildGapQuery(safe).orderByDesc(MakePartPriceGapItem::getGeneratedAt)
                .orderByDesc(MakePartPriceGapItem::getId));
    return new MakePartPriceGapPageResponse(page.getTotal(), page.getRecords());
  }

  @Override
  public MakePartPriceCalcRow get(Long id) {
    return id == null ? null : mapper.selectById(id);
  }

  @Override
  public MakePartPriceGenerateResponse generate(MakePartPriceGenerateRequest request) {
    MakePartPriceGenerateRequest safe =
        request == null ? new MakePartPriceGenerateRequest() : request;
    String businessUnitType = BusinessUnitContext.getCurrentBusinessUnitType();
    if (safe.getParentMaterialNos() != null && safe.getParentMaterialNos().size() == 1) {
      return generationService.generateByMaterial(
          safe.getParentMaterialNos().get(0), businessUnitType, period(safe));
    }
    if (StringUtils.hasText(safe.getOaNo())) {
      return generationService.generateByOa(safe.getOaNo(), businessUnitType, period(safe));
    }
    if (safe.getParentMaterialNos() != null && !safe.getParentMaterialNos().isEmpty()) {
      MakePartPriceGenerateResponse total = null;
      for (String parentMaterialNo : safe.getParentMaterialNos()) {
        MakePartPriceGenerateResponse one =
            generationService.generateByMaterial(parentMaterialNo, businessUnitType, period(safe));
        total = merge(total, one);
      }
      return total == null ? new MakePartPriceGenerateResponse() : total;
    }
    return generationService.generateAllLatest(businessUnitType, period(safe));
  }

  @Override
  public String latestBatch(String oaNo, String businessUnitType, String parentMaterialNo) {
    return generationService.findLatestBatchId(oaNo, businessUnitType, parentMaterialNo);
  }

  @Override
  public Map<String, Integer> statusSummary(MakePartPriceCalcQueryRequest request) {
    List<MakePartPriceCalcRow> rows = mapper.selectList(buildQuery(request));
    Map<String, Integer> summary = new LinkedHashMap<>();
    for (MakePartPriceCalcRow row : rows) {
      String status = StringUtils.hasText(row.getStatus()) ? row.getStatus() : "UNKNOWN";
      summary.merge(status, 1, Integer::sum);
    }
    return summary;
  }

  @Override
  public int export(MakePartPriceCalcQueryRequest request, OutputStream output) {
    List<MakePartPriceCalcRow> rows =
        mapper.selectList(
            buildQuery(request)
                .orderByAsc(MakePartPriceCalcRow::getParentMaterialNo)
                .orderByAsc(MakePartPriceCalcRow::getChildMaterialNo)
                .orderByAsc(MakePartPriceCalcRow::getScrapCode)
                .last("LIMIT " + MAX_EXPORT_ROWS));
    List<MakePartPriceGapItem> gapItems =
        gapItemMapper.selectList(
            buildGapQuery(request)
                .orderByAsc(MakePartPriceGapItem::getParentMaterialNo)
                .orderByAsc(MakePartPriceGapItem::getChildMaterialNo)
                .orderByAsc(MakePartPriceGapItem::getScrapCode)
                .orderByAsc(MakePartPriceGapItem::getMissingPriceRole)
                .last("LIMIT " + MAX_EXPORT_ROWS));
    try (ExcelWriter writer = EasyExcel.write(output).build()) {
      WriteSheet calcSheet =
          EasyExcel.writerSheet(0, "制造件价格生成")
              .head(exportHead())
              .build();
      WriteSheet gapSheet =
          EasyExcel.writerSheet(1, "缺价清单")
              .head(gapExportHead())
              .build();
      writer.write(exportRows(rows), calcSheet);
      writer.write(gapExportRows(gapItems), gapSheet);
    }
    return rows.size();
  }

  @Override
  public List<String> exportHeaders() {
    return EXPORT_HEADERS;
  }

  private LambdaQueryWrapper<MakePartPriceCalcRow> buildQuery(MakePartPriceCalcQueryRequest request) {
    MakePartPriceCalcQueryRequest safe = request == null ? new MakePartPriceCalcQueryRequest() : request;
    LambdaQueryWrapper<MakePartPriceCalcRow> query = Wrappers.lambdaQuery(MakePartPriceCalcRow.class);
    eqIfText(query, MakePartPriceCalcRow::getCalcBatchId, safe.getCalcBatchId());
    eqIfText(query, MakePartPriceCalcRow::getPricingMonth, safe.getPricingMonth());
    eqIfText(query, MakePartPriceCalcRow::getOaNo, safe.getOaNo());
    eqIfText(query, MakePartPriceCalcRow::getBusinessUnitType, safe.getBusinessUnitType());
    eqIfText(query, MakePartPriceCalcRow::getParentMaterialNo, safe.getParentMaterialNo());
    eqIfText(query, MakePartPriceCalcRow::getChildMaterialNo, safe.getChildMaterialNo());
    eqIfText(query, MakePartPriceCalcRow::getScrapCode, safe.getScrapCode());
    eqIfText(query, MakePartPriceCalcRow::getItemProcessType, safe.getItemProcessType());
    if (Boolean.TRUE.equals(safe.getOnlyError())) {
      query.ne(MakePartPriceCalcRow::getStatus, "OK");
    } else {
      eqIfText(query, MakePartPriceCalcRow::getStatus, safe.getStatus());
    }
    return query;
  }

  private LambdaQueryWrapper<MakePartPriceGapItem> buildGapQuery(MakePartPriceCalcQueryRequest request) {
    MakePartPriceCalcQueryRequest safe = request == null ? new MakePartPriceCalcQueryRequest() : request;
    LambdaQueryWrapper<MakePartPriceGapItem> query = Wrappers.lambdaQuery(MakePartPriceGapItem.class);
    eqIfTextGap(query, MakePartPriceGapItem::getCalcBatchId, safe.getCalcBatchId());
    eqIfTextGap(query, MakePartPriceGapItem::getPricingMonth, safe.getPricingMonth());
    eqIfTextGap(query, MakePartPriceGapItem::getOaNo, safe.getOaNo());
    eqIfTextGap(query, MakePartPriceGapItem::getBusinessUnitType, safe.getBusinessUnitType());
    eqIfTextGap(query, MakePartPriceGapItem::getParentMaterialNo, safe.getParentMaterialNo());
    eqIfTextGap(query, MakePartPriceGapItem::getChildMaterialNo, safe.getChildMaterialNo());
    eqIfTextGap(query, MakePartPriceGapItem::getScrapCode, safe.getScrapCode());
    eqIfTextGap(query, MakePartPriceGapItem::getMissingPriceRole, safe.getMissingPriceRole());
    eqIfTextGap(query, MakePartPriceGapItem::getMissingMaterialNo, safe.getMissingMaterialNo());
    return query;
  }

  private void eqIfText(
      LambdaQueryWrapper<MakePartPriceCalcRow> query,
      com.baomidou.mybatisplus.core.toolkit.support.SFunction<MakePartPriceCalcRow, ?> column,
      String value) {
    if (StringUtils.hasText(value)) {
      query.eq(column, value.trim());
    }
  }

  private void eqIfTextGap(
      LambdaQueryWrapper<MakePartPriceGapItem> query,
      com.baomidou.mybatisplus.core.toolkit.support.SFunction<MakePartPriceGapItem, ?> column,
      String value) {
    if (StringUtils.hasText(value)) {
      query.eq(column, value.trim());
    }
  }

  private List<List<String>> exportHead() {
    List<List<String>> head = new ArrayList<>();
    // 外径、壁厚、净长1、净长2 已废弃，不再导出；页面和导出只展示新制造件生成口径字段。
    for (String header : EXPORT_HEADERS) {
      head.add(Collections.singletonList(header));
    }
    return head;
  }

  private List<List<String>> gapExportHead() {
    List<List<String>> head = new ArrayList<>();
    for (String header : GAP_EXPORT_HEADERS) {
      head.add(Collections.singletonList(header));
    }
    return head;
  }

  private List<List<Object>> exportRows(List<MakePartPriceCalcRow> rows) {
    List<List<Object>> result = new ArrayList<>();
    for (MakePartPriceCalcRow row : rows) {
      List<Object> line = new ArrayList<>();
      line.add(nz(row.getPricingMonth()));
      line.add(dt(row.getCreatedAt()));
      line.add(nz(row.getOaNo()));
      line.add(nz(row.getParentMaterialNo()));
      line.add(nz(row.getParentMaterialName()));
      line.add(nz(row.getDrawingNo()));
      line.add(nz(row.getItemProcessType()));
      line.add(num(row.getGrossWeightG()));
      line.add(num(row.getNetWeightG()));
      line.add(num(row.getParentTotalCostPrice()));
      line.add(nz(row.getChildMaterialNo()));
      line.add(displayMaterial(row));
      line.add(num(row.getRawUnitPrice()));
      line.add(nz(row.getScrapCode()));
      line.add(nz(row.getScrapName()));
      line.add(num(row.getScrapUnitPrice()));
      line.add(num(row.getOutsourceFee()));
      line.add(Boolean.TRUE.equals(row.getPriceComplete()) ? "是" : "否");
      line.add(nz(row.getStatus()));
      line.add(nz(row.getRemark()));
      result.add(line);
    }
    return result;
  }

  private List<List<Object>> gapExportRows(List<MakePartPriceGapItem> gapItems) {
    List<List<Object>> result = new ArrayList<>();
    for (MakePartPriceGapItem item : gapItems) {
      // 缺价清单是后续 OA 补价输入表，必须同时导出制造件、原材料、废料和真正要补价的料号。
      List<Object> line = new ArrayList<>();
      line.add(nz(item.getPricingMonth()));
      line.add(dt(item.getGeneratedAt()));
      line.add(nz(item.getCalcBatchId()));
      line.add(nz(item.getOaNo()));
      line.add(nz(item.getBusinessUnitType()));
      line.add(nz(item.getParentMaterialNo()));
      line.add(nz(item.getParentMaterialName()));
      line.add(nz(item.getChildMaterialNo()));
      line.add(nz(item.getChildMaterialName()));
      line.add(nz(item.getChildMaterialSpec()));
      line.add(nz(item.getScrapCode()));
      line.add(nz(item.getScrapName()));
      line.add(nz(item.getMissingPriceRole()));
      line.add(nz(item.getMissingMaterialNo()));
      line.add(nz(item.getMissingMaterialName()));
      line.add(nz(item.getPriceType()));
      line.add(nz(item.getReason()));
      line.add(nz(item.getOaPushStatus()));
      result.add(line);
    }
    return result;
  }

  private String displayMaterial(MakePartPriceCalcRow row) {
    if (StringUtils.hasText(row.getChildMaterialSpec())) {
      return row.getChildMaterialSpec();
    }
    return nz(row.getChildMaterialName());
  }

  private MakePartPriceGenerateResponse merge(
      MakePartPriceGenerateResponse left, MakePartPriceGenerateResponse right) {
    if (left == null) {
      return right;
    }
    left.setParentCount(left.getParentCount() + right.getParentCount());
    left.setRowCount(left.getRowCount() + right.getRowCount());
    left.setTotalCount(left.getTotalCount() + right.getTotalCount());
    left.setOkCount(left.getOkCount() + right.getOkCount());
    left.setWarningCount(left.getWarningCount() + right.getWarningCount());
    left.setErrorCount(left.getErrorCount() + right.getErrorCount());
    right.getStatusSummary()
        .forEach((status, count) -> left.getStatusSummary().merge(status, count, Integer::sum));
    return left;
  }

  private String period(MakePartPriceGenerateRequest request) {
    // 当前阶段没有月度调价模块，制造件生成统一取系统当前日期对应月份作为价格月份。
    return StringUtils.hasText(request.getPeriod()) ? request.getPeriod().trim() : YearMonth.now().toString();
  }

  private int pageNo(Integer page) {
    return page == null || page < 1 ? 1 : page;
  }

  private int pageSize(Integer pageSize) {
    return pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 200);
  }

  private String nz(String value) {
    return value == null ? "" : value;
  }

  private Object num(BigDecimal value) {
    return value == null ? "" : value;
  }

  private String dt(LocalDateTime value) {
    return value == null ? "" : value.toString();
  }
}
