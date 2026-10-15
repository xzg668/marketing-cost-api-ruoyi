package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.LinkedImportedFileDto;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import org.springframework.security.access.AccessDeniedException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.BindingCandidate;
import com.sanhua.marketingcost.dto.BindingCandidateBuildResult;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.FactorRowRefSaveResult;
import com.sanhua.marketingcost.dto.FactorUploadBatchCreateRequest;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.dto.FormulaFactorRef;
import com.sanhua.marketingcost.dto.LinkedFormulaRow;
import com.sanhua.marketingcost.dto.LinkedFormulaWorkbookParseResult;
import com.sanhua.marketingcost.dto.PriceItemExcelImportRow;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceItemImportResponse.ErrorRow;
import com.sanhua.marketingcost.dto.PriceLinkedAutoBindingWriteRequest;
import com.sanhua.marketingcost.dto.PriceLinkedAutoBindingWriteResult;
import com.sanhua.marketingcost.dto.PriceLinkedImportCommand;
import com.sanhua.marketingcost.dto.PriceLinkedImportResultClassifyRequest;
import com.sanhua.marketingcost.dto.PriceLinkedItemDto;
import com.sanhua.marketingcost.dto.PriceLinkedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceLinkedItemUpdateRequest;
import com.sanhua.marketingcost.dto.PriceVariableBindingDto;
import com.sanhua.marketingcost.dto.ResolvedFactorRef;
import com.sanhua.marketingcost.dto.StandardBindingCheckRequest;
import com.sanhua.marketingcost.dto.StandardBindingDecision;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalPriceImportResult;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.entity.FactorUploadRowError;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.entity.PriceLinkedFormulaChangeLog;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.enums.FactorPriceConflictStrategy;
import com.sanhua.marketingcost.enums.FactorUploadImportPurpose;
import com.sanhua.marketingcost.enums.PriceLinkedImportEffectiveStrategy;
import com.sanhua.marketingcost.formula.normalize.FormulaDisplayRenderer;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.FormulaSyntaxException;
import com.sanhua.marketingcost.formula.normalize.FormulaValidator;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.mapper.FactorUploadRowErrorMapper;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedFormulaChangeLogMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.FactorMonthlyPriceUpsertService;
import com.sanhua.marketingcost.service.FactorUploadBatchService;
import com.sanhua.marketingcost.service.PriceLinkedAutoBindingWriteService;
import com.sanhua.marketingcost.service.PriceLinkedBindingCandidateBuilder;
import com.sanhua.marketingcost.service.PriceLinkedFactorWorkbookParser;
import com.sanhua.marketingcost.service.PriceLinkedFormulaFactorRefParser;
import com.sanhua.marketingcost.service.PriceLinkedFormulaFactorRefResolver;
import com.sanhua.marketingcost.service.PriceLinkedFormulaWorkbookParser;
import com.sanhua.marketingcost.service.PriceLinkedImportResultClassifier;
import com.sanhua.marketingcost.service.PriceLinkedItemService;
import com.sanhua.marketingcost.service.PriceLinkedStandardBindingService;
import com.sanhua.marketingcost.service.PriceVariableBindingService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalPriceCorrectionImportService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalPriceCorrectionWorkbook;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PriceLinkedItemServiceImpl implements PriceLinkedItemService {
  private static final Logger log = LoggerFactory.getLogger(PriceLinkedItemServiceImpl.class);

  /** "订单类型" 列常量；任一忽略大小写后匹配 "固定" 则走 fixed 分支，其余走 linked。 */
  private static final String ORDER_TYPE_FIXED = "固定";

  /** Excel 数据行 1-based 行号 = 表头行数 + 偏移；源表只有 1 行表头。 */
  private static final int HEADER_ROW_NUMBER = 1;

  private static final int EXCEL_ROW_OFFSET = HEADER_ROW_NUMBER + 1;

  private static final Set<String> MATERIAL_TOKEN_NAMES = Set.of("材料含税价格", "材料价格");
  private static final Set<String> SCRAP_TOKEN_NAMES = Set.of("废料含税价格", "废料价格");

  private final PriceLinkedItemMapper itemMapper;
  private final PriceFixedItemMapper fixedItemMapper;
  private final PriceVariableMapper priceVariableMapper;
  private final PriceVariableBindingService priceVariableBindingService;
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

  @Autowired private TechnicalPriceCorrectionImportService technicalPriceImport;

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
  private FactorUploadRowErrorMapper factorUploadRowErrorMapper;

  private boolean excelAutoBindingEnabled = true;

  public PriceLinkedItemServiceImpl(
      PriceLinkedItemMapper itemMapper,
      PriceFixedItemMapper fixedItemMapper,
      PriceVariableMapper priceVariableMapper,
      PriceVariableBindingService priceVariableBindingService,
      FormulaNormalizer formulaNormalizer,
      FormulaDisplayRenderer formulaDisplayRenderer,
      FormulaValidator formulaValidator) {
    this.itemMapper = itemMapper;
    this.fixedItemMapper = fixedItemMapper;
    this.priceVariableMapper = priceVariableMapper;
    this.priceVariableBindingService = priceVariableBindingService;

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
    var query =
        Wrappers.lambdaQuery(PriceLinkedItem.class).eq(PriceLinkedItem::getSourceKind, "PUBLIC");
    if (StringUtils.hasText(resolvedMonth)) {
      query.eq(PriceLinkedItem::getPricingMonth, resolvedMonth);
    }
    if (StringUtils.hasText(materialCode)) {
      query.like(PriceLinkedItem::getMaterialCode, materialCode.trim());
    }
    if (!includeHistory) {
      query
          .isNull(PriceLinkedItem::getEffectiveTo)
          .notExists(
              "SELECT 1 FROM lp_price_linked_item newer WHERE newer.deleted=0 AND"
                  + " newer.source_kind='PUBLIC' AND newer.rule_status='PUBLISHED' AND"
                  + " newer.business_unit_type=lp_price_linked_item.business_unit_type AND"
                  + " newer.pricing_month=lp_price_linked_item.pricing_month AND"
                  + " newer.rule_identity_key=lp_price_linked_item.rule_identity_key AND"
                  + " newer.rule_revision>lp_price_linked_item.rule_revision");
    }
    query.orderByAsc(PriceLinkedItem::getId);
    return itemMapper.selectList(query).stream().map(this::toDto).toList();
  }

  @Override
  public PageResult<PriceLinkedItemDto> page(
      String pricingMonth, String materialCode, boolean includeHistory, int page, int pageSize) {
    String resolvedMonth = resolvePricingMonth(pricingMonth);
    var query =
        Wrappers.lambdaQuery(PriceLinkedItem.class).eq(PriceLinkedItem::getSourceKind, "PUBLIC");
    if (StringUtils.hasText(resolvedMonth)) {
      query.eq(PriceLinkedItem::getPricingMonth, resolvedMonth);
    }
    if (StringUtils.hasText(materialCode)) {
      query.like(PriceLinkedItem::getMaterialCode, materialCode.trim());
    }
    if (!includeHistory) {
      query
          .isNull(PriceLinkedItem::getEffectiveTo)
          .notExists(
              "SELECT 1 FROM lp_price_linked_item newer WHERE newer.deleted=0 AND"
                  + " newer.source_kind='PUBLIC' AND newer.rule_status='PUBLISHED' AND"
                  + " newer.business_unit_type=lp_price_linked_item.business_unit_type AND"
                  + " newer.pricing_month=lp_price_linked_item.pricing_month AND"
                  + " newer.rule_identity_key=lp_price_linked_item.rule_identity_key AND"
                  + " newer.rule_revision>lp_price_linked_item.rule_revision");
    }
    query.orderByAsc(PriceLinkedItem::getId);
    Page<PriceLinkedItem> result =
        itemMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
    return new PageResult<>(
        result.getRecords().stream().map(this::toDto).toList(), result.getTotal());
  }

  private void requireMutableRule(PriceLinkedItem item) {
    if (item != null && OriginalLinkedRuleSnapshot.VERSION.equals(item.getExpressionVersion()))
      throw new IllegalArgumentException("已发布原稿规则不可修改或删除，请预检并发布新的原稿修订");
  }

  private void requirePublicSource(PriceLinkedItem item) {
    if (item != null && "TECH_SUPPLEMENTAL".equals(item.getSourceKind())) {
      throw new IllegalArgumentException("技术审批价格不能在公共价格页面修改或删除，请查看原补录任务");
    }
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
  @Transactional(rollbackFor = Exception.class)
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
  @Transactional(rollbackFor = Exception.class)
  public PriceLinkedItemDto update(Long id, PriceLinkedItemUpdateRequest request) {
    if (id == null) {
      return null;
    }
    PriceLinkedItem item = itemMapper.selectById(id);
    if (item == null) {
      return null;
    }
    requirePublicSource(item);
    requireMutableRule(item);
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
    String fallbackMonth =
        StringUtils.hasText(request.getPricingMonth()) ? request.getPricingMonth().trim() : null;
    List<PriceLinkedItemDto> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null || !StringUtils.hasText(row.getMaterialCode())) {
        continue;
      }
      String pricingMonth =
          StringUtils.hasText(row.getPricingMonth()) ? row.getPricingMonth().trim() : fallbackMonth;
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
    PriceLinkedItem item = itemMapper.selectById(id);
    requirePublicSource(item);
    requireMutableRule(item);
    return itemMapper.delete(
            Wrappers.lambdaQuery(PriceLinkedItem.class)
                .eq(PriceLinkedItem::getId, id)
                .eq(PriceLinkedItem::getSourceKind, "PUBLIC"))
        > 0;
  }

  @Override
  public PageResult<LinkedImportedFileDto> importedFiles(
      String pricingMonth, String businessUnitType, int page, int pageSize) {
    String month = FactorPriceScope.month(pricingMonth);
    String businessUnit = resolveOptionalBusinessUnitType(businessUnitType);
    if (!StringUtils.hasText(businessUnit)) throw new IllegalArgumentException("请选择业务单元");
    if (!BusinessUnitContext.isAdmin()
        && !businessUnit.equals(BusinessUnitContext.getCurrentBusinessUnitType())) {
      throw new AccessDeniedException("无权查看其他业务单元的导入文件");
    }
    // 沿用导入历史的上传人权限；普通用户只能查看自己的文件。
    String uploader = canViewAllUploaders() ? null : currentOperator();
    var query = Wrappers.lambdaQuery(FactorUploadBatch.class)
        .select(FactorUploadBatch::getId, FactorUploadBatch::getFileName,
            FactorUploadBatch::getPriceOrgCode, FactorUploadBatch::getLinkedRowCount,
            FactorUploadBatch::getUploadedBy, FactorUploadBatch::getPublishedAt)
        .eq(FactorUploadBatch::getImportType, "LINKED_ORIGINAL")
        .eq(FactorUploadBatch::getStatus, "PUBLISHED")
        .isNull(FactorUploadBatch::getTechnicalVersionId)
        .eq(FactorUploadBatch::getPriceMonth, month)
        .eq(FactorUploadBatch::getBusinessUnitType, businessUnit)
        .eq(StringUtils.hasText(uploader), FactorUploadBatch::getUploadedBy, uploader)
        .orderByDesc(FactorUploadBatch::getPublishedAt)
        .orderByDesc(FactorUploadBatch::getId);
    Page<FactorUploadBatch> result = factorUploadBatchMapper.selectPage(
        new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
    return new PageResult<>(result.getRecords().stream().map(batch ->
        new LinkedImportedFileDto(
            batch.getId(), batch.getFileName(), batch.getPriceOrgCode(), batch.getLinkedRowCount(),
            batch.getUploadedBy(), batch.getPublishedAt())).toList(), result.getTotal());
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
    return importExcel(
        input, pricingMonth, overwriteManual, businessUnitType, sourceFileName, null);
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
        input,
        pricingMonth,
        overwriteManual,
        businessUnitType,
        sourceFileName,
        effectiveStrategy,
        null,
        null);
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
    return importWorkbook(
        input,
        pricingMonth,
        overwriteManual,
        businessUnitType,
        sourceFileName,
        effectiveStrategy,
        formulaEffectiveDate,
        factorPriceConflictStrategy,
        null);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PriceItemImportResponse importExcel(PriceLinkedImportCommand command) {
    return importWorkbook(
        new ByteArrayInputStream(command.getFileBytes()),
        command.getPricingMonth(),
        command.isOverwriteManual(),
        command.getBusinessUnitType(),
        command.getSourceFileName(),
        command.getEffectiveStrategy(),
        command.getFormulaEffectiveDate(),
        command.getFactorPriceConflictStrategy(),
        command.getTechnicalPlan());
  }

  private PriceItemImportResponse importWorkbook(
      InputStream input,
      String pricingMonth,
      boolean overwriteManual,
      String businessUnitType,
      String sourceFileName,
      String effectiveStrategy,
      String formulaEffectiveDate,
      String factorPriceConflictStrategy,
      TechnicalPriceCorrectionWorkbook.Plan technicalPlan) {
    PriceItemImportResponse response = new PriceItemImportResponse();
    response.setBatchId(UUID.randomUUID().toString());
    String strategy = normalizeEffectiveStrategy(effectiveStrategy);
    response.setEffectiveStrategy(strategy);
    response.setImportPurpose(importPurposeForStrategy(strategy));
    if (!StringUtils.hasText(pricingMonth)) {
      response.setFactorPriceConflictStrategy(FactorPriceConflictStrategy.KEEP_EXISTING.getCode());
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
    V2ImportContext v2Context =
        excelAutoBindingEnabled
            ? prepareV2ImportContext(
                excelBytes,
                month,
                resolvedBusinessUnitType,
                sourceFileName,
                strategy,
                resolvedFactorConflictStrategy,
                response)
            : V2ImportContext.disabled();
    List<CollectedImportRow> rows = new ArrayList<>();
    List<ErrorRow> parseErrors = new ArrayList<>();
    Integer linkedSheetNo = findLinkedImportSheetNo(excelBytes);
    String linkedSheetName = findLinkedImportSheetName(excelBytes);
    try {
      if (technicalPlan != null) {
        for (var row : technicalPlan.rows())
          rows.add(new CollectedImportRow(row.rowNumber(), row.values()));
      } else
        EasyExcel.read(
                new ByteArrayInputStream(excelBytes),
                PriceItemExcelImportRow.class,
                new CollectingListener(rows, parseErrors))
            .sheet(linkedSheetNo == null ? 0 : linkedSheetNo)
            .headRowNumber(HEADER_ROW_NUMBER)
            .doRead();
    } catch (RuntimeException e) {
      response.getErrors().add(new ErrorRow(null, null, null, "Excel 解析失败: " + e.getMessage()));
      response.setSkipped(response.getSkipped() + 1);
      enrichAndPersistImportErrors(
          v2Context.factorUploadBatchId(), response, sourceFileName, linkedSheetName);
      finalizeImportBatch(v2Context.factorUploadBatchId(), response);
      applyVersionedSummaryAliases(response);
      return response;
    }

    for (CollectedImportRow collected : rows) {
      PriceItemExcelImportRow row = collected.row();
      int excelRow = collected.rowNumber();
      var technicalRow =
          technicalPlan == null ? null : technicalPlan.row(linkedSheetName, excelRow);
      if (technicalRow != null && !technicalRow.issues().isEmpty()) {
        response
            .getErrors()
            .add(
                buildImportError(
                    excelRow,
                    row,
                    row.getFormulaExpr(),
                    resolvedFormulaEffectiveDate,
                    linkedSheetName,
                    String.join("；", technicalRow.issues())));
        response.setSkipped(response.getSkipped() + 1);
        continue;
      }
      String validateError = validateRow(row);
      if (validateError != null) {
        response
            .getErrors()
            .add(
                buildImportError(
                    excelRow,
                    row,
                    row == null ? null : row.getFormulaExpr(),
                    resolvedFormulaEffectiveDate,
                    linkedSheetName,
                    validateError));
        response.setSkipped(response.getSkipped() + 1);
        continue;
      }
      // orderType 为 "固定" → fixed 分支；其余（空 / "联动"）默认走 linked
      if (ORDER_TYPE_FIXED.equals(trim(row.getOrderType()))) {
        upsertFixed(row, response);
        response.setFixedCount(response.getFixedCount() + 1);
      } else {
        V2BindingPlan v2Plan =
            v2Context.enabled() ? v2Context.plansByExcelRow().get(excelRow) : null;
        // 联动分支：公式不合法则跳过，不入库
        String formulaSource =
            formulaSourceForNormalize(row, v2Plan, v2Context.importedFactorVariableByAlias());
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
          response
              .getErrors()
              .add(
                  buildImportError(
                      excelRow,
                      row,
                      formulaSource,
                      resolvedFormulaEffectiveDate,
                      linkedSheetName,
                      "联动公式非法或无法解析: " + formulaSource));
          response.setSkipped(response.getSkipped() + 1);
          continue;
        }
        applyExcelGoldenPrice(row, v2Plan);
        LinkedImportOutcome linkedOutcome;
        try {
          linkedOutcome =
              upsertLinked(
                  row,
                  month,
                  normalizedFormula,
                  resolvedFormulaEffectiveDate,
                  resolvedBusinessUnitType,
                  technicalPlan,
                  technicalRow);
        } catch (IllegalArgumentException ex) {
          response
              .getErrors()
              .add(
                  buildImportError(
                      excelRow,
                      row,
                      formulaSource,
                      resolvedFormulaEffectiveDate,
                      linkedSheetName,
                      ex.getMessage()));
          response.setSkipped(response.getSkipped() + 1);
          continue;
        }
        if (technicalRow != null)
          response
              .getTechnicalResults()
              .add(
                  new TechnicalPriceImportResult(
                      technicalRow.itemKey(),
                      linkedSheetName,
                      excelRow,
                      row.getMaterialCode(),
                      linkedOutcome.item().getId(),
                      linkedOutcome.skipped() ? "REUSED" : "IMPORTED",
                      null,
                      null));
        if (linkedOutcome.skipped()) {
          PriceLinkedItem item = linkedOutcome.item();
          if (v2Context.enabled() && missingCurrentV2Bindings(item, v2Plan)) {
            applyV2AutoBindings(
                item,
                v2Plan,
                month,
                resolvedBusinessUnitType,
                excelRow,
                overwriteManualForStrategy(strategy, overwriteManual),
                response);
          }
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
          // Technical supplements carry a validated text formula. The ordinary Excel-cell
          // auto-binding path must not report its deliberately empty unit-price cell as an error.
          if (technicalRow == null || (v2Plan != null && v2Plan.formulaAvailable())) {
            applyV2AutoBindings(
                item,
                v2Plan,
                month,
                resolvedBusinessUnitType,
                excelRow,
                overwriteManualForStrategy(strategy, overwriteManual),
                response);
          }
        }
        response.setLinkedCount(response.getLinkedCount() + 1);
      }
    }

    if (!parseErrors.isEmpty()) {
      response.getErrors().addAll(parseErrors);
      response.setSkipped(response.getSkipped() + parseErrors.size());
    }
    enrichAndPersistImportErrors(
        v2Context.factorUploadBatchId(), response, sourceFileName, linkedSheetName);
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
    int rowErrorCount = response.getErrors() == null ? 0 : response.getErrors().size();
    int errorCount = rowErrorCount + response.getBindingErrorCount();
    batch.setLinkedRowCount(response.getLinkedCount());
    batch.setAutoBindingCount(response.getAutoBindingCount());
    batch.setWarningCount(
        response.getManualSkippedCount()
            + response.getConflictBindingCount()
            + response.getQuoteBaseConflictCount());
    batch.setErrorCount(errorCount);
    batch.setStatus(errorCount > 0 ? "PARTIAL" : "SUCCESS");
    batch.setFinishedAt(LocalDateTime.now());
    batch.setUpdatedAt(LocalDateTime.now());
    factorUploadBatchMapper.updateById(batch);
  }

  private ErrorRow buildImportError(
      Integer rowNumber,
      PriceItemExcelImportRow sourceRow,
      String formula,
      LocalDate formulaEffectiveDate,
      String sourceSheetName,
      String message) {
    ErrorRow error =
        new ErrorRow(
            rowNumber,
            sourceRow == null ? null : sourceRow.getMaterialCode(),
            sourceRow == null ? null : sourceRow.getOrderType(),
            message);
    if (sourceRow != null) {
      error.setMaterialName(sourceRow.getMaterialName());
      error.setSupplierCode(sourceRow.getSupplierCode());
    }
    error.setSourceSheetName(sourceSheetName);
    error.setFormula(formula);
    error.setFormulaEffectiveDate(
        formulaEffectiveDate == null ? null : formulaEffectiveDate.toString());
    return error;
  }

  private void enrichAndPersistImportErrors(
      Long factorUploadBatchId,
      PriceItemImportResponse response,
      String sourceFileName,
      String defaultSourceSheetName) {
    if (response == null || response.getErrors() == null) {
      return;
    }
    for (ErrorRow error : response.getErrors()) {
      if (error == null) {
        continue;
      }
      if (!StringUtils.hasText(error.getSourceWorkbookName())) {
        error.setSourceWorkbookName(sourceFileName);
      }
      if (!StringUtils.hasText(error.getSourceSheetName())) {
        error.setSourceSheetName(defaultSourceSheetName);
      }
      if (!StringUtils.hasText(error.getFormulaEffectiveDate())) {
        error.setFormulaEffectiveDate(response.getFormulaEffectiveDate());
      }
      if (!StringUtils.hasText(error.getErrorStage())) {
        error.setErrorStage(classifyImportErrorStage(error.getMessage()));
      }
      if (!StringUtils.hasText(error.getErrorCode())) {
        error.setErrorCode(classifyImportErrorCode(error.getMessage()));
      }
      if (!StringUtils.hasText(error.getSuggestion())) {
        error.setSuggestion(importErrorSuggestion(error.getErrorCode()));
      }
    }
    persistImportErrors(factorUploadBatchId, response.getErrors());
  }

  private void persistImportErrors(Long factorUploadBatchId, List<ErrorRow> errors) {
    if (factorUploadRowErrorMapper == null
        || factorUploadBatchId == null
        || errors == null
        || errors.isEmpty()) {
      return;
    }
    for (ErrorRow error : errors) {
      if (error == null) {
        continue;
      }
      FactorUploadRowError entity = new FactorUploadRowError();
      entity.setFactorUploadBatchId(factorUploadBatchId);
      entity.setSourceWorkbookName(limitText(error.getSourceWorkbookName(), 255));
      entity.setSourceSheetName(limitText(error.getSourceSheetName(), 128));
      entity.setExcelRowNumber(error.getRowNumber());
      entity.setMaterialCode(limitText(error.getMaterialCode(), 64));
      entity.setMaterialName(limitText(error.getMaterialName(), 255));
      entity.setSupplierCode(limitText(error.getSupplierCode(), 64));
      entity.setOrderType(limitText(error.getOrderType(), 64));
      entity.setFormula(error.getFormula());
      entity.setFormulaEffectiveDate(parseLocalDate(error.getFormulaEffectiveDate()));
      entity.setErrorStage(limitText(error.getErrorStage(), 32));
      entity.setErrorCode(limitText(error.getErrorCode(), 64));
      entity.setErrorMessage(limitText(firstText(error.getMessage(), "导入失败"), 2048));
      entity.setSuggestion(limitText(error.getSuggestion(), 1024));
      entity.setCreatedAt(LocalDateTime.now());
      factorUploadRowErrorMapper.insert(entity);
    }
  }

  private String classifyImportErrorStage(String message) {
    String text = firstText(message, "");
    if (text.contains("生命周期倒挂") || text.contains("formulaEffectiveDate")) {
      return "FORMULA_VERSION";
    }
    if (text.contains("公式非法") || text.contains("无法解析")) {
      return "FORMULA_PARSE";
    }
    if (text.contains("Excel 解析") || text.contains("Excel 读取")) {
      return "EXCEL_PARSE";
    }
    if (text.contains("必填")) {
      return "ROW_VALIDATE";
    }
    if (text.contains("自动绑定") || text.contains("影响因素无法登记变量")) {
      return "AUTO_BIND";
    }
    return "ROW_IMPORT";
  }

  private String classifyImportErrorCode(String message) {
    String text = firstText(message, "");
    if (text.contains("生命周期倒挂") || text.contains("formulaEffectiveDate")) {
      return "FORMULA_LIFECYCLE_OVERLAP";
    }
    if (text.contains("公式非法") || text.contains("无法解析")) {
      return "FORMULA_INVALID";
    }
    if (text.contains("Excel 解析") || text.contains("Excel 读取")) {
      return "EXCEL_PARSE_FAILED";
    }
    if (text.contains("必填")) {
      return "REQUIRED_FIELD_MISSING";
    }
    if (text.contains("自动绑定") || text.contains("影响因素无法登记变量")) {
      return "AUTO_BIND_FAILED";
    }
    return "IMPORT_ERROR";
  }

  private String importErrorSuggestion(String errorCode) {
    if ("FORMULA_LIFECYCLE_OVERLAP".equals(errorCode)) {
      return "请将新公式生效日期调整为晚于当前版本生效日期，再重新导入";
    }
    if ("FORMULA_INVALID".equals(errorCode)) {
      return "请检查该行联动公式及引用单元格，修正后重新导入";
    }
    if ("EXCEL_PARSE_FAILED".equals(errorCode)) {
      return "请检查 Excel 模板、单元格格式及必填列后重新上传";
    }
    if ("REQUIRED_FIELD_MISSING".equals(errorCode)) {
      return "请补齐该行必填字段后重新导入";
    }
    if ("AUTO_BIND_FAILED".equals(errorCode)) {
      return "请检查公式引用的影响因素，必要时进入人工绑定";
    }
    if ("FACTOR_PRICE_ERROR".equals(errorCode) || "FACTOR_REFERENCE_ERROR".equals(errorCode)) {
      return "请检查影响因素表对应行的数据后重新导入";
    }
    return "请根据失败原因修正 Excel 后重新导入";
  }

  private LocalDate parseLocalDate(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private String limitText(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  private String normalizeEffectiveStrategy(String effectiveStrategy) {
    String normalized = trim(effectiveStrategy);
    if (PriceLinkedImportEffectiveStrategy.APPEND_ONLY.getCode().equals(normalized)) {
      return normalized;
    }
    return PriceLinkedImportEffectiveStrategy.OVERRIDE_EFFECTIVE.getCode();
  }

  private LocalDate resolveFormulaEffectiveDate(String pricingMonth, String formulaEffectiveDate) {
    String raw =
        StringUtils.hasText(formulaEffectiveDate)
            ? formulaEffectiveDate.trim()
            : pricingMonth.trim() + "-01";
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException("formulaEffectiveDate 格式错误，应为 yyyy-MM-dd: " + raw);
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

  private String firstText(String first, String fallback) {
    return StringUtils.hasText(first) ? first : fallback;
  }

  private String resolveOptionalBusinessUnitType(String requestedBusinessUnitType) {
    if (StringUtils.hasText(requestedBusinessUnitType)) {
      return requestedBusinessUnitType.trim();
    }
    String current = BusinessUnitContext.getCurrentBusinessUnitType();
    return StringUtils.hasText(current) ? current.trim() : null;
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
        .anyMatch(
            authority ->
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
    String fileName =
        StringUtils.hasText(sourceFileName) ? sourceFileName.trim() : "monthly-import.xlsx";
    FactorWorkbookParseResult factorParseResult =
        factorWorkbookParser.parse(new ByteArrayInputStream(excelBytes), fileName);
    if (factorParseResult == null || factorParseResult.getSheets().isEmpty()) {
      return V2ImportContext.disabled();
    }

    FactorUploadBatch batch =
        factorUploadBatchService.createFactorBatch(
            factorBatchRequest(
                factorParseResult,
                priceMonth,
                businessUnitType,
                fileName,
                excelBytes,
                effectiveStrategy));
    Long factorUploadBatchId = batch == null ? null : batch.getId();
    response.setFactorUploadBatchId(factorUploadBatchId);
    if (factorUploadBatchId != null) {
      response.setBatchId(String.valueOf(factorUploadBatchId));
    }

    FactorMonthlyPriceUpsertResult upsertResult =
        factorMonthlyPriceUpsertService.upsert(
            factorParseResult,
            priceMonth,
            businessUnitType,
            currentOperator(),
            factorUploadBatchId,
            factorPriceConflictStrategy);
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
      ErrorRow errorRow = new ErrorRow(error.getSourceRowNumber(), null, null, error.getMessage());
      errorRow.setSourceSheetName(error.getSourceSheetName());
      errorRow.setErrorStage("FACTOR_IMPORT");
      errorRow.setErrorCode("FACTOR_PRICE_ERROR");
      response.getErrors().add(errorRow);
      response.setSkipped(response.getSkipped() + 1);
    }

    FactorRowRefSaveResult rowRefResult =
        factorUploadBatchService.saveRowRefs(factorUploadBatchId, factorParseResult, upsertResult);
    for (FactorRowRefSaveResult.RowError error : rowRefResult.getErrors()) {
      ErrorRow errorRow = new ErrorRow(error.getSourceRowNumber(), null, null, error.getMessage());
      errorRow.setSourceSheetName(error.getSourceSheetName());
      errorRow.setErrorStage("FACTOR_IMPORT");
      errorRow.setErrorCode("FACTOR_REFERENCE_ERROR");
      response.getErrors().add(errorRow);
      response.setSkipped(response.getSkipped() + 1);
    }

    Map<String, ResolvedFactorRef> importedFactorByAlias =
        buildImportedFactorAliasMap(upsertResult);
    Map<Integer, V2BindingPlan> plansByExcelRow =
        buildV2BindingPlans(
            excelBytes, fileName, factorUploadBatchId, findLinkedImportSheetName(excelBytes));
    return new V2ImportContext(true, factorUploadBatchId, plansByExcelRow, importedFactorByAlias);
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
      byte[] excelBytes, String fileName, Long factorUploadBatchId, String linkedSheetName) {
    Map<Integer, V2BindingPlan> plans = new HashMap<>();
    LinkedFormulaWorkbookParseResult formulaWorkbook =
        formulaWorkbookParser.parse(new ByteArrayInputStream(excelBytes), fileName);
    for (var sheet : formulaWorkbook.getSheets()) {
      if (StringUtils.hasText(linkedSheetName) && !linkedSheetName.equals(sheet.getSheetName())) {
        continue;
      }
      for (LinkedFormulaRow formulaRow : sheet.getRows()) {
        V2BindingPlan plan = new V2BindingPlan(factorUploadBatchId, formulaRow);
        if (Boolean.TRUE.equals(formulaRow.getHasFormula())) {
          List<FormulaFactorRef> formulaRefs =
              formulaFactorRefParser.parse(formulaRow.getPriceCellFormula());
          plan.resolvedRefs.addAll(
              formulaFactorRefResolver.resolve(factorUploadBatchId, formulaRefs));
          BindingCandidateBuildResult candidates =
              bindingCandidateBuilder.build(
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
      List<StandardBindingDecision> decisions =
          standardBindingService.checkAgainstPreviousRule(
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

  private boolean missingCurrentV2Bindings(PriceLinkedItem item, V2BindingPlan plan) {
    if (item == null || item.getId() == null || plan == null || plan.candidates().isEmpty()) {
      return false;
    }
    List<PriceVariableBindingDto> currentBindings =
        priceVariableBindingService.listByLinkedItem(item.getId());
    if (currentBindings == null) {
      return false;
    }
    Set<String> currentTokenNames = new HashSet<>();
    for (PriceVariableBindingDto binding : currentBindings) {
      if (binding != null && StringUtils.hasText(binding.getTokenName())) {
        currentTokenNames.add(binding.getTokenName().trim());
      }
    }
    for (BindingCandidate candidate : plan.candidates()) {
      if (candidate == null || !StringUtils.hasText(candidate.getTokenName())) {
        continue;
      }
      if (!hasAnyCurrentToken(currentTokenNames, equivalentTokenNames(candidate.getTokenName()))) {
        return true;
      }
    }
    return false;
  }

  private boolean hasAnyCurrentToken(
      Set<String> currentTokenNames, Set<String> acceptedTokenNames) {
    if (currentTokenNames == null
        || currentTokenNames.isEmpty()
        || acceptedTokenNames == null
        || acceptedTokenNames.isEmpty()) {
      return false;
    }
    for (String tokenName : acceptedTokenNames) {
      if (currentTokenNames.contains(tokenName)) {
        return true;
      }
    }
    return false;
  }

  private Set<String> equivalentTokenNames(String tokenName) {
    if (!StringUtils.hasText(tokenName)) {
      return Set.of();
    }
    String normalized = tokenName.trim();
    if (MATERIAL_TOKEN_NAMES.contains(normalized)) {
      return MATERIAL_TOKEN_NAMES;
    }
    if (SCRAP_TOKEN_NAMES.contains(normalized)) {
      return SCRAP_TOKEN_NAMES;
    }
    return Set.of(normalized);
  }

  private StandardBindingCheckRequest standardBindingRequest(
      PriceLinkedItem item, V2BindingPlan plan, String priceMonth, String businessUnitType) {
    StandardBindingCheckRequest request = new StandardBindingCheckRequest();
    request.setBusinessUnitType(businessUnitType);
    request.setLinkedItemId(item.getId());
    request.setOrganizationCode(item.getOrgCode());
    request.setPricingMonth(priceMonth);
    request.setUnit(item.getUnit());
    request.setSpecModel(item.getSpecModel());
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
    String source =
        StringUtils.hasText(excelDerived)
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
    if (existing != null
        && !Objects.equals(existing.getFactorIdentityId(), ref.getFactorIdentityId())) {
      log.warn(
          "本次导入影响因素别名冲突，跳过 alias={} existingFactorIdentityId={} newFactorIdentityId={}",
          key,
          existing.getFactorIdentityId(),
          ref.getFactorIdentityId());
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
        "(?<![\\p{L}\\p{N}_\\[])" + Pattern.quote(token) + "(?![\\p{L}\\p{N}_\\]])");
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
    if (plan == null
        || plan.formulaRow() == null
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
        replacement =
            factorIndex == 0
                ? "[__material]"
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
    if (text.contains("材料含税价格")
        || text.contains("材料价格")
        || text.contains("废料含税价格")
        || text.contains("废料价格")) {
      return true;
    }
    return refs != null
        && refs.size() == 2
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
    PriceVariable existing =
        priceVariableMapper.selectOne(
            Wrappers.lambdaQuery(PriceVariable.class)
                .eq(PriceVariable::getVariableCode, variableCode)
                .last("LIMIT 1"));
    if (existing == null) {
      PriceVariable variable = new PriceVariable();
      variable.setVariableCode(variableCode);
      variable.setVariableName(
          StringUtils.hasText(ref.getShortName()) ? ref.getShortName().trim() : variableCode);
      variable.setAliasesJson(factorAliasJson(ref, variableCode));
      variable.setSourceType("FACTOR_MONTHLY_PRICE");
      variable.setSourceTable("lp_factor_monthly_price");
      variable.setSourceField("price");
      variable.setScope("BASE_PRICE");
      variable.setStatus("active");
      variable.setTaxMode("INCL");
      variable.setFactorType("FINANCE_FACTOR");
      variable.setResolverKind("FINANCE");
      StringBuilder params =
          new StringBuilder("{\"factorIdentityId\":").append(ref.getFactorIdentityId());
      if (ref.getFactorMonthlyPriceId() != null) {
        params.append(",\"factorMonthlyPriceId\":").append(ref.getFactorMonthlyPriceId());
      }
      if (StringUtils.hasText(ref.getShortName())) {
        params
            .append(",\"shortName\":\"")
            .append(jsonEscape(ref.getShortName().trim()))
            .append("\"");
      }
      if (StringUtils.hasText(ref.getPriceSource())) {
        params
            .append(",\"priceSource\":\"")
            .append(jsonEscape(ref.getPriceSource().trim()))
            .append("\"");
      }
      params.append(",\"buScoped\":true}");
      variable.setResolverParams(params.toString());
      priceVariableMapper.insert(variable);

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
    if (ref != null
        && StringUtils.hasText(ref.getFactorSeqNo())
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
    return raw != null && raw.contains("材料价格") && !raw.contains("材料含税价格") ? "材料价格" : "材料含税价格";
  }

  private String scrapTokenName(PriceItemExcelImportRow row) {
    String raw = row == null ? null : row.getFormulaExpr();
    return raw != null && raw.contains("废料价格") && !raw.contains("废料含税价格") ? "废料价格" : "废料含税价格";
  }

  private String defaultPriceSource(String priceSource) {
    return StringUtils.hasText(priceSource) ? priceSource.trim() : "未指定";
  }

  private String jsonEscape(String raw) {
    return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private PriceLinkedItem upsertLinked(
      PriceItemExcelImportRow row, String pricingMonth, String normalizedFormula) {
    return upsertLinked(
            row,
            pricingMonth,
            normalizedFormula,
            parseMonthStart(pricingMonth),
            resolveBusinessUnitType(null))
        .item();
  }

  private LinkedImportOutcome upsertLinked(
      PriceItemExcelImportRow row,
      String pricingMonth,
      String normalizedFormula,
      LocalDate formulaEffectiveDate,
      String businessUnitType) {
    return upsertLinked(
        row, pricingMonth, normalizedFormula, formulaEffectiveDate, businessUnitType, null, null);
  }

  private LinkedImportOutcome upsertLinked(
      PriceItemExcelImportRow row,
      String pricingMonth,
      String normalizedFormula,
      LocalDate formulaEffectiveDate,
      String businessUnitType,
      TechnicalPriceCorrectionWorkbook.Plan plan,
      TechnicalPriceCorrectionWorkbook.ImportRow technicalRow) {
    PriceLinkedItem existing =
        plan == null
            ? findCurrentLinkedVersion(pricingMonth, businessUnitType, row)
            : technicalPriceImport.current(plan.scope().version().getId(), technicalRow.itemKey());
    if (existing != null
        && sameLinkedFormulaVersion(existing, row, normalizedFormula)
        && (plan == null
            || Objects.equals(trim(existing.getSupplierCode()), trim(row.getSupplierCode()))
                && Objects.equals(trim(existing.getSupplierName()), trim(row.getSupplierName())))) {
      return new LinkedImportOutcome(existing, false, false, true);
    }
    if (existing != null && plan == null) {
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
    if (plan == null) applyCurrentBusinessUnit(item);
    else technicalPriceImport.identify(item, plan, technicalRow);
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
    var query =
        Wrappers.lambdaQuery(PriceLinkedItem.class)
            .eq(PriceLinkedItem::getSourceKind, "PUBLIC")
            .eq(PriceLinkedItem::getPricingMonth, pricingMonth)
            .eq(PriceLinkedItem::getMaterialCode, trim(row.getMaterialCode()))
            .eq(PriceLinkedItem::getDeleted, 0);
    if (StringUtils.hasText(businessUnitType)) {
      query.eq(PriceLinkedItem::getBusinessUnitType, businessUnitType.trim());
    } else {
      query.isNull(PriceLinkedItem::getBusinessUnitType);
    }
    // 同月同料号按供应商分别维护版本：代码优先，代码为空时按名称识别。
    // effective_to 不再定义“当前公式”；当前版本只认最新成功导入时间。
    String supplierCode = trim(row.getSupplierCode());
    if (supplierCode != null) {
      query.eq(PriceLinkedItem::getSupplierCode, supplierCode);
    } else {
      query.and(
          q ->
              q.isNull(PriceLinkedItem::getSupplierCode)
                  .or()
                  .eq(PriceLinkedItem::getSupplierCode, ""));
      String supplierName = trim(row.getSupplierName());
      if (supplierName != null) {
        query.eq(PriceLinkedItem::getSupplierName, supplierName);
      } else {
        query.and(
            q ->
                q.isNull(PriceLinkedItem::getSupplierName)
                    .or()
                    .eq(PriceLinkedItem::getSupplierName, ""));
      }
    }
    return itemMapper.selectOne(
        query
            .orderByDesc(PriceLinkedItem::getCreatedAt)
            .orderByDesc(PriceLinkedItem::getId)
            .last("LIMIT 1"));
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
    // 生效/失效日期只保留为历史展示元数据，不再阻止同月同日的再次导入。
    existing.setEffectiveTo(formulaEffectiveDate);
    existing.setUpdatedAt(LocalDateTime.now());
    itemMapper.updateById(existing);
  }

  private PriceFixedItem findExistingFixed(PriceItemExcelImportRow row) {
    var query =
        Wrappers.lambdaQuery(PriceFixedItem.class)
            .eq(PriceFixedItem::getSourceKind, "PUBLIC")
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
   * <p>设计同 {@code 财务基价表.CollectingListener}：独立静态类便于测试 时直接喂已解析的 List 跳过 IO。
   */
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
      List<BindingCandidate> candidates) {
    private V2BindingPlan(Long factorUploadBatchId, LinkedFormulaRow formulaRow) {
      this(factorUploadBatchId, formulaRow, new ArrayList<>(), new ArrayList<>());
    }

    private boolean formulaAvailable() {
      return formulaRow != null && Boolean.TRUE.equals(formulaRow.getHasFormula());
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
      Integer rowIndex =
          context == null || context.readRowHolder() == null
              ? null
              : context.readRowHolder().getRowIndex() + 1;
      sink.add(new CollectedImportRow(rowIndex == null ? 0 : rowIndex, data));
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
      // no-op
    }

    @Override
    public void onException(Exception exception, AnalysisContext context) {
      Integer rowIndex =
          context == null || context.readRowHolder() == null
              ? null
              : context.readRowHolder().getRowIndex() + 1;
      errors.add(new ErrorRow(rowIndex, null, null, "Excel 解析失败: " + exception.getMessage()));
    }
  }

  private record CollectedImportRow(int rowNumber, PriceItemExcelImportRow row) {}

  private String resolvePricingMonth(String pricingMonth) {
    if (StringUtils.hasText(pricingMonth)) {
      return pricingMonth.trim();
    }
    PriceLinkedItem latest =
        itemMapper.selectOne(
            Wrappers.lambdaQuery(PriceLinkedItem.class)
                .eq(PriceLinkedItem::getSourceKind, "PUBLIC")
                .select(PriceLinkedItem::getPricingMonth)
                .orderByDesc(PriceLinkedItem::getPricingMonth)
                .last("LIMIT 1"));
    return latest == null ? null : latest.getPricingMonth();
  }

  private PriceLinkedItem findExisting(
      String pricingMonth, PriceLinkedItemImportRequest.PriceLinkedItemImportRow row) {
    var query =
        Wrappers.lambdaQuery(PriceLinkedItem.class)
            .eq(PriceLinkedItem::getSourceKind, "PUBLIC")
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

  private void fillItem(
      PriceLinkedItem item,
      String pricingMonth,
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
      log.warn(
          "PriceLinkedItem.merge 收到 formulaExprCn 写入（已忽略，改由 Renderer 派生）: {}",
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
   * <p>为什么不只靠 MetaObjectHandler：部分调用链下 insertFill 拿不到 SecurityContext （导入历史批次里有落入 NULL 的行，会被
   * BusinessUnitInterceptor 过滤掉）， 在 Service 入口直接注入是最稳的写入边界。拿不到 BU（测试/定时任务）时保留原值，不强写 null。
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
    dto.setExpressionVersion(item.getExpressionVersion());
    dto.setRuleRevision(item.getRuleRevision());
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
    boolean original = OriginalLinkedRuleSnapshot.VERSION.equals(item.getExpressionVersion());
    dto.setFormulaExprCn(
        original
            ? item.getSourceFormulaExpr()
            : formulaDisplayRenderer.renderCn(item.getFormulaExpr()));
    // 列表行级公式健康检查 —— 前端据此在行下方标红（方案 A + C 加严）。
    // 两层防线：(1) Normalizer 抓未识别中文 / 括号不平衡；(2) Validator 抓相邻 value
    // 缺运算符 / 把算式错误包进方括号 / 裸 ASCII 引用未知 code。
    // 只拦 FormulaSyntaxException；其他 RuntimeException 要冒上去让 GlobalExceptionHandler 处理，不吞。
    String raw = item.getFormulaExpr();
    if (original) {
      try {
        com.sanhua.marketingcost.formula.registry.ExpressionEvaluator.validate(raw);
        dto.setFormulaValid(true);
      } catch (IllegalArgumentException error) {
        dto.setFormulaValid(false);
        dto.setFormulaError(error.getMessage());
      }
    } else if (!StringUtils.hasText(raw)) {
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
    dto.setSourceUploadBatchId(item.getSourceUploadBatchId());
    dto.setSourceSheetName(item.getSourceSheetName());
    dto.setSourceRowNumber(item.getSourceRowNumber());
    dto.setSourceFormulaCellRef(item.getSourceFormulaCellRef());
    dto.setSourceFormulaExpr(item.getSourceFormulaExpr());
    dto.setSourceInputSnapshotJson(item.getSourceInputSnapshotJson());
    dto.setSourceTaxIncludedPrice(item.getSourceTaxIncludedPrice());
    dto.setSourceTaxExcludedPrice(item.getSourceTaxExcludedPrice());
    dto.setUpdatedAt(item.getUpdatedAt());
    return dto;
  }

  private void logFormulaChangeIfNeeded(
      PriceLinkedItem item,
      PriceLinkedItemUpdateRequest request,
      String oldFormulaExpr,
      String oldFormulaExprCn) {
    if (formulaChangeLogMapper == null
        || item == null
        || request == null
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
      PriceLinkedItem item, boolean created, boolean updated, boolean skipped) {}
}
