package com.sanhua.marketingcost.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.PriceItemExcelImportRow;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceItemImportResponse.ErrorRow;
import com.sanhua.marketingcost.dto.PriceLinkedItemDto;
import com.sanhua.marketingcost.dto.PriceLinkedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceLinkedItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.formula.normalize.FormulaDisplayRenderer;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.FormulaSyntaxException;
import com.sanhua.marketingcost.formula.normalize.FormulaValidator;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.PriceLinkedItemService;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private final PriceLinkedItemMapper itemMapper;
  private final PriceFixedItemMapper fixedItemMapper;
  private final FormulaNormalizer formulaNormalizer;
  /** Plan B T6：formula_expr_cn 由 renderer 派生，DTO 组装时反向映射 [code] → 中文 */
  private final FormulaDisplayRenderer formulaDisplayRenderer;
  /** 方案 A 加严：Normalizer 之外再跑 Validator，抓相邻 value 缺运算符、未知 code 等结构错 */
  private final FormulaValidator formulaValidator;

  public PriceLinkedItemServiceImpl(
      PriceLinkedItemMapper itemMapper,
      PriceFixedItemMapper fixedItemMapper,
      FormulaNormalizer formulaNormalizer,
      FormulaDisplayRenderer formulaDisplayRenderer,
      FormulaValidator formulaValidator) {
    this.itemMapper = itemMapper;
    this.fixedItemMapper = fixedItemMapper;
    this.formulaNormalizer = formulaNormalizer;
    this.formulaDisplayRenderer = formulaDisplayRenderer;
    this.formulaValidator = formulaValidator;
  }

  @Override
  public List<PriceLinkedItemDto> list(String pricingMonth, String materialCode) {
    String resolvedMonth = resolvePricingMonth(pricingMonth);
    var query = Wrappers.lambdaQuery(PriceLinkedItem.class);
    if (StringUtils.hasText(resolvedMonth)) {
      query.eq(PriceLinkedItem::getPricingMonth, resolvedMonth);
    }
    if (StringUtils.hasText(materialCode)) {
      query.like(PriceLinkedItem::getMaterialCode, materialCode.trim());
    }
    query.orderByAsc(PriceLinkedItem::getId);
    return itemMapper.selectList(query).stream()
        .map(this::toDto)
        .toList();
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
    merge(item, request);
    itemMapper.updateById(item);
    return toDto(item);
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
  @Transactional(rollbackFor = Exception.class)
  public PriceItemImportResponse importExcel(InputStream input, String pricingMonth) {
    PriceItemImportResponse response = new PriceItemImportResponse();
    response.setBatchId(UUID.randomUUID().toString());
    if (!StringUtils.hasText(pricingMonth)) {
      response.getErrors().add(new ErrorRow(null, null, null, "pricingMonth 必填"));
      response.setSkipped(1);
      return response;
    }
    if (input == null) {
      response.getErrors().add(new ErrorRow(null, null, null, "Excel 流为空"));
      response.setSkipped(1);
      return response;
    }
    String month = pricingMonth.trim();
    List<PriceItemExcelImportRow> rows = new ArrayList<>();
    List<ErrorRow> parseErrors = new ArrayList<>();
    EasyExcel.read(input, PriceItemExcelImportRow.class,
            new CollectingListener(rows, parseErrors))
        .sheet()
        .headRowNumber(HEADER_ROW_NUMBER)
        .doRead();

    for (int idx = 0; idx < rows.size(); idx++) {
      PriceItemExcelImportRow row = rows.get(idx);
      int excelRow = EXCEL_ROW_OFFSET + idx;
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
        // 联动分支：公式不合法则跳过，不入库
        String normalizedFormula = tryNormalize(row.getFormulaExpr());
        if (normalizedFormula == null) {
          response.getErrors().add(new ErrorRow(
              excelRow, row.getMaterialCode(), row.getOrderType(),
              "联动公式非法或无法解析: " + row.getFormulaExpr()));
          response.setSkipped(response.getSkipped() + 1);
          continue;
        }
        upsertLinked(row, month, normalizedFormula);
        response.setLinkedCount(response.getLinkedCount() + 1);
      }
    }

    if (!parseErrors.isEmpty()) {
      response.getErrors().addAll(parseErrors);
      response.setSkipped(response.getSkipped() + parseErrors.size());
    }
    return response;
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

  private void upsertLinked(PriceItemExcelImportRow row, String pricingMonth,
      String normalizedFormula) {
    PriceLinkedItem existing = findExistingLinked(pricingMonth, row);
    PriceLinkedItem item = existing != null ? existing : new PriceLinkedItem();
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
    item.setEffectiveFrom(row.getEffectiveFrom());
    item.setEffectiveTo(row.getEffectiveTo());
    // 保底填 "联动"，方便后续按 orderType 过滤
    item.setOrderType(StringUtils.hasText(row.getOrderType()) ? row.getOrderType().trim() : "联动");
    // 写入前显式注入当前登录账号的 BU，和 importItems 走同一路径，避免 NULL 行被 selectList 过滤掉
    applyCurrentBusinessUnit(item);
    if (existing == null) {
      itemMapper.insert(item);
    } else {
      itemMapper.updateById(item);
    }
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

  private PriceLinkedItem findExistingLinked(String pricingMonth, PriceItemExcelImportRow row) {
    var query = Wrappers.lambdaQuery(PriceLinkedItem.class)
        .eq(PriceLinkedItem::getPricingMonth, pricingMonth)
        .eq(PriceLinkedItem::getMaterialCode, trim(row.getMaterialCode()));
    if (StringUtils.hasText(row.getSupplierCode())) {
      query.eq(PriceLinkedItem::getSupplierCode, row.getSupplierCode().trim());
    }
    if (StringUtils.hasText(row.getSpecModel())) {
      query.eq(PriceLinkedItem::getSpecModel, row.getSpecModel().trim());
    }
    return itemMapper.selectOne(query.last("LIMIT 1"));
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

  /**
   * EasyExcel 监听器 —— 行装进 rows，解析异常记入 errors 不中断导入。
   *
   * <p>设计同 {@code FinanceBasePriceImportServiceImpl.CollectingListener}：独立静态类便于测试
   * 时直接喂已解析的 List 跳过 IO。
   */
  private static final class CollectingListener
      extends AnalysisEventListener<PriceItemExcelImportRow> {

    private final List<PriceItemExcelImportRow> sink;
    private final List<ErrorRow> errors;

    CollectingListener(List<PriceItemExcelImportRow> sink, List<ErrorRow> errors) {
      this.sink = sink;
      this.errors = errors;
    }

    @Override
    public void invoke(PriceItemExcelImportRow data, AnalysisContext context) {
      sink.add(data);
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
}
