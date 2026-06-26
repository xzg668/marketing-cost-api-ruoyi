package com.sanhua.marketingcost.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceItemExcelImportRow;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceItemImportResponse.ErrorRow;
import com.sanhua.marketingcost.dto.BindingCandidateBuildResult;
import com.sanhua.marketingcost.dto.ExcelAutoBindingImportLogDto;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.FactorRowRefSaveResult;
import com.sanhua.marketingcost.dto.FactorUploadBatchDto;
import com.sanhua.marketingcost.dto.FactorUploadBatchCreateRequest;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.dto.FormulaFactorRef;
import com.sanhua.marketingcost.dto.LinkedFormulaRow;
import com.sanhua.marketingcost.dto.LinkedFormulaWorkbookParseResult;
import com.sanhua.marketingcost.dto.PriceLinkedAutoBindingWriteRequest;
import com.sanhua.marketingcost.dto.PriceLinkedAutoBindingWriteResult;
import com.sanhua.marketingcost.dto.PriceLinkedImportBatchDetailDto;
import com.sanhua.marketingcost.dto.PriceLinkedImportResultClassifyRequest;
import com.sanhua.marketingcost.dto.PriceLinkedItemDto;
import com.sanhua.marketingcost.dto.PriceLinkedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceVariableBindingRequest;
import com.sanhua.marketingcost.dto.PriceVariableBindingDto;
import com.sanhua.marketingcost.dto.ResolvedFactorRef;
import com.sanhua.marketingcost.dto.StandardBindingCheckRequest;
import com.sanhua.marketingcost.dto.StandardBindingDecision;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.ExcelAutoBindingImportLog;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorQuoteBaseMapping;
import com.sanhua.marketingcost.entity.FactorRowRef;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.dto.PriceLinkedItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.entity.PriceLinkedFormulaChangeLog;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.enums.FactorUploadImportPurpose;
import com.sanhua.marketingcost.enums.FactorPriceConflictStrategy;
import com.sanhua.marketingcost.enums.PriceLinkedImportEffectiveStrategy;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl;
import com.sanhua.marketingcost.formula.normalize.FormulaDisplayRenderer;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.FormulaSyntaxException;
import com.sanhua.marketingcost.formula.normalize.FormulaValidator;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.ExcelAutoBindingImportLogMapper;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.mapper.FactorQuoteBaseMappingMapper;
import com.sanhua.marketingcost.mapper.FactorRowRefMapper;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedFormulaChangeLogMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.PriceLinkedItemService;
import com.sanhua.marketingcost.service.FactorMonthlyPriceUpsertService;
import com.sanhua.marketingcost.service.FactorUploadBatchService;
import com.sanhua.marketingcost.service.PriceLinkedAutoBindingWriteService;
import com.sanhua.marketingcost.service.PriceLinkedBindingCandidateBuilder;
import com.sanhua.marketingcost.service.PriceLinkedFactorWorkbookParser;
import com.sanhua.marketingcost.service.PriceLinkedFormulaFactorRefParser;
import com.sanhua.marketingcost.service.PriceLinkedFormulaFactorRefResolver;
import com.sanhua.marketingcost.service.PriceLinkedFormulaWorkbookParser;
import com.sanhua.marketingcost.service.PriceLinkedImportResultClassifier;
import com.sanhua.marketingcost.service.PriceLinkedStandardBindingService;
import com.sanhua.marketingcost.service.PriceVariableBindingService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceLinkedItemServiceImpl implements PriceLinkedItemService {
  private static final Logger log = LoggerFactory.getLogger(PriceLinkedItemServiceImpl.class);

  /** "订单类型" 列常量；任一忽略大小写后匹配 "固定" 则走 fixed 分支，其余走 linked。 */
  private static final String ORDER_TYPE_FIXED = "固定";

  /** Excel 数据行 1-based 行号 = 表头行数 + 偏移；源表只有 1 行表头。 */
  private static final int HEADER_ROW_NUMBER = 1;
  private static final int EXCEL_ROW_OFFSET = HEADER_ROW_NUMBER + 1;

  private static final Pattern SHEET_REF_PATTERN =
      Pattern.compile("(?:'([^']+)'|([^'!+\\-*/(),]+))!\\$?([A-Z]+)\\$?(\\d+)");

  private final PriceLinkedItemMapper itemMapper;
  private final PriceFixedItemMapper fixedItemMapper;
  private final FinanceBasePriceMapper financeBasePriceMapper;
  private final PriceVariableMapper priceVariableMapper;
  private final PriceVariableBindingService priceVariableBindingService;
  private final FactorVariableRegistryImpl factorVariableRegistry;
  private final FormulaNormalizer formulaNormalizer;
  /** Plan B T6：formula_expr_cn 由 renderer 派生，DTO 组装时反向映射 [code] → 中文 */
  private final FormulaDisplayRenderer formulaDisplayRenderer;
  /** 方案 A 加严：Normalizer 之外再跑 Validator，抓相邻 value 缺运算符、未知 code 等结构错 */
  private final FormulaValidator formulaValidator;
  @Autowired(required = false)
  private PriceLinkedFormulaChangeLogMapper formulaChangeLogMapper;
  @Autowired(required = false)
  private PriceLinkedFactorWorkbookParser factorWorkbookParser;
  @Autowired(required = false)
  private FactorUploadBatchService factorUploadBatchService;
  @Autowired(required = false)
  private FactorMonthlyPriceUpsertService factorMonthlyPriceUpsertService;
  @Autowired(required = false)
  private PriceLinkedFormulaWorkbookParser formulaWorkbookParser;
  @Autowired(required = false)
  private PriceLinkedFormulaFactorRefParser formulaFactorRefParser;
  @Autowired(required = false)
  private PriceLinkedFormulaFactorRefResolver formulaFactorRefResolver;
  @Autowired(required = false)
  private PriceLinkedBindingCandidateBuilder bindingCandidateBuilder;
  @Autowired(required = false)
  private PriceLinkedStandardBindingService standardBindingService;
  @Autowired(required = false)
  private PriceLinkedAutoBindingWriteService autoBindingWriteService;
  @Autowired(required = false)
  private PriceLinkedImportResultClassifier importResultClassifier;
  @Autowired(required = false)
  private FactorUploadBatchMapper factorUploadBatchMapper;
  @Autowired(required = false)
  private FactorRowRefMapper factorRowRefMapper;
  @Autowired(required = false)
  private FactorIdentityMapper factorIdentityMapper;
  @Autowired(required = false)
  private FactorMonthlyPriceMapper factorMonthlyPriceMapper;
  @Autowired(required = false)
  private FactorQuoteBaseMappingMapper factorQuoteBaseMappingMapper;
  @Autowired(required = false)
  private ExcelAutoBindingImportLogMapper autoBindingImportLogMapper;
  private boolean excelAutoBindingEnabled = true;

  public PriceLinkedItemServiceImpl(
      PriceLinkedItemMapper itemMapper,
      PriceFixedItemMapper fixedItemMapper,
      FinanceBasePriceMapper financeBasePriceMapper,
      PriceVariableMapper priceVariableMapper,
      PriceVariableBindingService priceVariableBindingService,
      FactorVariableRegistryImpl factorVariableRegistry,
      FormulaNormalizer formulaNormalizer,
      FormulaDisplayRenderer formulaDisplayRenderer,
      FormulaValidator formulaValidator) {
    this.itemMapper = itemMapper;
    this.fixedItemMapper = fixedItemMapper;
    this.financeBasePriceMapper = financeBasePriceMapper;
    this.priceVariableMapper = priceVariableMapper;
    this.priceVariableBindingService = priceVariableBindingService;
    this.factorVariableRegistry = factorVariableRegistry;
    this.formulaNormalizer = formulaNormalizer;
    this.formulaDisplayRenderer = formulaDisplayRenderer;
    this.formulaValidator = formulaValidator;
  }

  @Override
  public List<PriceLinkedItemDto> list(String pricingMonth, String materialCode) {
    return list(pricingMonth, materialCode, false);
  }

  @Override
  public List<PriceLinkedItemDto> list(
      String pricingMonth, String materialCode, boolean includeHistory) {
    String resolvedMonth = resolvePricingMonth(pricingMonth);
    var query = Wrappers.lambdaQuery(PriceLinkedItem.class);
    if (StringUtils.hasText(resolvedMonth)) {
      query.eq(PriceLinkedItem::getPricingMonth, resolvedMonth);
    }
    if (StringUtils.hasText(materialCode)) {
      query.like(PriceLinkedItem::getMaterialCode, materialCode.trim());
    }
    if (!includeHistory) {
      query.isNull(PriceLinkedItem::getEffectiveTo);
    }
    query.orderByAsc(PriceLinkedItem::getId);
    return itemMapper.selectList(query).stream()
        .map(this::toDto)
        .toList();
  }

  @Override
  public PageResult<PriceLinkedItemDto> page(
      String pricingMonth,
      String materialCode,
      boolean includeHistory,
      int page,
      int pageSize) {
    String resolvedMonth = resolvePricingMonth(pricingMonth);
    var query = Wrappers.lambdaQuery(PriceLinkedItem.class);
    if (StringUtils.hasText(resolvedMonth)) {
      query.eq(PriceLinkedItem::getPricingMonth, resolvedMonth);
    }
    if (StringUtils.hasText(materialCode)) {
      query.like(PriceLinkedItem::getMaterialCode, materialCode.trim());
    }
    if (!includeHistory) {
      query.isNull(PriceLinkedItem::getEffectiveTo);
    }
    query.orderByAsc(PriceLinkedItem::getId);
    Page<PriceLinkedItem> result =
        itemMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
    return new PageResult<>(
        result.getRecords().stream().map(this::toDto).toList(),
        result.getTotal());
  }

  private long normalizePage(int page) {
    return page < 1 ? 1L : page;
  }

  private long normalizePageSize(int pageSize) {
    if (pageSize < 1) {
      return 20L;
    }
    return Math.min(pageSize, 200);
  }

  @Override
  public PriceLinkedItemDto create(PriceLinkedItemUpdateRequest request) {
    if (request == null) {
      return null;
    }
    PriceLinkedItem item = new PriceLinkedItem();
    merge(item, request);
    if (!StringUtils.hasText(item.getPricingMonth())) {
      item.setPricingMonth(resolvePricingMonth(null));
    }
    if (!StringUtils.hasText(item.getPricingMonth())) {
      return null;
    }
    // 手工新增同样走 BU 注入，保持和 import 路径一致
    applyCurrentBusinessUnit(item);
    itemMapper.insert(item);
    return toDto(item);
  }

  @Override
  public PriceLinkedItemDto update(Long id, PriceLinkedItemUpdateRequest request) {
    if (id == null) {
      return null;
    }
    PriceLinkedItem item = itemMapper.selectById(id);
    if (item == null) {
      return null;
    }
    String oldFormulaExpr = item.getFormulaExpr();
    String oldFormulaExprCn = item.getFormulaExprCn();
    merge(item, request);
    itemMapper.updateById(item);
    logFormulaChangeIfNeeded(item, request, oldFormulaExpr, oldFormulaExprCn);
    return toDto(item);
  }

  void setFormulaChangeLogMapper(PriceLinkedFormulaChangeLogMapper formulaChangeLogMapper) {
    this.formulaChangeLogMapper = formulaChangeLogMapper;
  }

  @Value("${cost.linked.excel-auto-binding.enabled:true}")
  void setExcelAutoBindingEnabled(boolean excelAutoBindingEnabled) {
    this.excelAutoBindingEnabled = excelAutoBindingEnabled;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<PriceLinkedItemDto> importItems(PriceLinkedItemImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    String fallbackMonth = StringUtils.hasText(request.getPricingMonth())
        ? request.getPricingMonth().trim()
        : null;
    List<PriceLinkedItemDto> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null || !StringUtils.hasText(row.getMaterialCode())) {
        continue;
      }
      String pricingMonth = StringUtils.hasText(row.getPricingMonth())
          ? row.getPricingMonth().trim()
          : fallbackMonth;
      if (!StringUtils.hasText(pricingMonth)) {
        continue;
      }
      PriceLinkedItem item = findExisting(pricingMonth, row);
      if (item == null) {
        item = new PriceLinkedItem();
        item.setPricingMonth(pricingMonth);
        fillItem(item, pricingMonth, row);
        // 写入边界：显式把当前登录账号的业务单元写进实体。
        // 依赖 MetaObjectHandler 不稳（部分调用链下 SecurityContext 拿不到 BU），
        // 且导入是典型"归属当前会话"的语义，在入口显式注入最直接、与前端筛选一致。
        applyCurrentBusinessUnit(item);
        itemMapper.insert(item);
      } else {
        fillItem(item, pricingMonth, row);
        // 重新导入时同样把所属业务单元刷成当前登录值，修复历史 NULL 行（看不见）的问题
        applyCurrentBusinessUnit(item);
        itemMapper.updateById(item);
      }
      imported.add(toDto(item));
    }
    return imported;
  }

  @Override
  public boolean delete(Long id) {
    if (id == null) {
      return false;
    }
    return itemMapper.deleteById(id) > 0;
  }

  @Override
  public List<FactorUploadBatchDto> listImportHistory(
      String pricingMonth, String businessUnitType, Integer limit) {
    return listImportHistory(pricingMonth, businessUnitType, null, false, limit);
  }

  @Override
  public List<FactorUploadBatchDto> listImportHistory(
      String pricingMonth,
      String businessUnitType,
      String uploadedBy,
      Boolean includeAllUploaders,
      Integer limit) {
    if (factorUploadBatchMapper == null) {
      return List.of();
    }
    int rowLimit = limit == null ? 10 : Math.min(Math.max(limit, 1), 50);
    var query = Wrappers.lambdaQuery(FactorUploadBatch.class)
        .eq(FactorUploadBatch::getImportType, "MONTHLY_LINKED_FACTOR");
    if (StringUtils.hasText(pricingMonth)) {
      query.eq(FactorUploadBatch::getPriceMonth, pricingMonth.trim());
    }
    String resolvedBusinessUnit = resolveOptionalBusinessUnitType(businessUnitType);
    if (StringUtils.hasText(resolvedBusinessUnit)) {
      query.eq(FactorUploadBatch::getBusinessUnitType, resolvedBusinessUnit);
    }
    String resolvedUploadedBy = resolveImportHistoryUploadedBy(uploadedBy, includeAllUploaders);
    if (StringUtils.hasText(resolvedUploadedBy)) {
      query.eq(FactorUploadBatch::getUploadedBy, resolvedUploadedBy);
    }
    query.orderByDesc(FactorUploadBatch::getStartedAt)
        .orderByDesc(FactorUploadBatch::getId)
        .last("LIMIT " + rowLimit);
    return factorUploadBatchMapper.selectList(query).stream()
        .map(this::toBatchDto)
        .toList();
  }

  @Override
  public PriceLinkedImportBatchDetailDto getImportBatchDetail(Long factorUploadBatchId) {
    if (factorUploadBatchId == null || factorUploadBatchMapper == null) {
      return null;
    }
    FactorUploadBatch batch = factorUploadBatchMapper.selectById(factorUploadBatchId);
    if (batch == null) {
      return null;
    }
    if (!canViewImportBatch(batch)) {
      return null;
    }
    PriceLinkedImportBatchDetailDto detail = new PriceLinkedImportBatchDetailDto();
    detail.setBatch(toBatchDto(batch));
    detail.setBatchId(String.valueOf(batch.getId()));
    detail.setFactorUploadBatchId(batch.getId());
    detail.setImportPurpose(batch.getImportPurpose());
    detail.setFactorRecognizedCount(nullToZero(batch.getFactorRowCount()));
    detail.setEffectiveStrategy(batch.getEffectiveStrategy());
    detail.setFormulaEffectiveDate(defaultFormulaEffectiveDateText(batch.getPriceMonth()));
    detail.setFactorPriceConflictStrategy(FactorPriceConflictStrategy.KEEP_EXISTING.getCode());
    detail.setLinkedCount(nullToZero(batch.getLinkedRowCount()));
    detail.setLinkedVersionCreatedCount(detail.getLinkedCount());
    detail.setAutoBindingCount(nullToZero(batch.getAutoBindingCount()));
    detail.setBindingErrorCount(nullToZero(batch.getErrorCount()));
    detail.getFactorRows().addAll(loadPersistedFactorRows(batch));
    detail.setQuoteBaseRecognizedCount((int) detail.getFactorRows().stream()
        .filter(row -> "RECOGNIZED".equalsIgnoreCase(row.getQuoteBaseDetectStatus()))
        .count());
    detail.setQuoteBaseConflictCount((int) detail.getFactorRows().stream()
        .filter(row -> "CONFLICT".equalsIgnoreCase(row.getQuoteBaseDetectStatus()))
        .count());
    detail.setQuoteBaseUnrecognizedCount((int) detail.getFactorRows().stream()
        .filter(row -> "UNRECOGNIZED".equalsIgnoreCase(row.getQuoteBaseDetectStatus()))
        .count());
    List<ExcelAutoBindingImportLogDto> logs = loadPersistedBindingLogs(batch.getId());
    detail.getBindingLogs().addAll(logs);
    detail.setManualSkippedCount((int) logs.stream()
        .filter(log -> "SKIPPED_MANUAL".equalsIgnoreCase(log.getAction()))
        .count());
    detail.setConflictBindingCount((int) logs.stream()
        .filter(log -> "FAILED".equalsIgnoreCase(log.getStatus()))
        .count());
    detail.getBindingErrors().addAll(logs.stream()
        .filter(log -> !"SUCCESS".equalsIgnoreCase(log.getStatus()))
        .map(this::toBindingError)
        .toList());
    return detail;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PriceItemImportResponse importExcel(
      InputStream input, String pricingMonth, boolean overwriteManual) {
    return importExcel(input, pricingMonth, overwriteManual, null, null);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PriceItemImportResponse importExcel(
      InputStream input,
      String pricingMonth,
      boolean overwriteManual,
      String businessUnitType,
      String sourceFileName) {
    return importExcel(input, pricingMonth, overwriteManual, businessUnitType, sourceFileName, null);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PriceItemImportResponse importExcel(
      InputStream input,
      String pricingMonth,
      boolean overwriteManual,
      String businessUnitType,
      String sourceFileName,
      String effectiveStrategy) {
    return importExcel(
        input, pricingMonth, overwriteManual, businessUnitType, sourceFileName, effectiveStrategy,
        null, null);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PriceItemImportResponse importExcel(
      InputStream input,
      String pricingMonth,
      boolean overwriteManual,
      String businessUnitType,
      String sourceFileName,
      String effectiveStrategy,
      String formulaEffectiveDate,
      String factorPriceConflictStrategy) {
    PriceItemImportResponse response = new PriceItemImportResponse();
    response.setBatchId(UUID.randomUUID().toString());
    String strategy = normalizeEffectiveStrategy(effectiveStrategy);
    response.setEffectiveStrategy(strategy);
    response.setImportPurpose(importPurposeForStrategy(strategy));
    if (!StringUtils.hasText(pricingMonth)) {
      response.setFactorPriceConflictStrategy(
          FactorPriceConflictStrategy.KEEP_EXISTING.getCode());
      response.getErrors().add(new ErrorRow(null, null, null, "pricingMonth 必填"));
      response.setSkipped(1);
      return response;
    }
    String month = pricingMonth.trim();
    LocalDate resolvedFormulaEffectiveDate =
        resolveFormulaEffectiveDate(month, formulaEffectiveDate);
    String resolvedFactorConflictStrategy =
        normalizeFactorPriceConflictStrategy(factorPriceConflictStrategy);
    response.setFormulaEffectiveDate(resolvedFormulaEffectiveDate.toString());
    response.setFactorPriceConflictStrategy(resolvedFactorConflictStrategy);
    if (input == null) {
      response.getErrors().add(new ErrorRow(null, null, null, "Excel 流为空"));
      response.setSkipped(1);
      return response;
    }
    byte[] excelBytes;
    try {
      excelBytes = input.readAllBytes();
    } catch (IOException e) {
      response.getErrors().add(new ErrorRow(null, null, null, "Excel 读取失败: " + e.getMessage()));
      response.setSkipped(1);
      return response;
    }
    String resolvedBusinessUnitType = resolveBusinessUnitType(businessUnitType);
    V2ImportContext v2Context = excelAutoBindingEnabled
        ? prepareV2ImportContext(
            excelBytes, month, resolvedBusinessUnitType, sourceFileName, strategy,
            resolvedFactorConflictStrategy, response)
        : V2ImportContext.disabled();
    Map<Integer, AutoBindingPlan> autoBindingPlans = !excelAutoBindingEnabled || v2Context.enabled()
        ? Map.of()
        : extractAutoBindingPlans(excelBytes, month);
    List<CollectedImportRow> rows = new ArrayList<>();
    List<ErrorRow> parseErrors = new ArrayList<>();
    Integer linkedSheetNo = findLinkedImportSheetNo(excelBytes);
    try {
      EasyExcel.read(new ByteArrayInputStream(excelBytes), PriceItemExcelImportRow.class,
              new CollectingListener(rows, parseErrors))
          .sheet(linkedSheetNo == null ? 0 : linkedSheetNo)
          .headRowNumber(HEADER_ROW_NUMBER)
          .doRead();
    } catch (RuntimeException e) {
      response.getErrors().add(new ErrorRow(
          null, null, null, "Excel 解析失败: " + e.getMessage()));
      response.setSkipped(response.getSkipped() + 1);
      return response;
    }

    for (CollectedImportRow collected : rows) {
      PriceItemExcelImportRow row = collected.row();
      int excelRow = collected.rowNumber();
      String validateError = validateRow(row);
      if (validateError != null) {
        response.getErrors().add(new ErrorRow(
            excelRow,
            row == null ? null : row.getMaterialCode(),
            row == null ? null : row.getOrderType(),
            validateError));
        response.setSkipped(response.getSkipped() + 1);
        continue;
      }
      // orderType 为 "固定" → fixed 分支；其余（空 / "联动"）默认走 linked
      if (ORDER_TYPE_FIXED.equals(trim(row.getOrderType()))) {
        upsertFixed(row, response);
        response.setFixedCount(response.getFixedCount() + 1);
      } else {
        V2BindingPlan v2Plan = v2Context.enabled() ? v2Context.plansByExcelRow().get(excelRow) : null;
        // 联动分支：公式不合法则跳过，不入库
        String formulaSource = formulaSourceForNormalize(
            row, v2Plan, v2Context.importedFactorVariableByAlias());
        FormulaTaxNormalization taxNormalization = normalizeExcelFormulaTax(formulaSource);
        formulaSource = taxNormalization.formula();
        if (taxNormalization.finalVatDivisorStripped()) {
          row.setTaxIncluded("0");
        }
        String normalizedFormula = tryNormalize(formulaSource);
        if (normalizedFormula == null && formulaSource != row.getFormulaExpr()) {
          formulaSource = row.getFormulaExpr();
          normalizedFormula = tryNormalize(formulaSource);
        }
        if (normalizedFormula == null) {
          response.getErrors().add(new ErrorRow(
              excelRow, row.getMaterialCode(), row.getOrderType(),
              "联动公式非法或无法解析: " + formulaSource));
          response.setSkipped(response.getSkipped() + 1);
          continue;
        }
        applyExcelGoldenPrice(row, v2Plan);
        LinkedImportOutcome linkedOutcome;
        try {
          linkedOutcome = upsertLinked(
              row, month, normalizedFormula, resolvedFormulaEffectiveDate, resolvedBusinessUnitType);
        } catch (IllegalArgumentException ex) {
          response.getErrors().add(new ErrorRow(
              excelRow, row.getMaterialCode(), row.getOrderType(), ex.getMessage()));
          response.setSkipped(response.getSkipped() + 1);
          continue;
        }
        if (linkedOutcome.skipped()) {
          response.setLinkedSkippedCount(response.getLinkedSkippedCount() + 1);
          continue;
        }
        PriceLinkedItem item = linkedOutcome.item();
        if (linkedOutcome.created()) {
          response.setLinkedCreatedCount(response.getLinkedCreatedCount() + 1);
        }
        if (linkedOutcome.updated()) {
          response.setLinkedUpdatedCount(response.getLinkedUpdatedCount() + 1);
        }
        if (v2Context.enabled()) {
          applyV2AutoBindings(item, v2Plan, month,
              resolvedBusinessUnitType, excelRow, overwriteManualForStrategy(strategy, overwriteManual), response);
        } else {
          applyAutoBindings(item, row, normalizedFormula, autoBindingPlans.get(excelRow), month,
              excelRow, overwriteManualForStrategy(strategy, overwriteManual), response);
        }
        response.setLinkedCount(response.getLinkedCount() + 1);
      }
    }

    if (!parseErrors.isEmpty()) {
      response.getErrors().addAll(parseErrors);
      response.setSkipped(response.getSkipped() + parseErrors.size());
    }
    finalizeImportBatch(v2Context.factorUploadBatchId(), response);
    applyVersionedSummaryAliases(response);
    return response;
  }

  private void finalizeImportBatch(Long factorUploadBatchId, PriceItemImportResponse response) {
    if (factorUploadBatchId == null || factorUploadBatchMapper == null || response == null) {
      return;
    }
    FactorUploadBatch batch = factorUploadBatchMapper.selectById(factorUploadBatchId);
    if (batch == null) {
      return;
    }
    int errorCount = response.getSkipped() + response.getBindingErrorCount();
    batch.setLinkedRowCount(response.getLinkedCount());
    batch.setAutoBindingCount(response.getAutoBindingCount());
    batch.setWarningCount(response.getManualSkippedCount()
        + response.getConflictBindingCount()
        + response.getQuoteBaseConflictCount());
    batch.setErrorCount(errorCount);
    batch.setStatus(errorCount > 0 ? "PARTIAL" : "SUCCESS");
    batch.setFinishedAt(LocalDateTime.now());
    batch.setUpdatedAt(LocalDateTime.now());
    factorUploadBatchMapper.updateById(batch);
  }

  private String normalizeEffectiveStrategy(String effectiveStrategy) {
    String normalized = trim(effectiveStrategy);
    if (PriceLinkedImportEffectiveStrategy.APPEND_ONLY.getCode().equals(normalized)) {
      return normalized;
    }
    return PriceLinkedImportEffectiveStrategy.OVERRIDE_EFFECTIVE.getCode();
  }

  private LocalDate resolveFormulaEffectiveDate(
      String pricingMonth, String formulaEffectiveDate) {
    String raw = StringUtils.hasText(formulaEffectiveDate)
        ? formulaEffectiveDate.trim()
        : pricingMonth.trim() + "-01";
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException(
          "formulaEffectiveDate 格式错误，应为 yyyy-MM-dd: " + raw);
    }
  }

  private String defaultFormulaEffectiveDateText(String pricingMonth) {
    if (!StringUtils.hasText(pricingMonth)) {
      return null;
    }
    try {
      return resolveFormulaEffectiveDate(pricingMonth.trim(), null).toString();
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private String normalizeFactorPriceConflictStrategy(String factorPriceConflictStrategy) {
    String normalized = trim(factorPriceConflictStrategy);
    if (!StringUtils.hasText(normalized)) {
      return FactorPriceConflictStrategy.KEEP_EXISTING.getCode();
    }
    String upper = normalized.toUpperCase(java.util.Locale.ROOT);
    if (FactorPriceConflictStrategy.KEEP_EXISTING.getCode().equals(upper)
        || FactorPriceConflictStrategy.OVERWRITE.getCode().equals(upper)) {
      return upper;
    }
    throw new IllegalArgumentException(
        "factorPriceConflictStrategy 非法，仅支持 KEEP_EXISTING / OVERWRITE: "
            + factorPriceConflictStrategy);
  }

  private void applyVersionedSummaryAliases(PriceItemImportResponse response) {
    if (response == null) {
      return;
    }
    if (response.getMonthlyPriceConflictCount() == 0) {
      response.setMonthlyPriceConflictCount(response.getMonthlyPriceSkippedCount());
    }
    if (response.getMonthlyPriceOverwriteCount() == 0) {
      response.setMonthlyPriceOverwriteCount(response.getMonthlyPriceUpdatedCount());
    }
    response.setLinkedVersionCreatedCount(response.getLinkedCreatedCount());
    response.setLinkedUnchangedSkippedCount(response.getLinkedSkippedCount());
    response.setLinkedExpiredCount(response.getLinkedUpdatedCount());
  }

  private boolean overwriteManualForStrategy(String effectiveStrategy, boolean overwriteManual) {
    return PriceLinkedImportEffectiveStrategy.OVERRIDE_EFFECTIVE.getCode().equals(effectiveStrategy)
        || overwriteManual;
  }

  private String importPurposeForStrategy(String effectiveStrategy) {
    if (PriceLinkedImportEffectiveStrategy.APPEND_ONLY.getCode().equals(effectiveStrategy)) {
      return FactorUploadImportPurpose.LINKED_APPEND_ONLY.getCode();
    }
    return FactorUploadImportPurpose.LINKED_OVERRIDE_EFFECTIVE.getCode();
  }

  private List<FactorMonthlyPriceUpsertResult.RowResult> loadPersistedFactorRows(
      FactorUploadBatch batch) {
    if (factorRowRefMapper == null) {
      return List.of();
    }
    Long factorUploadBatchId = batch == null ? null : batch.getId();
    if (factorUploadBatchId == null) {
      return List.of();
    }
    List<FactorRowRef> refs = factorRowRefMapper.selectList(
        Wrappers.lambdaQuery(FactorRowRef.class)
            .eq(FactorRowRef::getFactorUploadBatchId, factorUploadBatchId)
            .orderByAsc(FactorRowRef::getSourceSheetName)
            .orderByAsc(FactorRowRef::getSourceRowNumber)
            .orderByAsc(FactorRowRef::getId));
    if (refs.isEmpty()) {
      return List.of();
    }
    Map<Long, FactorIdentity> identities = loadIdentities(refs);
    Map<Long, FactorMonthlyPrice> monthlyPrices = loadMonthlyPrices(refs);
    Map<Long, FactorQuoteBaseMapping> quoteBaseMappings = loadQuoteBaseMappings(refs);
    List<FactorMonthlyPriceUpsertResult.RowResult> rows = new ArrayList<>();
    for (FactorRowRef ref : refs) {
      FactorIdentity identity = identities.get(ref.getFactorIdentityId());
      FactorMonthlyPrice monthlyPrice = monthlyPrices.get(ref.getFactorMonthlyPriceId());
      FactorQuoteBaseMapping quoteBaseMapping = quoteBaseMappings.get(ref.getFactorIdentityId());
      FactorMonthlyPriceUpsertResult.RowResult row =
          new FactorMonthlyPriceUpsertResult.RowResult();
      row.setSourceSheetName(ref.getSourceSheetName());
      row.setSourceRowNumber(ref.getSourceRowNumber());
      row.setFactorIdentityId(ref.getFactorIdentityId());
      row.setFactorMonthlyPriceId(ref.getFactorMonthlyPriceId());
      row.setFactorSeqNo(firstText(ref.getFactorSeqNo(),
          identity == null ? null : identity.getFactorSeqNo()));
      row.setFactorName(firstText(ref.getFactorName(),
          identity == null ? null : identity.getFactorName()));
      row.setShortName(firstText(ref.getShortName(),
          identity == null ? null : identity.getShortName()));
      row.setPriceSource(firstText(ref.getPriceSource(),
          identity == null ? null : identity.getPriceSource()));
      row.setNewPrice(monthlyPrice != null && monthlyPrice.getPrice() != null
          ? monthlyPrice.getPrice()
          : ref.getPrice());
      row.setOriginalPrice(ref.getOriginalPrice());
      row.setUnit(ref.getUnit());
      row.setUploadedBy(batch.getUploadedBy());
      row.setUploadedAt(firstDateTime(batch.getFinishedAt(), batch.getStartedAt()));
      row.setMonthlyPriceAction("IMPORTED");
      row.setIdentityAction("PERSISTED");
      applyPersistedQuoteBaseMapping(row, quoteBaseMapping);
      rows.add(row);
    }
    return rows;
  }

  private void applyPersistedQuoteBaseMapping(
      FactorMonthlyPriceUpsertResult.RowResult row,
      FactorQuoteBaseMapping mapping) {
    if (row == null) {
      return;
    }
    if (mapping == null) {
      row.setQuoteBaseDetectStatus("UNRECOGNIZED");
      row.setQuoteBaseMatchSource("AUTO");
      row.setQuoteBaseDetectMessage("未命中报价单公共基价映射");
      return;
    }
    row.setQuoteBaseDetectStatus("RECOGNIZED");
    row.setQuoteBaseQuoteFieldCode(mapping.getQuoteFieldCode());
    row.setQuoteBaseQuoteFieldName(mapping.getQuoteFieldName());
    row.setQuoteBaseVariableCode(mapping.getVariableCode());
    row.setQuoteBaseMatchedKeyword(mapping.getMatchedKeyword());
    row.setQuoteBaseMatchSource(mapping.getMatchSource());
    row.setQuoteBaseDetectMessage("已绑定到报价单公共基价字段");
  }

  private List<ExcelAutoBindingImportLogDto> loadPersistedBindingLogs(Long factorUploadBatchId) {
    if (autoBindingImportLogMapper == null) {
      return List.of();
    }
    return autoBindingImportLogMapper.selectList(
        Wrappers.lambdaQuery(ExcelAutoBindingImportLog.class)
            .eq(ExcelAutoBindingImportLog::getFactorUploadBatchId, factorUploadBatchId)
            .orderByAsc(ExcelAutoBindingImportLog::getId))
        .stream()
        .map(this::toImportLogDto)
        .toList();
  }

  private Map<Long, FactorIdentity> loadIdentities(List<FactorRowRef> refs) {
    if (factorIdentityMapper == null) {
      return Map.of();
    }
    Set<Long> ids = new HashSet<>();
    for (FactorRowRef ref : refs) {
      if (ref.getFactorIdentityId() != null) {
        ids.add(ref.getFactorIdentityId());
      }
    }
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<Long, FactorIdentity> result = new HashMap<>();
    for (FactorIdentity identity : factorIdentityMapper.selectBatchIds(ids)) {
      result.put(identity.getId(), identity);
    }
    return result;
  }

  private Map<Long, FactorMonthlyPrice> loadMonthlyPrices(List<FactorRowRef> refs) {
    if (factorMonthlyPriceMapper == null) {
      return Map.of();
    }
    Set<Long> ids = new HashSet<>();
    for (FactorRowRef ref : refs) {
      if (ref.getFactorMonthlyPriceId() != null) {
        ids.add(ref.getFactorMonthlyPriceId());
      }
    }
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<Long, FactorMonthlyPrice> result = new HashMap<>();
    for (FactorMonthlyPrice monthlyPrice : factorMonthlyPriceMapper.selectBatchIds(ids)) {
      result.put(monthlyPrice.getId(), monthlyPrice);
    }
    return result;
  }

  private Map<Long, FactorQuoteBaseMapping> loadQuoteBaseMappings(List<FactorRowRef> refs) {
    if (factorQuoteBaseMappingMapper == null) {
      return Map.of();
    }
    Set<Long> ids = new HashSet<>();
    for (FactorRowRef ref : refs) {
      if (ref.getFactorIdentityId() != null) {
        ids.add(ref.getFactorIdentityId());
      }
    }
    if (ids.isEmpty()) {
      return Map.of();
    }
    List<FactorQuoteBaseMapping> mappings = factorQuoteBaseMappingMapper.selectList(
        Wrappers.lambdaQuery(FactorQuoteBaseMapping.class)
            .in(FactorQuoteBaseMapping::getFactorIdentityId, ids)
            .eq(FactorQuoteBaseMapping::getEnabled, 1)
            .eq(FactorQuoteBaseMapping::getDeleted, 0)
            .orderByAsc(FactorQuoteBaseMapping::getId));
    Map<Long, FactorQuoteBaseMapping> result = new HashMap<>();
    for (FactorQuoteBaseMapping mapping : mappings) {
      // 一个影响因素正常只对应一个 OA 基价字段；历史异常多条时取最早启用记录展示。
      result.putIfAbsent(mapping.getFactorIdentityId(), mapping);
    }
    return result;
  }

  private FactorUploadBatchDto toBatchDto(FactorUploadBatch batch) {
    FactorUploadBatchDto dto = new FactorUploadBatchDto();
    if (batch == null) {
      return dto;
    }
    dto.setId(batch.getId());
    dto.setBatchNo(batch.getBatchNo());
    dto.setImportType(batch.getImportType());
    dto.setImportPurpose(batch.getImportPurpose());
    dto.setEffectiveStrategy(batch.getEffectiveStrategy());
    dto.setPriceMonth(batch.getPriceMonth());
    dto.setBusinessUnitType(batch.getBusinessUnitType());
    dto.setFileName(batch.getFileName());
    dto.setUploadedBy(batch.getUploadedBy());
    dto.setStatus(batch.getStatus());
    dto.setFactorSheetCount(batch.getFactorSheetCount());
    dto.setLinkedSheetCount(batch.getLinkedSheetCount());
    dto.setFactorRowCount(batch.getFactorRowCount());
    dto.setLinkedRowCount(batch.getLinkedRowCount());
    dto.setAutoBindingCount(batch.getAutoBindingCount());
    dto.setWarningCount(batch.getWarningCount());
    dto.setErrorCount(batch.getErrorCount());
    dto.setStartedAt(batch.getStartedAt());
    dto.setFinishedAt(batch.getFinishedAt());
    return dto;
  }

  private ExcelAutoBindingImportLogDto toImportLogDto(ExcelAutoBindingImportLog log) {
    ExcelAutoBindingImportLogDto dto = new ExcelAutoBindingImportLogDto();
    dto.setId(log.getId());
    dto.setFactorUploadBatchId(log.getFactorUploadBatchId());
    dto.setLinkedItemId(log.getLinkedItemId());
    dto.setMaterialCode(log.getMaterialCode());
    dto.setSupplierCode(log.getSupplierCode());
    dto.setTokenName(log.getTokenName());
    dto.setAction(log.getAction());
    dto.setStatus(log.getStatus());
    dto.setFactorIdentityId(log.getFactorIdentityId());
    dto.setFactorMonthlyPriceId(log.getFactorMonthlyPriceId());
    dto.setSourceWorkbookName(log.getSourceWorkbookName());
    dto.setSourceSheetName(log.getSourceSheetName());
    dto.setSourceCellRef(log.getSourceCellRef());
    dto.setExcelFormula(log.getExcelFormula());
    dto.setMessage(log.getMessage());
    dto.setCreatedAt(log.getCreatedAt());
    return dto;
  }

  private PriceItemImportResponse.BindingError toBindingError(ExcelAutoBindingImportLogDto log) {
    PriceItemImportResponse.BindingError error = new PriceItemImportResponse.BindingError();
    error.setMaterialCode(log.getMaterialCode());
    error.setTokenName(log.getTokenName());
    error.setFormula(log.getExcelFormula());
    error.setNewFactorIdentity(log.getFactorIdentityId());
    error.setReason(log.getMessage());
    error.setRefSheet(log.getSourceSheetName());
    error.setRefRow(parseCellRow(log.getSourceCellRef()));
    return error;
  }

  private Integer parseCellRow(String cellRef) {
    if (!StringUtils.hasText(cellRef)) {
      return null;
    }
    Matcher matcher = Pattern.compile("\\d+").matcher(cellRef);
    Integer row = null;
    while (matcher.find()) {
      row = Integer.valueOf(matcher.group());
    }
    return row;
  }

  private String firstText(String first, String fallback) {
    return StringUtils.hasText(first) ? first : fallback;
  }

  private LocalDateTime firstDateTime(LocalDateTime first, LocalDateTime fallback) {
    return first != null ? first : fallback;
  }

  private int nullToZero(Integer value) {
    return value == null ? 0 : value;
  }

  private String resolveOptionalBusinessUnitType(String requestedBusinessUnitType) {
    if (StringUtils.hasText(requestedBusinessUnitType)) {
      return requestedBusinessUnitType.trim();
    }
    String current = BusinessUnitContext.getCurrentBusinessUnitType();
    return StringUtils.hasText(current) ? current.trim() : null;
  }

  private String resolveImportHistoryUploadedBy(String uploadedBy, Boolean includeAllUploaders) {
    boolean viewAll = Boolean.TRUE.equals(includeAllUploaders) && canViewAllUploaders();
    if (viewAll) {
      return StringUtils.hasText(uploadedBy) ? uploadedBy.trim() : null;
    }
    String current = currentOperator();
    return StringUtils.hasText(current) ? current : null;
  }

  private boolean canViewImportBatch(FactorUploadBatch batch) {
    if (batch == null || canViewAllUploaders()) {
      return true;
    }
    String current = currentOperator();
    return StringUtils.hasText(current)
        && StringUtils.hasText(batch.getUploadedBy())
        && current.equals(batch.getUploadedBy());
  }

  private boolean canViewAllUploaders() {
    if (BusinessUnitContext.isAdmin()) {
      return true;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication.getAuthorities() == null) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .map(authority -> authority == null ? "" : authority.getAuthority())
        .anyMatch(authority ->
            "*:*:*".equals(authority)
                || "price:linked-item:import-history:all".equals(authority)
                || "price:linked-item:admin".equals(authority)
                || "price:finance-base:import-history:all".equals(authority)
                || "price:finance-base:admin".equals(authority));
  }

  private V2ImportContext prepareV2ImportContext(
      byte[] excelBytes,
      String priceMonth,
      String businessUnitType,
      String sourceFileName,
      String effectiveStrategy,
      String factorPriceConflictStrategy,
      PriceItemImportResponse response) {
    if (!v2AutoBindingReady() || excelBytes == null || excelBytes.length == 0) {
      return V2ImportContext.disabled();
    }
    String fileName = StringUtils.hasText(sourceFileName) ? sourceFileName.trim() : "monthly-import.xlsx";
    FactorWorkbookParseResult factorParseResult =
        factorWorkbookParser.parse(new ByteArrayInputStream(excelBytes), fileName);
    if (factorParseResult == null || factorParseResult.getSheets().isEmpty()) {
      return V2ImportContext.disabled();
    }

    FactorUploadBatch batch = factorUploadBatchService.createFactorBatch(
        factorBatchRequest(
            factorParseResult, priceMonth, businessUnitType, fileName, excelBytes, effectiveStrategy));
    Long factorUploadBatchId = batch == null ? null : batch.getId();
    response.setFactorUploadBatchId(factorUploadBatchId);
    if (factorUploadBatchId != null) {
      response.setBatchId(String.valueOf(factorUploadBatchId));
    }

    FactorMonthlyPriceUpsertResult upsertResult = factorMonthlyPriceUpsertService.upsert(
        factorParseResult, priceMonth, businessUnitType, currentOperator(),
        factorUploadBatchId, factorPriceConflictStrategy);
    response.setFactorRecognizedCount(upsertResult.getRows().size());
    response.setMonthlyPriceCreatedCount(upsertResult.getMonthlyPriceCreatedCount());
    response.setMonthlyPriceUpdatedCount(upsertResult.getMonthlyPriceUpdatedCount());
    response.setMonthlyPriceUnchangedCount(upsertResult.getMonthlyPriceUnchangedCount());
    response.setMonthlyPriceSkippedCount(upsertResult.getMonthlyPriceSkippedCount());
    response.setMonthlyPriceConflictCount(upsertResult.getMonthlyPriceConflictCount());
    response.setMonthlyPriceOverwriteCount(upsertResult.getMonthlyPriceOverwriteCount());
    response.setQuoteBaseRecognizedCount(upsertResult.getQuoteBaseRecognizedCount());
    response.setQuoteBaseUnrecognizedCount(upsertResult.getQuoteBaseUnrecognizedCount());
    response.setQuoteBaseConflictCount(upsertResult.getQuoteBaseConflictCount());
    response.getFactorRows().addAll(upsertResult.getRows());
    for (FactorMonthlyPriceUpsertResult.RowError error : upsertResult.getErrors()) {
      response.getErrors().add(new ErrorRow(
          error.getSourceRowNumber(), null, null, error.getMessage()));
      response.setSkipped(response.getSkipped() + 1);
    }

    FactorRowRefSaveResult rowRefResult = factorUploadBatchService.saveRowRefs(
        factorUploadBatchId, factorParseResult, upsertResult);
    for (FactorRowRefSaveResult.RowError error : rowRefResult.getErrors()) {
      response.getErrors().add(new ErrorRow(
          error.getSourceRowNumber(), null, null, error.getMessage()));
      response.setSkipped(response.getSkipped() + 1);
    }

    Map<String, ResolvedFactorRef> importedFactorByAlias =
        buildImportedFactorAliasMap(upsertResult);
    Map<Integer, V2BindingPlan> plansByExcelRow = buildV2BindingPlans(
        excelBytes, fileName, factorUploadBatchId, findLinkedImportSheetName(excelBytes));
    return new V2ImportContext(
        true, factorUploadBatchId, plansByExcelRow, importedFactorByAlias);
  }

  private Integer findLinkedImportSheetNo(byte[] excelBytes) {
    if (excelBytes == null || excelBytes.length == 0) {
      return null;
    }
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes))) {
      for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
        if (isHiddenSheet(workbook, i)) {
          continue;
        }
        Sheet sheet = workbook.getSheetAt(i);
        int headerRowIndex = findHeaderRow(sheet);
        if (headerRowIndex < 0) {
          continue;
        }
        Row header = sheet.getRow(headerRowIndex);
        if (findHeaderCol(header, "物料代码") >= 0 && findHeaderCol(header, "单价") >= 0) {
          return i;
        }
      }
    } catch (Exception e) {
      log.warn("定位联动价 Excel sheet 失败，回退读取第一个 sheet: {}", e.getMessage());
    }
    return null;
  }

  private String findLinkedImportSheetName(byte[] excelBytes) {
    Integer sheetNo = findLinkedImportSheetNo(excelBytes);
    if (sheetNo == null) {
      return null;
    }
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes))) {
      Sheet sheet = workbook.getSheetAt(sheetNo);
      return sheet == null ? null : sheet.getSheetName();
    } catch (Exception e) {
      log.warn("定位联动价 Excel sheet 名失败: {}", e.getMessage());
      return null;
    }
  }

  private boolean isHiddenSheet(Workbook workbook, int sheetIndex) {
    return workbook != null
        && sheetIndex >= 0
        && sheetIndex < workbook.getNumberOfSheets()
        && (workbook.isSheetHidden(sheetIndex) || workbook.isSheetVeryHidden(sheetIndex));
  }

  private boolean v2AutoBindingReady() {
    return factorWorkbookParser != null
        && factorUploadBatchService != null
        && factorMonthlyPriceUpsertService != null
        && formulaWorkbookParser != null
        && formulaFactorRefParser != null
        && formulaFactorRefResolver != null
        && bindingCandidateBuilder != null
        && standardBindingService != null
        && autoBindingWriteService != null
        && importResultClassifier != null;
  }

  private FactorUploadBatchCreateRequest factorBatchRequest(
      FactorWorkbookParseResult factorParseResult,
      String priceMonth,
      String businessUnitType,
      String fileName,
      byte[] excelBytes,
      String effectiveStrategy) {
    FactorUploadBatchCreateRequest request = new FactorUploadBatchCreateRequest();
    request.setPriceMonth(priceMonth);
    request.setBusinessUnitType(businessUnitType);
    request.setFileName(fileName);
    request.setFileSha256(sha256(excelBytes));
    request.setUploadedBy(currentOperator());
    request.setImportType("MONTHLY_LINKED_FACTOR");
    request.setImportPurpose(importPurposeForStrategy(effectiveStrategy));
    request.setEffectiveStrategy(effectiveStrategy);
    request.setParseResult(factorParseResult);
    return request;
  }

  private Map<Integer, V2BindingPlan> buildV2BindingPlans(
      byte[] excelBytes,
      String fileName,
      Long factorUploadBatchId,
      String linkedSheetName) {
    Map<Integer, V2BindingPlan> plans = new HashMap<>();
    LinkedFormulaWorkbookParseResult formulaWorkbook =
        formulaWorkbookParser.parse(new ByteArrayInputStream(excelBytes), fileName);
    for (var sheet : formulaWorkbook.getSheets()) {
      if (StringUtils.hasText(linkedSheetName)
          && !linkedSheetName.equals(sheet.getSheetName())) {
        continue;
      }
      for (LinkedFormulaRow formulaRow : sheet.getRows()) {
        V2BindingPlan plan = new V2BindingPlan(factorUploadBatchId, formulaRow);
        if (Boolean.TRUE.equals(formulaRow.getHasFormula())) {
          List<FormulaFactorRef> formulaRefs =
              formulaFactorRefParser.parse(formulaRow.getPriceCellFormula());
          plan.resolvedRefs.addAll(
              formulaFactorRefResolver.resolve(factorUploadBatchId, formulaRefs));
          BindingCandidateBuildResult candidates = bindingCandidateBuilder.build(
              formulaRow.getMaterialCode(),
              formulaRow.getLinkedItemImportKey(),
              formulaRow.getFormulaText(),
              plan.resolvedRefs);
          plan.candidates.addAll(candidates.getCandidates());
        }
        plans.put(formulaRow.getExcelRowNumber(), plan);
      }
    }
    return plans;
  }

  private void applyV2AutoBindings(
      PriceLinkedItem item,
      V2BindingPlan plan,
      String priceMonth,
      String businessUnitType,
      int excelRow,
      boolean overwriteManual,
      PriceItemImportResponse response) {
    if (item == null || plan == null) {
      return;
    }
    PriceLinkedImportResultClassifyRequest classifyRequest =
        new PriceLinkedImportResultClassifyRequest();
    classifyRequest.setExcelRowNumber(excelRow);
    classifyRequest.setMaterialCode(item.getMaterialCode());
    classifyRequest.setFormula(formulaForResult(plan.formulaRow()));
    classifyRequest.setFormulaAvailable(plan.formulaAvailable());
    classifyRequest.getResolvedRefs().addAll(plan.resolvedRefs());

    if (plan.formulaAvailable()) {
      List<StandardBindingDecision> decisions = standardBindingService.checkAndRecord(
          standardBindingRequest(item, plan, priceMonth, businessUnitType));
      classifyRequest.getStandardDecisions().addAll(decisions);

      PriceLinkedAutoBindingWriteRequest writeRequest = new PriceLinkedAutoBindingWriteRequest();
      writeRequest.setLinkedItemId(item.getId());
      writeRequest.setPricingMonth(priceMonth);
      writeRequest.setFactorUploadBatchId(plan.factorUploadBatchId());
      writeRequest.setExcelFormula(plan.formulaRow().getPriceCellFormula());
      writeRequest.setOverwriteManualBinding(overwriteManual);
      writeRequest.getDecisions().addAll(decisions);
      PriceLinkedAutoBindingWriteResult writeResult = autoBindingWriteService.write(writeRequest);
      classifyRequest.setWriteResult(writeResult);
    }
    importResultClassifier.append(response, classifyRequest);
  }

  private StandardBindingCheckRequest standardBindingRequest(
      PriceLinkedItem item,
      V2BindingPlan plan,
      String priceMonth,
      String businessUnitType) {
    StandardBindingCheckRequest request = new StandardBindingCheckRequest();
    request.setBusinessUnitType(businessUnitType);
    request.setMaterialCode(item.getMaterialCode());
    request.setSupplierCode(item.getSupplierCode());
    request.setLinkedItemImportKey(plan.formulaRow().getLinkedItemImportKey());
    request.setFactorUploadBatchId(plan.factorUploadBatchId());
    request.setFormulaText(plan.formulaRow().getFormulaText());
    request.setFormulaAvailable(plan.formulaAvailable());
    request.setOperator(currentOperator());
    request.getCandidates().addAll(plan.candidates());
    return request;
  }

  private String formulaForResult(LinkedFormulaRow row) {
    if (row == null) {
      return null;
    }
    return StringUtils.hasText(row.getPriceCellFormula())
        ? row.getPriceCellFormula()
        : row.getFormulaText();
  }

  private String formulaSourceForNormalize(
      PriceItemExcelImportRow row,
      V2BindingPlan plan,
      Map<String, ResolvedFactorRef> importedFactorByAlias) {
    String excelDerived = excelDerivedFormulaForNormalize(plan);
    String source = StringUtils.hasText(excelDerived)
        ? excelDerived
        : row == null ? null : row.getFormulaExpr();
    return applyImportedFactorPriority(source, importedFactorByAlias);
  }

  private Map<String, ResolvedFactorRef> buildImportedFactorAliasMap(
      FactorMonthlyPriceUpsertResult upsertResult) {
    if (upsertResult == null || upsertResult.getRows().isEmpty()) {
      return Map.of();
    }
    Map<String, ResolvedFactorRef> map = new LinkedHashMap<>();
    for (FactorMonthlyPriceUpsertResult.RowResult row : upsertResult.getRows()) {
      if (row == null || row.getFactorIdentityId() == null) {
        continue;
      }
      ResolvedFactorRef ref = resolvedRefFromImportRow(row);
      putImportedFactorAlias(map, row.getShortName(), ref);
      if (StringUtils.hasText(row.getFactorSeqNo()) && StringUtils.hasText(row.getShortName())) {
        putImportedFactorAlias(
            map, row.getFactorSeqNo().trim() + "#" + row.getShortName().trim(), ref);
      }
      putImportedFactorAlias(map, row.getFactorName(), ref);
    }
    return map;
  }

  private ResolvedFactorRef resolvedRefFromImportRow(FactorMonthlyPriceUpsertResult.RowResult row) {
    ResolvedFactorRef ref = new ResolvedFactorRef();
    ref.setFactorIdentityId(row.getFactorIdentityId());
    ref.setFactorMonthlyPriceId(row.getFactorMonthlyPriceId());
    ref.setFactorSeqNo(row.getFactorSeqNo());
    ref.setShortName(row.getShortName());
    ref.setPriceSource(row.getPriceSource());
    ref.setPrice(row.getNewPrice());
    ref.setSheetName(row.getSourceSheetName());
    ref.setRowNumber(row.getSourceRowNumber());
    return ref;
  }

  private void putImportedFactorAlias(
      Map<String, ResolvedFactorRef> aliases, String alias, ResolvedFactorRef ref) {
    if (!StringUtils.hasText(alias) || ref == null || ref.getFactorIdentityId() == null) {
      return;
    }
    String key = alias.trim();
    ResolvedFactorRef existing = aliases.get(key);
    if (existing != null && !Objects.equals(existing.getFactorIdentityId(), ref.getFactorIdentityId())) {
      log.warn("本次导入影响因素别名冲突，跳过 alias={} existingFactorIdentityId={} newFactorIdentityId={}",
          key, existing.getFactorIdentityId(), ref.getFactorIdentityId());
      return;
    }
    aliases.putIfAbsent(key, ref);
  }

  private String applyImportedFactorPriority(
      String formula, Map<String, ResolvedFactorRef> importedFactorByAlias) {
    if (!StringUtils.hasText(formula)
        || importedFactorByAlias == null
        || importedFactorByAlias.isEmpty()) {
      return formula;
    }
    String result = formula;
    List<Map.Entry<String, ResolvedFactorRef>> entries =
        new ArrayList<>(importedFactorByAlias.entrySet());
    entries.sort((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()));
    for (Map.Entry<String, ResolvedFactorRef> entry : entries) {
      if (!containsFormulaToken(result, entry.getKey())) {
        continue;
      }
      String replacement = factorReplacement(entry.getValue());
      result = replaceFormulaToken(result, entry.getKey(), replacement);
    }
    return result;
  }

  private String replaceFormulaToken(String formula, String token, String replacement) {
    if (!StringUtils.hasText(formula)
        || !StringUtils.hasText(token)
        || !StringUtils.hasText(replacement)) {
      return formula;
    }
    Matcher matcher = formulaTokenPattern(token).matcher(formula);
    return matcher.replaceAll(Matcher.quoteReplacement(replacement));
  }

  private boolean containsFormulaToken(String formula, String token) {
    return StringUtils.hasText(formula)
        && StringUtils.hasText(token)
        && formulaTokenPattern(token).matcher(formula).find();
  }

  private Pattern formulaTokenPattern(String token) {
    return Pattern.compile(
        "(?<![\\p{L}\\p{N}_\\[])"
            + Pattern.quote(token)
            + "(?![\\p{L}\\p{N}_\\]])");
  }

  private void applyExcelGoldenPrice(PriceItemExcelImportRow row, V2BindingPlan plan) {
    if (row == null || plan == null || plan.formulaRow() == null) {
      return;
    }
    if (plan.formulaRow().getPriceCellValue() != null) {
      row.setUnitPrice(plan.formulaRow().getPriceCellValue());
    }
  }

  private String excelDerivedFormulaForNormalize(V2BindingPlan plan) {
    if (plan == null || plan.formulaRow() == null
        || !Boolean.TRUE.equals(plan.formulaRow().getHasFormula())
        || !StringUtils.hasText(plan.formulaRow().getExcelDerivedFormulaText())) {
      return null;
    }
    String derived = removeSystemWeightDivisors(plan.formulaRow().getExcelDerivedFormulaText());
    List<ResolvedFactorRef> refs = plan.resolvedRefs();
    if (refs == null || refs.isEmpty()) {
      return derived;
    }
    boolean rowLocal = shouldUseRowLocalTokens(plan.formulaRow().getFormulaText(), refs);
    int factorIndex = 0;
    for (ResolvedFactorRef ref : refs) {
      if (ref == null || !StringUtils.hasText(ref.getRawRef())) {
        continue;
      }
      String replacement;
      if (rowLocal) {
        replacement = factorIndex == 0 ? "[__material]"
            : factorIndex == 1 ? "[__scrap]" : factorReplacement(ref);
      } else {
        replacement = factorReplacement(ref);
      }
      if (StringUtils.hasText(replacement)) {
        derived = derived.replace(ref.getRawRef(), replacement);
      }
      factorIndex++;
    }
    return derived;
  }

  private FormulaTaxNormalization normalizeExcelFormulaTax(String formula) {
    String stripped = stripFinalVatDivisor(formula);
    return new FormulaTaxNormalization(
        stripped, StringUtils.hasText(formula) && !formula.equals(stripped));
  }

  private String stripFinalVatDivisor(String formula) {
    if (!StringUtils.hasText(formula)) {
      return formula;
    }
    String trimmed = formula.trim();
    int slash = findFinalTopLevelSlash(trimmed);
    if (slash < 0) {
      return formula;
    }
    String divisor = trimmed.substring(slash + 1).trim();
    if (!"1.13".equals(divisor)) {
      return formula;
    }
    String withoutDivisor = trimmed.substring(0, slash).trim();
    return StringUtils.hasText(withoutDivisor) ? withoutDivisor : formula;
  }

  private int findFinalTopLevelSlash(String formula) {
    int depth = 0;
    int slash = -1;
    for (int i = 0; i < formula.length(); i++) {
      char c = formula.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth = Math.max(0, depth - 1);
      } else if (c == '/' && depth == 0) {
        slash = i;
      }
    }
    return slash;
  }

  private record FormulaTaxNormalization(String formula, boolean finalVatDivisorStripped) {}

  private String removeSystemWeightDivisors(String formula) {
    if (!StringUtils.hasText(formula) || !formula.contains("/1000")) {
      return formula;
    }
    StringBuilder out = new StringBuilder(formula.length());
    for (int i = 0; i < formula.length(); i++) {
      if (formula.startsWith("/1000", i) && additiveTermHasSystemWeight(formula, i)) {
        i += "/1000".length() - 1;
        continue;
      }
      out.append(formula.charAt(i));
    }
    return out.toString();
  }

  private boolean additiveTermHasSystemWeight(String formula, int operatorIndex) {
    int depthAtOperator = depthBefore(formula, operatorIndex);
    int start = 0;
    for (int i = operatorIndex - 1; i >= 0; i--) {
      char c = formula.charAt(i);
      if ((c == '+' || c == '-') && depthBefore(formula, i) == depthAtOperator) {
        start = i + 1;
        break;
      }
    }
    String term = formula.substring(start, operatorIndex);
    return term.contains("[blank_weight]") || term.contains("[net_weight]");
  }

  private int depthBefore(String formula, int index) {
    int depth = 0;
    for (int i = 0; i < index && i < formula.length(); i++) {
      char c = formula.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      }
    }
    return depth;
  }

  private boolean shouldUseRowLocalTokens(String formulaText, List<ResolvedFactorRef> refs) {
    String text = formulaText == null ? "" : formulaText;
    if (text.contains("材料含税价格") || text.contains("材料价格")
        || text.contains("废料含税价格") || text.contains("废料价格")) {
      return true;
    }
    return refs != null && refs.size() == 2
        && (text.contains("__material") || text.contains("__scrap"));
  }

  private String factorReplacement(ResolvedFactorRef ref) {
    if (ref == null) {
      return null;
    }
    String monthlyFactorCode = ensureMonthlyFactorVariable(ref);
    if (StringUtils.hasText(monthlyFactorCode)) {
      return "[" + monthlyFactorCode + "]";
    }
    if (StringUtils.hasText(ref.getShortName())) {
      return ref.getShortName().trim();
    }
    return ref.getRawRef();
  }

  private String ensureMonthlyFactorVariable(ResolvedFactorRef ref) {
    if (ref == null || ref.getFactorIdentityId() == null) {
      return null;
    }
    String variableCode = "factor_identity_" + ref.getFactorIdentityId();
    PriceVariable existing = priceVariableMapper.selectOne(
        Wrappers.lambdaQuery(PriceVariable.class)
            .eq(PriceVariable::getVariableCode, variableCode)
            .last("LIMIT 1"));
    if (existing == null) {
      PriceVariable variable = new PriceVariable();
      variable.setVariableCode(variableCode);
      variable.setVariableName(StringUtils.hasText(ref.getShortName())
          ? ref.getShortName().trim() : variableCode);
      variable.setAliasesJson(factorAliasJson(ref, variableCode));
      variable.setSourceType("FACTOR_MONTHLY_PRICE");
      variable.setSourceTable("lp_factor_monthly_price");
      variable.setSourceField("price");
      variable.setScope("BASE_PRICE");
      variable.setStatus("active");
      variable.setTaxMode("INCL");
      variable.setFactorType("FINANCE_FACTOR");
      variable.setResolverKind("FINANCE");
      StringBuilder params = new StringBuilder("{\"factorIdentityId\":")
          .append(ref.getFactorIdentityId());
      if (ref.getFactorMonthlyPriceId() != null) {
        params.append(",\"factorMonthlyPriceId\":").append(ref.getFactorMonthlyPriceId());
      }
      if (StringUtils.hasText(ref.getShortName())) {
        params.append(",\"shortName\":\"").append(jsonEscape(ref.getShortName().trim())).append("\"");
      }
      if (StringUtils.hasText(ref.getPriceSource())) {
        params.append(",\"priceSource\":\"").append(jsonEscape(ref.getPriceSource().trim())).append("\"");
      }
      params.append(",\"buScoped\":true}");
      variable.setResolverParams(params.toString());
      priceVariableMapper.insert(variable);
      factorVariableRegistry.invalidate();
      formulaDisplayRenderer.refresh();
    } else {
      boolean changed = false;
      String display = StringUtils.hasText(ref.getShortName()) ? ref.getShortName().trim() : null;
      if (StringUtils.hasText(display)
          && (!StringUtils.hasText(existing.getVariableName())
              || variableCode.equals(existing.getVariableName().trim()))) {
        existing.setVariableName(display);
        changed = true;
      }
      if (!StringUtils.hasText(existing.getAliasesJson()) && StringUtils.hasText(display)) {
        existing.setAliasesJson(factorAliasJson(ref, variableCode));
        changed = true;
      }
      if (changed) {
        priceVariableMapper.updateById(existing);
        formulaDisplayRenderer.refresh();
      }
    }
    return variableCode;
  }

  private String factorAliasJson(ResolvedFactorRef ref, String variableCode) {
    List<String> aliases = new ArrayList<>();
    if (ref != null && StringUtils.hasText(ref.getShortName())) {
      aliases.add(ref.getShortName().trim());
    }
    if (ref != null && StringUtils.hasText(ref.getFactorSeqNo())
        && StringUtils.hasText(ref.getShortName())) {
      aliases.add(ref.getFactorSeqNo().trim() + "#" + ref.getShortName().trim());
    }
    aliases.add(variableCode);
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < aliases.size(); i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append('"').append(jsonEscape(aliases.get(i))).append('"');
    }
    json.append(']');
    return json.toString();
  }

  /** 必填校验：缺物料代码直接跳过（整行无意义）。 */
  private String validateRow(PriceItemExcelImportRow row) {
    if (row == null) {
      return "行为空";
    }
    if (!StringUtils.hasText(row.getMaterialCode())) {
      return "物料代码为空";
    }
    return null;
  }

  /** 公式非空则过 FormulaNormalizer；失败返 null（让调用方记 error）。空公式直接返空串放行。 */
  private String tryNormalize(String raw) {
    if (!StringUtils.hasText(raw)) {
      return "";
    }
    try {
      return formulaNormalizer.normalize(raw);
    } catch (FormulaSyntaxException ex) {
      log.debug("公式规范化失败: raw={}, error={}", raw, ex.getMessage());
      return null;
    }
  }

  /**
   * 从 Excel 单价列真实公式里提取影响因素引用，自动推断行局部变量绑定。
   *
   * <p>典型公式：
   * <pre>
   * ROUND($I$2*影响因素10!$E$64/1000-(I2-J2)*影响因素10!$E$44/1000+K2,4)/1.13
   * </pre>
   * 这里第一处影响因素引用是主材料价，第二处是废料价。若工作簿里同时带了
   * 影响因素 sheet，直接按引用行号读简称/价源；若只上传联动价 sheet，则用
   * 已导入的 {@code lp_finance_base_price.seq = 引用行号 - 2} 回查。
   */
  private Map<Integer, AutoBindingPlan> extractAutoBindingPlans(byte[] excelBytes, String priceMonth) {
    Map<Integer, AutoBindingPlan> plans = new HashMap<>();
    if (excelBytes == null || excelBytes.length == 0) {
      return plans;
    }
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes))) {
      Map<String, Map<Integer, InfluenceFactorRef>> influenceIndex = buildInfluenceIndex(workbook);
      Integer linkedSheetNo = findLinkedImportSheetNo(excelBytes);
      Sheet linkedSheet = linkedSheetNo == null ? null : workbook.getSheetAt(linkedSheetNo);
      if (linkedSheet == null) {
        return plans;
      }
      int headerRowIndex = findHeaderRow(linkedSheet);
      if (headerRowIndex < 0) {
        return plans;
      }
      Row header = linkedSheet.getRow(headerRowIndex);
      int unitPriceCol = findHeaderCol(header, "单价");
      if (unitPriceCol < 0) {
        return plans;
      }
      for (int r = headerRowIndex + 1; r <= linkedSheet.getLastRowNum(); r++) {
        Row row = linkedSheet.getRow(r);
        if (row == null) {
          continue;
        }
        Cell priceCell = row.getCell(unitPriceCol);
        if (priceCell == null || priceCell.getCellType() != CellType.FORMULA) {
          continue;
        }
        List<InfluenceFactorRef> refs = parseInfluenceRefs(
            priceCell.getCellFormula(), influenceIndex, priceMonth);
        if (refs.isEmpty()) {
          continue;
        }
        AutoBindingPlan plan = new AutoBindingPlan();
        plan.material = refs.get(0);
        if (refs.size() > 1) {
          plan.scrap = refs.get(1);
        }
        plans.put(r + 1, plan);
      }
    } catch (Exception e) {
      log.warn("读取 Excel 公式自动绑定失败，联动价导入继续但不自动生成绑定: {}", e.getMessage());
    }
    return plans;
  }

  private Map<String, Map<Integer, InfluenceFactorRef>> buildInfluenceIndex(Workbook workbook) {
    Map<String, Map<Integer, InfluenceFactorRef>> index = new HashMap<>();
    for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
      if (isHiddenSheet(workbook, i)) {
        continue;
      }
      Sheet sheet = workbook.getSheetAt(i);
      if (sheet == null || sheet.getSheetName() == null
          || !sheet.getSheetName().contains("影响因素")) {
        continue;
      }
      int headerRowIndex = findHeaderRow(sheet);
      if (headerRowIndex < 0) {
        continue;
      }
      Row header = sheet.getRow(headerRowIndex);
      int shortNameCol = findHeaderCol(header, "简称");
      int priceSourceCol = findHeaderCol(header, "取价来源");
      int factorNameCol = findHeaderCol(header, "价表影响因素名称");
      int seqCol = findHeaderCol(header, "序号");
      if (shortNameCol < 0 || priceSourceCol < 0) {
        continue;
      }
      Map<Integer, InfluenceFactorRef> byRow = new HashMap<>();
      for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
        Row row = sheet.getRow(r);
        if (row == null) {
          continue;
        }
        String shortName = cellText(row.getCell(shortNameCol));
        if (!StringUtils.hasText(shortName)) {
          continue;
        }
        InfluenceFactorRef ref = new InfluenceFactorRef();
        ref.sheetName = sheet.getSheetName();
        ref.rowNumber = r + 1;
        ref.seq = parseInteger(cellText(seqCol >= 0 ? row.getCell(seqCol) : null));
        ref.factorName = cellText(factorNameCol >= 0 ? row.getCell(factorNameCol) : null);
        ref.shortName = shortName.trim();
        ref.priceSource = cellText(row.getCell(priceSourceCol));
        byRow.put(r + 1, ref);
      }
      index.put(sheet.getSheetName(), byRow);
    }
    return index;
  }

  private List<InfluenceFactorRef> parseInfluenceRefs(
      String formula,
      Map<String, Map<Integer, InfluenceFactorRef>> influenceIndex,
      String priceMonth) {
    List<InfluenceFactorRef> refs = new ArrayList<>();
    if (!StringUtils.hasText(formula)) {
      return refs;
    }
    Matcher matcher = SHEET_REF_PATTERN.matcher(formula);
    while (matcher.find()) {
      String sheetName = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
      if (!StringUtils.hasText(sheetName) || !sheetName.contains("影响因素")) {
        continue;
      }
      int rowNumber = Integer.parseInt(matcher.group(4));
      InfluenceFactorRef ref = null;
      Map<Integer, InfluenceFactorRef> byRow = influenceIndex.get(sheetName);
      if (byRow != null) {
        ref = byRow.get(rowNumber);
      }
      if (ref == null) {
        ref = findInfluenceFactorFromDb(sheetName, rowNumber, priceMonth);
      }
      if (ref == null || !StringUtils.hasText(ref.shortName)) {
        continue;
      }
      InfluenceFactorRef resolvedRef = ref;
      if (refs.stream().noneMatch(existing ->
          existing.rowNumber == resolvedRef.rowNumber
              && existing.sheetName.equals(resolvedRef.sheetName))) {
        refs.add(ref);
      }
    }
    return refs;
  }

  private InfluenceFactorRef findInfluenceFactorFromDb(
      String sheetName, int rowNumber, String priceMonth) {
    int seq = rowNumber - 2;
    if (seq <= 0 || !StringUtils.hasText(priceMonth)) {
      return null;
    }
    FinanceBasePrice row = financeBasePriceMapper.selectOne(
        Wrappers.lambdaQuery(FinanceBasePrice.class)
            .eq(FinanceBasePrice::getPriceMonth, priceMonth)
            .eq(FinanceBasePrice::getSeq, seq)
            .orderByDesc(FinanceBasePrice::getId)
            .last("LIMIT 1"));
    if (row == null) {
      return null;
    }
    InfluenceFactorRef ref = new InfluenceFactorRef();
    ref.sheetName = sheetName;
    ref.rowNumber = rowNumber;
    ref.seq = seq;
    ref.factorName = row.getFactorName();
    ref.shortName = row.getShortName();
    ref.factorCode = row.getFactorCode();
    ref.priceSource = row.getPriceSource();
    return ref;
  }

  private void applyAutoBindings(
      PriceLinkedItem item,
      PriceItemExcelImportRow row,
      String normalizedFormula,
      AutoBindingPlan plan,
      String priceMonth,
      int excelRow,
      boolean overwriteManual,
      PriceItemImportResponse response) {
    if (item == null || plan == null || !StringUtils.hasText(normalizedFormula)) {
      return;
    }
    if (normalizedFormula.contains("[__material]") && plan.material != null) {
      saveAutoBinding(item, materialTokenName(row), plan.material, priceMonth, excelRow,
          overwriteManual, response);
    }
    if (normalizedFormula.contains("[__scrap]") && plan.scrap != null) {
      saveAutoBinding(item, scrapTokenName(row), plan.scrap, priceMonth, excelRow,
          overwriteManual, response);
    }
  }

  private void saveAutoBinding(
      PriceLinkedItem item,
      String tokenName,
      InfluenceFactorRef factor,
      String priceMonth,
      int excelRow,
      boolean overwriteManual,
      PriceItemImportResponse response) {
    PriceVariableBindingDto current = currentBinding(item.getId(), tokenName);
    if (current != null && "MANUAL".equalsIgnoreCase(current.getSource()) && !overwriteManual) {
      response.setManualSkippedCount(response.getManualSkippedCount() + 1);
      PriceItemImportResponse.BindingError error = new PriceItemImportResponse.BindingError();
      error.setExcelRowNumber(excelRow);
      error.setMaterialCode(item.getMaterialCode());
      error.setFormula(item.getFormulaExpr());
      error.setRefSheet(factor.sheetName);
      error.setRefRow(factor.rowNumber);
      error.setExistingFactorIdentity(current.getFactorIdentityId());
      error.setReason("当前 token 已存在 MANUAL 绑定，默认不覆盖");
      response.getBindingErrors().add(error);
      return;
    }
    String factorCode = ensureFinanceVariable(factor);
    if (!StringUtils.hasText(factorCode)) {
      response.getErrors().add(new ErrorRow(excelRow, item.getMaterialCode(), item.getOrderType(),
          "Excel 公式引用的影响因素无法登记变量: " + factor.display()));
      return;
    }
    PriceVariableBindingRequest request = new PriceVariableBindingRequest();
    request.setLinkedItemId(item.getId());
    request.setTokenName(tokenName);
    request.setFactorCode(factorCode);
    request.setPriceSource(factor.priceSource);
    request.setEffectiveDate(parseMonthStart(priceMonth));
    request.setSource("EXCEL_FORMULA");
    request.setRemark("由单价列公式自动识别：" + factor.display());
    try {
      priceVariableBindingService.save(request);
      response.setAutoBindingCount(response.getAutoBindingCount() + 1);
    } catch (RuntimeException e) {
      response.getErrors().add(new ErrorRow(excelRow, item.getMaterialCode(), item.getOrderType(),
          "自动绑定失败: " + e.getMessage()));
    }
  }

  private PriceVariableBindingDto currentBinding(Long linkedItemId, String tokenName) {
    if (linkedItemId == null || !StringUtils.hasText(tokenName)) {
      return null;
    }
    return priceVariableBindingService.listByLinkedItem(linkedItemId).stream()
        .filter(binding -> tokenName.equals(binding.getTokenName()))
        .findFirst()
        .orElse(null);
  }

  private String ensureFinanceVariable(InfluenceFactorRef factor) {
    if (factor == null || !StringUtils.hasText(factor.shortName)) {
      return null;
    }
    String variableCode = StringUtils.hasText(factor.factorCode)
        ? factor.factorCode.trim()
        : factor.shortName.trim();
    PriceVariable existing = priceVariableMapper.selectOne(
        Wrappers.lambdaQuery(PriceVariable.class)
            .eq(PriceVariable::getVariableCode, variableCode)
            .last("LIMIT 1"));
    if (existing == null) {
      PriceVariable variable = new PriceVariable();
      variable.setVariableCode(variableCode);
      variable.setVariableName(factor.shortName.trim());
      variable.setSourceType("FACTOR");
      variable.setSourceTable("lp_finance_base_price");
      variable.setSourceField("price");
      variable.setScope("BASE_PRICE");
      variable.setStatus("active");
      variable.setTaxMode("INCL");
      variable.setFactorType("FINANCE_FACTOR");
      variable.setAliasesJson("[\"" + jsonEscape(factor.shortName.trim()) + "\"]");
      variable.setResolverKind("FINANCE");
      if (StringUtils.hasText(factor.factorCode)) {
        variable.setResolverParams("{\"factorCode\":\"" + jsonEscape(factor.factorCode.trim())
            + "\",\"priceSource\":\"" + jsonEscape(defaultPriceSource(factor.priceSource))
            + "\",\"buScoped\":true}");
      } else {
        variable.setResolverParams("{\"shortName\":\"" + jsonEscape(factor.shortName.trim())
            + "\",\"priceSource\":\"" + jsonEscape(defaultPriceSource(factor.priceSource))
            + "\",\"buScoped\":true}");
      }
      priceVariableMapper.insert(variable);
      factorVariableRegistry.invalidate();
    }
    return variableCode;
  }

  private int findHeaderRow(Sheet sheet) {
    int limit = Math.min(sheet.getLastRowNum(), 10);
    for (int r = 0; r <= limit; r++) {
      Row row = sheet.getRow(r);
      if (row == null) {
        continue;
      }
      if (findHeaderCol(row, "单价") >= 0
          || (findHeaderCol(row, "简称") >= 0 && findHeaderCol(row, "取价来源") >= 0)) {
        return r;
      }
    }
    return -1;
  }

  private int findHeaderCol(Row row, String headerName) {
    if (row == null || !StringUtils.hasText(headerName)) {
      return -1;
    }
    for (int c = 0; c < row.getLastCellNum(); c++) {
      if (headerName.equals(cellText(row.getCell(c)))) {
        return c;
      }
    }
    return -1;
  }

  private String cellText(Cell cell) {
    if (cell == null) {
      return "";
    }
    return switch (cell.getCellType()) {
      case STRING -> cell.getStringCellValue() == null ? "" : cell.getStringCellValue().trim();
      case NUMERIC -> {
        double n = cell.getNumericCellValue();
        if (Math.floor(n) == n) {
          yield String.valueOf((long) n);
        }
        yield String.valueOf(n);
      }
      case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
      case FORMULA -> cell.getCellFormula();
      default -> "";
    };
  }

  private Integer parseInteger(String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    try {
      return (int) Double.parseDouble(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private java.time.LocalDate parseMonthStart(String priceMonth) {
    if (!StringUtils.hasText(priceMonth)) {
      return java.time.LocalDate.now();
    }
    return java.time.LocalDate.parse(priceMonth.trim() + "-01");
  }

  private String materialTokenName(PriceItemExcelImportRow row) {
    String raw = row == null ? null : row.getFormulaExpr();
    return raw != null && raw.contains("材料价格") && !raw.contains("材料含税价格")
        ? "材料价格" : "材料含税价格";
  }

  private String scrapTokenName(PriceItemExcelImportRow row) {
    String raw = row == null ? null : row.getFormulaExpr();
    return raw != null && raw.contains("废料价格") && !raw.contains("废料含税价格")
        ? "废料价格" : "废料含税价格";
  }

  private String defaultPriceSource(String priceSource) {
    return StringUtils.hasText(priceSource) ? priceSource.trim() : "未指定";
  }

  private String jsonEscape(String raw) {
    return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private PriceLinkedItem upsertLinked(PriceItemExcelImportRow row, String pricingMonth,
      String normalizedFormula) {
    return upsertLinked(row, pricingMonth, normalizedFormula,
        parseMonthStart(pricingMonth), resolveBusinessUnitType(null)).item();
  }

  private LinkedImportOutcome upsertLinked(
      PriceItemExcelImportRow row,
      String pricingMonth,
      String normalizedFormula,
      LocalDate formulaEffectiveDate,
      String businessUnitType) {
    PriceLinkedItem existing = findCurrentLinkedVersion(pricingMonth, businessUnitType, row);
    if (existing != null && sameLinkedFormulaVersion(existing, row, normalizedFormula)) {
      return new LinkedImportOutcome(existing, false, false, true);
    }
    if (existing != null) {
      expireOldVersion(existing, formulaEffectiveDate);
    }
    PriceLinkedItem item = new PriceLinkedItem();
    item.setPricingMonth(pricingMonth);
    item.setOrgCode(row.getOrgCode());
    item.setSourceName(row.getSourceName());
    item.setSupplierName(row.getSupplierName());
    item.setSupplierCode(trim(row.getSupplierCode()));
    item.setPurchaseClass(row.getPurchaseClass());
    item.setMaterialName(row.getMaterialName());
    item.setMaterialCode(trim(row.getMaterialCode()));
    item.setSpecModel(trim(row.getSpecModel()));
    item.setUnit(row.getUnit());
    // Plan B T6 收尾：formulaExpr 存规范化结果（[code] 形式），formulaExprCn 统一由
    // Renderer 反向派生 —— 不再存 Excel 原文，避免两列语义漂移（历史脏数据：
    // Excel 原文常带 /1000 这种手工换算，和 kg 语义的规范化公式冲突，UI 一展示就混淆）。
    // 写入时也派生 = 双保险：即便后续某处绕过 DTO 直读 DB，拿到的也是一致值。
    item.setFormulaExpr(normalizedFormula);
    item.setFormulaExprCn(formulaDisplayRenderer.renderCn(normalizedFormula));
    item.setBlankWeight(row.getBlankWeight());
    item.setNetWeight(row.getNetWeight());
    item.setProcessFee(row.getProcessFee());
    item.setAgentFee(row.getAgentFee());
    item.setManualPrice(row.getUnitPrice());
    item.setTaxIncluded(parseTaxIncluded(row.getTaxIncluded()));
    item.setEffectiveFrom(formulaEffectiveDate);
    item.setEffectiveTo(null);
    // 保底填 "联动"，方便后续按 orderType 过滤
    item.setOrderType(StringUtils.hasText(row.getOrderType()) ? row.getOrderType().trim() : "联动");
    if (StringUtils.hasText(businessUnitType)) {
      item.setBusinessUnitType(businessUnitType.trim());
    }
    // 写入前显式注入当前登录账号的 BU，和 importItems 走同一路径，避免 NULL 行被 selectList 过滤掉
    applyCurrentBusinessUnit(item);
    itemMapper.insert(item);
    return new LinkedImportOutcome(item, true, existing != null, false);
  }

  private void upsertFixed(PriceItemExcelImportRow row, PriceItemImportResponse response) {
    PriceFixedItem existing = findExistingFixed(row);
    PriceFixedItem item = existing != null ? existing : new PriceFixedItem();
    item.setOrgCode(row.getOrgCode());
    item.setSourceName(row.getSourceName());
    item.setSupplierName(row.getSupplierName());
    item.setSupplierCode(trim(row.getSupplierCode()));
    item.setPurchaseClass(row.getPurchaseClass());
    item.setMaterialName(row.getMaterialName());
    item.setMaterialCode(trim(row.getMaterialCode()));
    item.setSpecModel(trim(row.getSpecModel()));
    item.setUnit(row.getUnit());
    item.setFormulaExpr(row.getFormulaExpr());
    item.setBlankWeight(row.getBlankWeight());
    item.setNetWeight(row.getNetWeight());
    item.setProcessFee(row.getProcessFee());
    item.setAgentFee(row.getAgentFee());
    item.setFixedPrice(row.getUnitPrice());
    item.setTaxIncluded(parseTaxIncluded(row.getTaxIncluded()));
    item.setEffectiveFrom(row.getEffectiveFrom());
    item.setEffectiveTo(row.getEffectiveTo());
    item.setOrderType(ORDER_TYPE_FIXED);
    // 写入前显式注入当前登录账号的 BU，固定价表同样受 BusinessUnitInterceptor 影响
    applyCurrentBusinessUnit(item);
    if (existing == null) {
      fixedItemMapper.insert(item);
    } else {
      fixedItemMapper.updateById(item);
    }
  }

  private PriceLinkedItem findCurrentLinkedVersion(
      String pricingMonth, String businessUnitType, PriceItemExcelImportRow row) {
    var query = Wrappers.lambdaQuery(PriceLinkedItem.class)
        .eq(PriceLinkedItem::getPricingMonth, pricingMonth)
        .eq(PriceLinkedItem::getMaterialCode, trim(row.getMaterialCode()))
        .isNull(PriceLinkedItem::getEffectiveTo)
        .eq(PriceLinkedItem::getDeleted, 0);
    if (StringUtils.hasText(businessUnitType)) {
      query.eq(PriceLinkedItem::getBusinessUnitType, businessUnitType.trim());
    } else {
      query.isNull(PriceLinkedItem::getBusinessUnitType);
    }
    // 联动价导入身份：有供应商时按“供应商 + 料号 + 业务单元”匹配；
    // Excel 供应商为空时退化为“料号 + 业务单元”。规格型号只是行属性，不参与判重。
    String supplierCode = trim(row.getSupplierCode());
    if (supplierCode != null) {
      query.eq(PriceLinkedItem::getSupplierCode, supplierCode);
    }
    return itemMapper.selectOne(query.orderByDesc(PriceLinkedItem::getId).last("LIMIT 1"));
  }

  private boolean sameLinkedFormulaVersion(
      PriceLinkedItem existing, PriceItemExcelImportRow row, String normalizedFormula) {
    if (existing == null || row == null) {
      return false;
    }
    return Objects.equals(existing.getFormulaExpr(), normalizedFormula)
        && sameDecimal(existing.getBlankWeight(), row.getBlankWeight())
        && sameDecimal(existing.getNetWeight(), row.getNetWeight())
        && sameDecimal(existing.getProcessFee(), row.getProcessFee())
        && sameDecimal(existing.getAgentFee(), row.getAgentFee())
        && sameDecimal(existing.getManualPrice(), row.getUnitPrice())
        && Objects.equals(existing.getTaxIncluded(), parseTaxIncluded(row.getTaxIncluded()));
  }

  private boolean sameDecimal(java.math.BigDecimal left, java.math.BigDecimal right) {
    if (left == null || right == null) {
      return left == right;
    }
    return left.compareTo(right) == 0;
  }

  private void expireOldVersion(PriceLinkedItem existing, LocalDate formulaEffectiveDate) {
    if (existing == null) {
      return;
    }
    LocalDate oldEffectiveFrom = existing.getEffectiveFrom();
    if (oldEffectiveFrom != null && !formulaEffectiveDate.isAfter(oldEffectiveFrom)) {
      throw new IllegalArgumentException(
          "formulaEffectiveDate 必须晚于当前公式版本 effective_from，避免生命周期倒挂: "
              + formulaEffectiveDate + " <= " + oldEffectiveFrom);
    }
    existing.setEffectiveTo(formulaEffectiveDate.minusDays(1));
    existing.setUpdatedAt(LocalDateTime.now());
    itemMapper.updateById(existing);
  }

  private PriceFixedItem findExistingFixed(PriceItemExcelImportRow row) {
    var query = Wrappers.lambdaQuery(PriceFixedItem.class)
        .eq(PriceFixedItem::getMaterialCode, trim(row.getMaterialCode()));
    String supplierCode = trim(row.getSupplierCode());
    if (supplierCode == null) {
      query.isNull(PriceFixedItem::getSupplierCode);
    } else {
      query.eq(PriceFixedItem::getSupplierCode, supplierCode);
    }
    String specModel = trim(row.getSpecModel());
    if (specModel == null) {
      query.isNull(PriceFixedItem::getSpecModel);
    } else {
      query.eq(PriceFixedItem::getSpecModel, specModel);
    }
    if (row.getEffectiveFrom() == null) {
      query.isNull(PriceFixedItem::getEffectiveFrom);
    } else {
      query.eq(PriceFixedItem::getEffectiveFrom, row.getEffectiveFrom());
    }
    return fixedItemMapper.selectOne(query.last("LIMIT 1"));
  }

  /** Excel 的"是否含税"接受 0/1、true/false、"是"/"否"；空默认 1（含税）。 */
  private Integer parseTaxIncluded(String raw) {
    if (!StringUtils.hasText(raw)) {
      return 1;
    }
    String v = raw.trim().toLowerCase();
    if (v.equals("0") || v.equals("false") || v.equals("否")) {
      return 0;
    }
    return 1;
  }

  private String trim(String v) {
    if (!StringUtils.hasText(v)) {
      return null;
    }
    return v.trim();
  }

  private String resolveBusinessUnitType(String requestedBusinessUnitType) {
    if (StringUtils.hasText(requestedBusinessUnitType)) {
      return requestedBusinessUnitType.trim();
    }
    String current = BusinessUnitContext.getCurrentBusinessUnitType();
    return StringUtils.hasText(current) ? current.trim() : "DEFAULT";
  }

  private String currentOperator() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && StringUtils.hasText(authentication.getName())) {
      return authentication.getName();
    }
    return "system";
  }

  private String sha256(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(bytes == null ? new byte[0] : bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  /**
   * EasyExcel 监听器 —— 行装进 rows，解析异常记入 errors 不中断导入。
   *
   * <p>设计同 {@code FinanceBasePriceImportServiceImpl.CollectingListener}：独立静态类便于测试
   * 时直接喂已解析的 List 跳过 IO。
   */
  private static final class AutoBindingPlan {
    private InfluenceFactorRef material;
    private InfluenceFactorRef scrap;
  }

  private record V2ImportContext(
      boolean enabled,
      Long factorUploadBatchId,
      Map<Integer, V2BindingPlan> plansByExcelRow,
      Map<String, ResolvedFactorRef> importedFactorVariableByAlias) {
    private static V2ImportContext disabled() {
      return new V2ImportContext(false, null, Map.of(), Map.of());
    }
  }

  private record V2BindingPlan(
      Long factorUploadBatchId,
      LinkedFormulaRow formulaRow,
      List<ResolvedFactorRef> resolvedRefs,
      List<com.sanhua.marketingcost.dto.BindingCandidate> candidates) {
    private V2BindingPlan(Long factorUploadBatchId, LinkedFormulaRow formulaRow) {
      this(factorUploadBatchId, formulaRow, new ArrayList<>(), new ArrayList<>());
    }

    private boolean formulaAvailable() {
      return formulaRow != null && Boolean.TRUE.equals(formulaRow.getHasFormula());
    }
  }

  private static final class InfluenceFactorRef {
    private String sheetName;
    private int rowNumber;
    private Integer seq;
    private String factorName;
    private String shortName;
    private String factorCode;
    private String priceSource;

    private String display() {
      return sheetName + "!E" + rowNumber + " -> " + shortName + " / " + priceSource;
    }
  }

  private static final class CollectingListener
      extends AnalysisEventListener<PriceItemExcelImportRow> {

    private final List<CollectedImportRow> sink;
    private final List<ErrorRow> errors;

    CollectingListener(List<CollectedImportRow> sink, List<ErrorRow> errors) {
      this.sink = sink;
      this.errors = errors;
    }

    @Override
    public void invoke(PriceItemExcelImportRow data, AnalysisContext context) {
      Integer rowIndex = context == null || context.readRowHolder() == null
          ? null : context.readRowHolder().getRowIndex() + 1;
      sink.add(new CollectedImportRow(rowIndex == null ? 0 : rowIndex, data));
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
      // no-op
    }

    @Override
    public void onException(Exception exception, AnalysisContext context) {
      Integer rowIndex = context == null || context.readRowHolder() == null
          ? null : context.readRowHolder().getRowIndex() + 1;
      errors.add(new ErrorRow(rowIndex, null, null,
          "Excel 解析失败: " + exception.getMessage()));
    }
  }

  private record CollectedImportRow(int rowNumber, PriceItemExcelImportRow row) {}

  private String resolvePricingMonth(String pricingMonth) {
    if (StringUtils.hasText(pricingMonth)) {
      return pricingMonth.trim();
    }
    PriceLinkedItem latest = itemMapper.selectOne(Wrappers.lambdaQuery(PriceLinkedItem.class)
        .select(PriceLinkedItem::getPricingMonth)
        .orderByDesc(PriceLinkedItem::getPricingMonth)
        .last("LIMIT 1"));
    return latest == null ? null : latest.getPricingMonth();
  }

  private PriceLinkedItem findExisting(String pricingMonth,
      PriceLinkedItemImportRequest.PriceLinkedItemImportRow row) {
    var query = Wrappers.lambdaQuery(PriceLinkedItem.class)
        .eq(PriceLinkedItem::getPricingMonth, pricingMonth)
        .eq(PriceLinkedItem::getMaterialCode, row.getMaterialCode());
    if (StringUtils.hasText(row.getSupplierCode())) {
      query.eq(PriceLinkedItem::getSupplierCode, row.getSupplierCode());
    }
    if (StringUtils.hasText(row.getSpecModel())) {
      query.eq(PriceLinkedItem::getSpecModel, row.getSpecModel());
    }
    return itemMapper.selectOne(query.last("LIMIT 1"));
  }

  private void fillItem(PriceLinkedItem item, String pricingMonth,
      PriceLinkedItemImportRequest.PriceLinkedItemImportRow row) {
    item.setPricingMonth(pricingMonth);
    item.setOrgCode(row.getOrgCode());
    item.setSourceName(row.getSourceName());
    item.setSupplierName(row.getSupplierName());
    item.setSupplierCode(row.getSupplierCode());
    item.setPurchaseClass(row.getPurchaseClass());
    item.setMaterialName(row.getMaterialName());
    item.setMaterialCode(row.getMaterialCode());
    item.setSpecModel(row.getSpecModel());
    item.setUnit(row.getUnit());
    // Plan B T6 一致性：JSON 导入路径也过 Normalizer + Renderer，不接受前端传的中文列
    String rawFormula = row.getFormulaExpr();
    if (StringUtils.hasText(rawFormula)) {
      String normalized = formulaNormalizer.normalize(rawFormula);
      item.setFormulaExpr(normalized);
      item.setFormulaExprCn(formulaDisplayRenderer.renderCn(normalized));
    } else {
      item.setFormulaExpr(rawFormula);
      item.setFormulaExprCn(null);
    }
    item.setBlankWeight(row.getBlankWeight());
    item.setNetWeight(row.getNetWeight());
    item.setProcessFee(row.getProcessFee());
    item.setAgentFee(row.getAgentFee());
    item.setManualPrice(row.getManualPrice());
    if (row.getTaxIncluded() != null) {
      item.setTaxIncluded(row.getTaxIncluded() ? 1 : 0);
    }
    item.setEffectiveFrom(row.getEffectiveFrom());
    item.setEffectiveTo(row.getEffectiveTo());
    item.setOrderType(row.getOrderType());
    item.setQuota(row.getQuota());
  }

  private void merge(PriceLinkedItem item, PriceLinkedItemUpdateRequest request) {
    if (request == null) {
      return;
    }
    if (StringUtils.hasText(request.getPricingMonth())) {
      item.setPricingMonth(request.getPricingMonth().trim());
    }
    if (request.getOrgCode() != null) {
      item.setOrgCode(request.getOrgCode());
    }
    if (request.getSourceName() != null) {
      item.setSourceName(request.getSourceName());
    }
    if (request.getSupplierName() != null) {
      item.setSupplierName(request.getSupplierName());
    }
    if (request.getSupplierCode() != null) {
      item.setSupplierCode(request.getSupplierCode());
    }
    if (request.getPurchaseClass() != null) {
      item.setPurchaseClass(request.getPurchaseClass());
    }
    if (request.getMaterialName() != null) {
      item.setMaterialName(request.getMaterialName());
    }
    if (request.getMaterialCode() != null) {
      item.setMaterialCode(request.getMaterialCode());
    }
    if (request.getSpecModel() != null) {
      item.setSpecModel(request.getSpecModel());
    }
    if (request.getUnit() != null) {
      item.setUnit(request.getUnit());
    }
    // Plan B T6：normalize-on-save —— 任何写路径都过 Normalizer，DB 里只存 [code] 形式。
    // 失败直接抛 FormulaSyntaxException（@ControllerAdvice 转 400），不静默兜底。
    // formula_expr_cn 写时也派生：两列从此强一致，脏数据无从进入。
    if (request.getFormulaExpr() != null) {
      String raw = request.getFormulaExpr();
      if (StringUtils.hasText(raw)) {
        String normalized = formulaNormalizer.normalize(raw);
        item.setFormulaExpr(normalized);
        item.setFormulaExprCn(formulaDisplayRenderer.renderCn(normalized));
      } else {
        // 空串 / 纯空白：允许清空公式
        item.setFormulaExpr(raw);
        item.setFormulaExprCn(null);
      }
    }
    // Plan B T6：formula_expr_cn 改派生，前端写入忽略并 WARN 提示不再接受此字段
    if (request.getFormulaExprCn() != null) {
      log.warn("PriceLinkedItem.merge 收到 formulaExprCn 写入（已忽略，改由 Renderer 派生）: {}",
          request.getFormulaExprCn());
    }
    if (request.getBlankWeight() != null) {
      item.setBlankWeight(request.getBlankWeight());
    }
    if (request.getNetWeight() != null) {
      item.setNetWeight(request.getNetWeight());
    }
    if (request.getProcessFee() != null) {
      item.setProcessFee(request.getProcessFee());
    }
    if (request.getAgentFee() != null) {
      item.setAgentFee(request.getAgentFee());
    }
    if (request.getManualPrice() != null) {
      item.setManualPrice(request.getManualPrice());
    }
    if (request.getTaxIncluded() != null) {
      item.setTaxIncluded(request.getTaxIncluded() ? 1 : 0);
    }
    if (request.getEffectiveFrom() != null) {
      item.setEffectiveFrom(request.getEffectiveFrom());
    }
    if (request.getEffectiveTo() != null) {
      item.setEffectiveTo(request.getEffectiveTo());
    }
    if (request.getOrderType() != null) {
      item.setOrderType(request.getOrderType());
    }
    if (request.getQuota() != null) {
      item.setQuota(request.getQuota());
    }
  }

  /**
   * 把当前登录账号的业务单元显式写入联动价实体。
   *
   * <p>为什么不只靠 MetaObjectHandler：部分调用链下 insertFill 拿不到 SecurityContext
   * （导入历史批次里有落入 NULL 的行，会被 BusinessUnitInterceptor 过滤掉），
   * 在 Service 入口直接注入是最稳的写入边界。拿不到 BU（测试/定时任务）时保留原值，不强写 null。
   */
  private void applyCurrentBusinessUnit(PriceLinkedItem item) {
    String buType = BusinessUnitContext.getCurrentBusinessUnitType();
    if (StringUtils.hasText(buType)) {
      item.setBusinessUnitType(buType);
    }
  }

  /** 同上，针对固定价实体；固定价表也受 BusinessUnitInterceptor 过滤。 */
  private void applyCurrentBusinessUnit(PriceFixedItem item) {
    String buType = BusinessUnitContext.getCurrentBusinessUnitType();
    if (StringUtils.hasText(buType)) {
      item.setBusinessUnitType(buType);
    }
  }

  private PriceLinkedItemDto toDto(PriceLinkedItem item) {
    PriceLinkedItemDto dto = new PriceLinkedItemDto();
    dto.setId(item.getId());
    dto.setPricingMonth(item.getPricingMonth());
    dto.setOrgCode(item.getOrgCode());
    dto.setSourceName(item.getSourceName());
    dto.setSupplierName(item.getSupplierName());
    dto.setSupplierCode(item.getSupplierCode());
    dto.setPurchaseClass(item.getPurchaseClass());
    dto.setMaterialName(item.getMaterialName());
    dto.setMaterialCode(item.getMaterialCode());
    dto.setSpecModel(item.getSpecModel());
    dto.setUnit(item.getUnit());
    dto.setFormulaExpr(item.getFormulaExpr());
    // Plan B T6：formulaExprCn 由 normalized [code] 反向派生 —— 两列从此强一致，
    // 不再读 DB 原值（该列遗留数据可能漂移且将随 T7 回洗被刷新）
    dto.setFormulaExprCn(formulaDisplayRenderer.renderCn(item.getFormulaExpr()));
    // 列表行级公式健康检查 —— 前端据此在行下方标红（方案 A + C 加严）。
    // 两层防线：(1) Normalizer 抓未识别中文 / 括号不平衡；(2) Validator 抓相邻 value
    // 缺运算符 / 把算式错误包进方括号 / 裸 ASCII 引用未知 code。
    // 只拦 FormulaSyntaxException；其他 RuntimeException 要冒上去让 GlobalExceptionHandler 处理，不吞。
    String raw = item.getFormulaExpr();
    if (!StringUtils.hasText(raw)) {
      dto.setFormulaValid(true);
    } else {
      try {
        String normalized = formulaNormalizer.normalize(raw);
        formulaValidator.validate(normalized);
        dto.setFormulaValid(true);
      } catch (FormulaSyntaxException ex) {
        dto.setFormulaValid(false);
        dto.setFormulaError(ex.getMessage());
      }
    }
    dto.setBlankWeight(item.getBlankWeight());
    dto.setNetWeight(item.getNetWeight());
    dto.setProcessFee(item.getProcessFee());
    dto.setAgentFee(item.getAgentFee());
    dto.setManualPrice(item.getManualPrice());
    dto.setTaxIncluded(item.getTaxIncluded());
    dto.setEffectiveFrom(item.getEffectiveFrom());
    dto.setEffectiveTo(item.getEffectiveTo());
    dto.setOrderType(item.getOrderType());
    dto.setQuota(item.getQuota());
    dto.setUpdatedAt(item.getUpdatedAt());
    return dto;
  }

  private void logFormulaChangeIfNeeded(
      PriceLinkedItem item,
      PriceLinkedItemUpdateRequest request,
      String oldFormulaExpr,
      String oldFormulaExprCn) {
    if (formulaChangeLogMapper == null || item == null || request == null
        || request.getFormulaExpr() == null) {
      return;
    }
    if (Objects.equals(oldFormulaExpr, item.getFormulaExpr())
        && Objects.equals(oldFormulaExprCn, item.getFormulaExprCn())) {
      return;
    }
    PriceLinkedFormulaChangeLog changeLog = new PriceLinkedFormulaChangeLog();
    changeLog.setLinkedItemId(item.getId());
    changeLog.setMaterialCode(item.getMaterialCode());
    changeLog.setOldFormulaExpr(oldFormulaExpr);
    changeLog.setNewFormulaExpr(item.getFormulaExpr());
    changeLog.setOldFormulaExprCn(oldFormulaExprCn);
    changeLog.setNewFormulaExprCn(item.getFormulaExprCn());
    changeLog.setChangeSource("SYSTEM_UI");
    changeLog.setRemark("系统内修改联动公式");
    changeLog.setCreatedAt(LocalDateTime.now());
    formulaChangeLogMapper.insert(changeLog);
  }

  private record LinkedImportOutcome(
      PriceLinkedItem item,
      boolean created,
      boolean updated,
      boolean skipped) {
  }
}
