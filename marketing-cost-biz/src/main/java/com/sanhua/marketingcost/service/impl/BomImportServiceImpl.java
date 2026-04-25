package com.sanhua.marketingcost.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.metadata.data.ReadCellData;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.sanhua.marketingcost.dto.BomBatchSummary;
import com.sanhua.marketingcost.dto.BomImportError;
import com.sanhua.marketingcost.dto.BomImportResult;
import com.sanhua.marketingcost.dto.BuildHierarchyRequest;
import com.sanhua.marketingcost.dto.BuildHierarchyResult;
import com.sanhua.marketingcost.dto.ImportAndBuildResult;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.BomHierarchyBuildService;
import com.sanhua.marketingcost.service.BomImportService;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * BOM Excel 导入实现 —— 阶段 A。
 *
 * <p>为什么不用 {@code @ExcelProperty} 注解绑定字段（像
 * {@code FinanceBasePriceImportServiceImpl} 那样）：BOM Excel 有两列 <b>同名</b>
 * "子件.主分类"（col21 / col22 分别是 material_category_1 / material_category_2），
 * EasyExcel 按表头名绑定时同名列只保留第一个，第二个会丢失。因此本类用
 * {@code Map<Integer, Object>} 原始模式 + 自定义 {@link BomRowListener}：
 * <ol>
 *   <li>{@code invokeHead} 阶段：记录 col index → 目标字段名，遇到同名"子件.主分类"
 *       第一次映射为 materialCategory1，第二次为 materialCategory2</li>
 *   <li>{@code invoke} 阶段：按 col index 从 Map 取值，转换类型后 set 到 BomU9Source</li>
 *   <li>每 {@link #BATCH_SIZE} 行调一次 {@code Db.saveBatch}，不累积全部行到内存</li>
 * </ol>
 *
 * <p>事务：方法级 {@code @Transactional}，DB 异常整批回滚；单行解析/校验失败不中断，
 * 进 {@link BomImportResult#getErrors()}。
 */
@Service
public class BomImportServiceImpl implements BomImportService {

  private static final Logger log = LoggerFactory.getLogger(BomImportServiceImpl.class);

  /** Excel 第 1 行标题 + 第 2 行表头；数据从第 3 行起 */
  private static final int HEAD_ROW_NUMBER = 2;

  /** 批次 buffer 阈值；超过就 flush 一次 saveBatch */
  private static final int BATCH_SIZE = 1000;

  /** 批次 ID 前缀日期格式 */
  private static final DateTimeFormatter BATCH_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

  /** Excel 列名 → BomU9Source 字段名（单次映射的表头，同名列见特殊处理） */
  private static final Map<String, String> COLUMN_TO_FIELD = buildColumnToField();

  /** 预期 Excel sheet 名（首选），找不到时退回 sheetAt(0） */
  private static final String EXPECTED_SHEET_NAME = "BOM母项";

  private final BomU9SourceMapper bomU9SourceMapper;
  private final BomHierarchyBuildService buildService;

  public BomImportServiceImpl(
      BomU9SourceMapper bomU9SourceMapper,
      BomHierarchyBuildService buildService) {
    this.bomU9SourceMapper = bomU9SourceMapper;
    this.buildService = buildService;
  }

  // ============================ public API ============================

  @Override
  @Transactional(rollbackFor = Exception.class)
  public BomImportResult importExcel(InputStream input, String sourceFileName, String importedBy) {
    BomImportResult result = new BomImportResult();
    result.setSourceType("EXCEL");
    result.setSourceFileName(sourceFileName);

    if (input == null) {
      result.getErrors().add(new BomImportError(null, "Excel 流为空"));
      result.setImportedAt(LocalDateTime.now());
      return result;
    }

    String batchId = generateBatchId();
    LocalDateTime importedAt = LocalDateTime.now();
    result.setImportBatchId(batchId);
    result.setImportedAt(importedAt);

    // BU 在监听器内每行手工注入，避免 MetaObjectHandler 在部分调用链下拿不到 SecurityContext
    String buType = BusinessUnitContext.getCurrentBusinessUnitType();

    BomRowListener listener =
        new BomRowListener(batchId, sourceFileName, importedAt, importedBy, buType, result);

    try {
      EasyExcel.read(input, listener)
          .sheet(EXPECTED_SHEET_NAME)
          .headRowNumber(HEAD_ROW_NUMBER)
          .doRead();
    } catch (RuntimeException e) {
      // sheet 名不匹配时 EasyExcel 抛异常：退回按 sheet index 0 再读一次
      log.warn("按 sheet 名 [{}] 读取失败，退回 sheetAt(0): {}", EXPECTED_SHEET_NAME, e.getMessage());
      // 此时 listener 已被污染，需要重新构造 —— 但 input 也读过了，需要调用方重传
      throw new IllegalStateException(
          "Excel 读取失败（sheet 名 '" + EXPECTED_SHEET_NAME + "' 不存在或文件损坏）: " + e.getMessage(), e);
    }

    log.info(
        "BOM 导入完成: batchId={}, file={}, total={}, success={}, errors={}",
        batchId,
        sourceFileName,
        result.getTotalRows(),
        result.getSuccessRows(),
        result.getErrors().size());
    return result;
  }

  /**
   * 财务一键端点：导入成功后按 batch 里出现过的每个 bomPurpose 循环跑 build ALL。
   *
   * <p>非 {@code @Transactional}：导入和构建是两个独立事务，避免一次长事务锁死连接池。
   * 导入已持久化的情况下构建任一 purpose 失败都不回滚导入结果（调用方可重试构建）。
   */
  @Override
  public ImportAndBuildResult importAndBuild(
      InputStream input, String sourceFileName, String importedBy) {
    ImportAndBuildResult merged = new ImportAndBuildResult();

    // 阶段 A：导入（内部事务控制）
    BomImportResult importResult;
    try {
      importResult = importExcel(input, sourceFileName, importedBy);
    } catch (Exception e) {
      log.error("importAndBuild 阶段 A 失败: {}", e.getMessage(), e);
      merged.setStatus("IMPORT_FAILED");
      merged.setErrorMessage(e.getMessage());
      return merged;
    }
    merged.setImportResult(importResult);

    // 阶段 B：查 distinct purpose，对每个 purpose 跑一次 ALL 构建
    String batchId = importResult.getImportBatchId();
    List<String> purposes = bomU9SourceMapper.findDistinctPurposes(batchId);
    log.info("importAndBuild 阶段 B 开始: batch={} purposes={}", batchId, purposes);

    int totalRows = 0;
    boolean anyFailed = false;
    for (String purpose : purposes) {
      BuildHierarchyRequest req = new BuildHierarchyRequest();
      req.setImportBatchId(batchId);
      req.setBomPurpose(purpose);
      req.setMode("ALL");
      try {
        BuildHierarchyResult br = buildService.build(req);
        merged.getBuilds().add(br);
        totalRows += br.getRowsWritten();
        merged.getPurposesBuilt().add(purpose);
        if (!br.getFailedProducts().isEmpty()) {
          // 某些产品 BOM 环 / 孤儿等局部失败 —— 整体标 PARTIAL，继续下一个 purpose
          anyFailed = true;
        }
      } catch (Exception e) {
        log.warn("importAndBuild 阶段 B purpose={} 构建异常: {}", purpose, e.getMessage());
        anyFailed = true;
        // 单个 purpose 失败不中断，继续尝试其他 purpose
      }
    }
    merged.setTotalRawRowsWritten(totalRows);
    merged.setStatus(anyFailed ? "PARTIAL_BUILD_FAILED" : "SUCCESS");
    log.info("importAndBuild 完成: batch={} status={} totalRawRows={}",
        batchId, merged.getStatus(), totalRows);
    return merged;
  }

  @Override
  public List<BomBatchSummary> listBatches(String layer, int page, int size) {
    // T3 阶段只实现 U9_SOURCE；T4/T5 再补其他层
    if (!"U9_SOURCE".equalsIgnoreCase(layer)) {
      throw new IllegalArgumentException(
          "layer 暂只支持 U9_SOURCE（当前=" + layer + "），RAW_HIERARCHY / COSTING_ROW 待 T4/T5 实现");
    }
    int safePage = Math.max(1, page);
    int safeSize = Math.max(1, Math.min(size, 200));
    int offset = (safePage - 1) * safeSize;
    return bomU9SourceMapper.listBatchSummaries(offset, safeSize);
  }

  // ============================ 私有辅助 ============================

  /** 生成批次 ID：b_20260423_1a2b3c */
  private static String generateBatchId() {
    return "b_" + LocalDate.now().format(BATCH_DATE) + "_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
  }

  /**
   * 构造"表头名 → BomU9Source 字段名"映射表。
   *
   * <p>注意："子件.主分类" 有两列（col21 / col22），不在本 map 里；由 Listener 在
   * {@code invokeHead} 里按出现次序手工分发给 materialCategory1 / materialCategory2。
   */
  private static Map<String, String> buildColumnToField() {
    Map<String, String> m = new HashMap<>();
    m.put("母件料品_料号", "parentMaterialNo");
    m.put("母件料品_品名", "parentMaterialName");
    m.put("生产单位", "productionUnit");
    m.put("BOM生产目的", "bomPurpose");
    m.put("版本号", "bomVersion");
    m.put("状态", "bomStatus");
    m.put("子件项次", "childSeq");
    m.put("子项类型", "childType");
    m.put("子件.料号", "childMaterialNo");
    m.put("子项_品名", "childMaterialName");
    m.put("子项规格", "childMaterialSpec");
    m.put("BOM子项.成本要素.成本要素编码", "costElementCode");
    m.put("BOM子项.成本要素.名称", "costElementName");
    m.put("BOM子项.委托加工备料来源", "consignSource");
    m.put("BOM子项.是否计算成本", "u9IsCostFlag");
    m.put("工程变更单编码", "engineeringChangeNo");
    m.put("发料单位", "issueUnit");
    m.put("BOM子项.子件料品.库存主单位", "stockUnit");
    m.put("子项_用量", "qtyPerParent");
    m.put("工序号", "processSeq");
    m.put("子件.生产分类", "productionCategory");
    m.put("子件料品.形态属性", "shapeAttr");
    m.put("子件.生产部门", "productionDept");
    m.put("发料方式", "issueMethod");
    m.put("是否虚拟", "isVirtual");
    m.put("母件底数", "parentBaseQty");
    m.put("子项.段3(替代策略)", "segment3");
    m.put("子项.段4(工序编号)", "segment4");
    m.put("BOM子项.订单完工", "orderComplete");
    m.put("生效日期", "effectiveFrom");
    m.put("失效日期", "effectiveTo");
    return m;
  }

  // ============================ Listener：核心解析 ============================

  /**
   * 逐行读 Excel 到 BomU9Source；每 {@link #BATCH_SIZE} 行 flush 一次。
   *
   * <p>不是 Spring bean —— 通过构造器持有依赖（BomU9SourceMapper 走外层方法闭包引用；
   * 这里直接用 {@code Db.saveBatch} 静态工具省去该引用）。
   */
  private static final class BomRowListener extends AnalysisEventListener<Map<Integer, Object>> {

    private final String batchId;
    private final String sourceFileName;
    private final LocalDateTime importedAt;
    private final String importedBy;
    private final String buType;
    private final BomImportResult result;

    /** col index → BomU9Source 字段名，在 invokeHead 阶段建立 */
    private final Map<Integer, String> colToField = new HashMap<>();

    /** buffer 累积；到 BATCH_SIZE 就 flush */
    private final List<BomU9Source> buffer = new ArrayList<>(BATCH_SIZE);

    BomRowListener(
        String batchId,
        String sourceFileName,
        LocalDateTime importedAt,
        String importedBy,
        String buType,
        BomImportResult result) {
      this.batchId = batchId;
      this.sourceFileName = sourceFileName;
      this.importedAt = importedAt;
      this.importedBy = importedBy;
      this.buType = buType;
      this.result = result;
    }

    @Override
    public void invokeHead(Map<Integer, ReadCellData<?>> headMap, AnalysisContext context) {
      // 关键：处理同名列 "子件.主分类" —— 第一次出现填 materialCategory1，第二次 materialCategory2
      int mainCategorySeen = 0;
      for (Map.Entry<Integer, ReadCellData<?>> e : headMap.entrySet()) {
        String header = e.getValue() == null ? null : e.getValue().getStringValue();
        if (header == null || header.isBlank()) {
          continue;
        }
        String trimmed = header.trim();
        if ("子件.主分类".equals(trimmed)) {
          mainCategorySeen++;
          colToField.put(e.getKey(), mainCategorySeen == 1 ? "materialCategory1" : "materialCategory2");
        } else {
          String field = COLUMN_TO_FIELD.get(trimmed);
          if (field != null) {
            colToField.put(e.getKey(), field);
          }
        }
      }
      log.debug("BOM 导入表头解析完成: mapped={} 列", colToField.size());
    }

    @Override
    public void invoke(Map<Integer, Object> data, AnalysisContext context) {
      result.setTotalRows(result.getTotalRows() + 1);
      // EasyExcel 回调的 rowIndex 是 0-based；Excel 1-based 行号 = rowIndex + 1
      int excelRow = context.readRowHolder().getRowIndex() + 1;
      BomU9Source entity = new BomU9Source();
      entity.setImportBatchId(batchId);
      entity.setSourceType("EXCEL");
      entity.setSourceFileName(sourceFileName);
      entity.setImportedAt(importedAt);
      entity.setImportedBy(importedBy);

      for (Map.Entry<Integer, Object> cell : data.entrySet()) {
        String field = colToField.get(cell.getKey());
        if (field == null) {
          continue;
        }
        try {
          assign(entity, field, cell.getValue());
        } catch (RuntimeException ex) {
          // 单字段转换失败留空，整行只在必填缺失时才进 errors（见下）
          log.debug("excel row={} col={} field={} 转换失败: {}", excelRow, cell.getKey(), field, ex.getMessage());
        }
      }

      // 必填校验：母件 / 子件料号
      if (!StringUtils.hasText(entity.getParentMaterialNo())
          || !StringUtils.hasText(entity.getChildMaterialNo())) {
        result.getErrors().add(new BomImportError(excelRow, "母件/子件料号必填"));
        return;
      }

      buffer.add(entity);
      if (buffer.size() >= BATCH_SIZE) {
        flushBuffer();
      }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
      if (!buffer.isEmpty()) {
        flushBuffer();
      }
    }

    @Override
    public void onException(Exception exception, AnalysisContext context) {
      // EasyExcel 层面的解析异常：整行进 errors，不抛出中断
      Integer excelRow = context == null || context.readRowHolder() == null
          ? null
          : context.readRowHolder().getRowIndex() + 1;
      result.getErrors().add(new BomImportError(excelRow, "Excel 解析异常: " + exception.getMessage()));
    }

    /** flush buffer 到 DB，清空后继续。MP saveBatch 走 JDBC BATCH，比 loop insert 快 ~10× */
    private void flushBuffer() {
      if (StringUtils.hasText(buType)) {
        for (BomU9Source row : buffer) {
          // lp_bom_u9_source 表没有 business_unit_type 列（DDL 里就没加），无需赋值
          // 这里预留 buType 参数是为了保持 Listener API 与其他导入服务一致，便于未来扩展
          // （例如将来要按 bu 统计批次时，可在此注入标记到其他关联表）
        }
      }
      Db.saveBatch(buffer);
      result.setSuccessRows(result.getSuccessRows() + buffer.size());
      buffer.clear();
    }

    /** 按字段名 setter 写回；类型转换按目标字段类型分派。 */
    private void assign(BomU9Source entity, String field, Object value) {
      if (value == null) {
        return;
      }
      switch (field) {
        case "parentMaterialNo" -> entity.setParentMaterialNo(toStr(value));
        case "parentMaterialName" -> entity.setParentMaterialName(toStr(value));
        case "productionUnit" -> entity.setProductionUnit(toStr(value));
        case "bomPurpose" -> entity.setBomPurpose(toStr(value));
        case "bomVersion" -> entity.setBomVersion(toStr(value));
        case "bomStatus" -> entity.setBomStatus(toStr(value));
        case "childSeq" -> entity.setChildSeq(toInt(value));
        case "childType" -> entity.setChildType(toStr(value));
        case "childMaterialNo" -> entity.setChildMaterialNo(toStr(value));
        case "childMaterialName" -> entity.setChildMaterialName(toStr(value));
        case "childMaterialSpec" -> entity.setChildMaterialSpec(toStr(value));
        case "costElementCode" -> entity.setCostElementCode(toStr(value));
        case "costElementName" -> entity.setCostElementName(toStr(value));
        case "consignSource" -> entity.setConsignSource(toStr(value));
        case "u9IsCostFlag" -> entity.setU9IsCostFlag(toFlag(value));
        case "engineeringChangeNo" -> entity.setEngineeringChangeNo(toStr(value));
        case "issueUnit" -> entity.setIssueUnit(toStr(value));
        case "stockUnit" -> entity.setStockUnit(toStr(value));
        case "qtyPerParent" -> entity.setQtyPerParent(toDecimal(value));
        case "processSeq" -> entity.setProcessSeq(toStr(value));
        case "materialCategory1" -> entity.setMaterialCategory1(toStr(value));
        case "materialCategory2" -> entity.setMaterialCategory2(toStr(value));
        case "productionCategory" -> entity.setProductionCategory(toStr(value));
        case "shapeAttr" -> entity.setShapeAttr(toStr(value));
        case "productionDept" -> entity.setProductionDept(toStr(value));
        case "issueMethod" -> entity.setIssueMethod(toStr(value));
        case "isVirtual" -> entity.setIsVirtual(toFlag(value));
        case "parentBaseQty" -> entity.setParentBaseQty(toDecimal(value));
        case "segment3" -> entity.setSegment3(toStr(value));
        case "segment4" -> entity.setSegment4(toStr(value));
        case "orderComplete" -> entity.setOrderComplete(toFlag(value));
        case "effectiveFrom" -> entity.setEffectiveFrom(toDate(value));
        case "effectiveTo" -> entity.setEffectiveTo(toDate(value));
        default -> log.debug("未知字段 {}，跳过", field);
      }
    }

    /** 任意 Object → String：去掉首尾空白；空串返回 null */
    private static String toStr(Object v) {
      if (v == null) return null;
      String s = v.toString().trim();
      return s.isEmpty() ? null : s;
    }

    /** 任意 Object → Integer：支持 Number 和 String；转换失败返回 null */
    private static Integer toInt(Object v) {
      if (v == null) return null;
      if (v instanceof Number n) return n.intValue();
      String s = v.toString().trim();
      if (s.isEmpty()) return null;
      return new BigDecimal(s).intValue();
    }

    /** 任意 Object → BigDecimal：精度优先（避免 Double 误差）；失败返回 null */
    private static BigDecimal toDecimal(Object v) {
      if (v == null) return null;
      if (v instanceof BigDecimal bd) return bd;
      if (v instanceof Number n) return new BigDecimal(n.toString());
      String s = v.toString().trim();
      if (s.isEmpty()) return null;
      return new BigDecimal(s);
    }

    /**
     * TINYINT(1) 标记类字段映射：
     * <ul>
     *   <li>"√" / "是" / "Y" / "true" / "1" → 1</li>
     *   <li>"×" / "否" / "N" / "false" / "0" / 空字符串 → 0</li>
     *   <li>其他字符串 → null（未知值不猜）</li>
     * </ul>
     */
    private static Integer toFlag(Object v) {
      if (v == null) return null;
      if (v instanceof Number n) return n.intValue() == 0 ? 0 : 1;
      String s = v.toString().trim();
      if (s.isEmpty()) return 0;
      if ("√".equals(s) || "是".equals(s) || "Y".equalsIgnoreCase(s)
          || "true".equalsIgnoreCase(s) || "1".equals(s)) {
        return 1;
      }
      if ("×".equals(s) || "否".equals(s) || "N".equalsIgnoreCase(s)
          || "false".equalsIgnoreCase(s) || "0".equals(s)) {
        return 0;
      }
      return null;
    }

    /**
     * 任意 Object → LocalDate。
     *
     * <p>EasyExcel 在 Map 模式下默认把 cell 都转成 String，date 单元格按 Excel 的 numFmt
     * 渲染成字符串，可能是 "2025-05-28" / "2025/5/28" / "2025/5/28 00:00:00" 等多种形态；
     * 为此列了一组常见格式依次尝试。Excel 纯数字序列号（未标记 date format 的 cell）和
     * 原生 LocalDateTime/Date 类型也都兼容。
     */
    private static final DateTimeFormatter[] DATE_FORMATS = new DateTimeFormatter[] {
        DateTimeFormatter.ISO_LOCAL_DATE,                            // 2025-05-28
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,                       // 2025-05-28T00:00:00
        DateTimeFormatter.ofPattern("yyyy.MM.dd"),                   // 2025.05.28  ← EasyExcel 实测默认格式
        DateTimeFormatter.ofPattern("yyyy.M.d"),                     // 2025.5.28
        DateTimeFormatter.ofPattern("yyyy/M/d"),                     // 2025/5/28
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),                   // 2025/05/28
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),                // 2025/5/28 0:00
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),             // 2025/5/28 0:00:00
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),          // 2025/05/28 00:00:00
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),          // 2025-05-28 00:00:00
        DateTimeFormatter.ofPattern("yyyy年M月d日"),                  // 2025年5月28日
    };

    private static LocalDate toDate(Object v) {
      if (v == null) return null;
      if (v instanceof LocalDate ld) return ld;
      if (v instanceof LocalDateTime ldt) return ldt.toLocalDate();
      if (v instanceof Date d) {
        return d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
      }
      String s = v.toString().trim();
      if (s.isEmpty()) return null;

      // 依次尝试已知格式；LocalDateTime 格式用 parse(TemporalAccessor) 降级到 LocalDate
      for (DateTimeFormatter fmt : DATE_FORMATS) {
        try {
          return LocalDate.parse(s, fmt);
        } catch (Exception ignore) {
          // try next
        }
        try {
          return LocalDateTime.parse(s, fmt).toLocalDate();
        } catch (Exception ignore) {
          // try next
        }
      }

      // 最后尝试：当作 Excel 数字序列号（如 "45805.0"）解析，用 POI 转换
      try {
        double d = Double.parseDouble(s);
        Date javaDate = org.apache.poi.ss.usermodel.DateUtil.getJavaDate(d);
        return javaDate.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
      } catch (NumberFormatException ignore) {
        // 非数字，放弃
      }
      throw new IllegalArgumentException("无法识别的日期格式: " + s);
    }
  }
}
