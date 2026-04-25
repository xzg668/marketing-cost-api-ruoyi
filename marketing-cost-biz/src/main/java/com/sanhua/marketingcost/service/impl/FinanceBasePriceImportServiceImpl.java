package com.sanhua.marketingcost.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.InfluenceFactorImportResponse;
import com.sanhua.marketingcost.dto.InfluenceFactorImportResponse.ErrorRow;
import com.sanhua.marketingcost.dto.InfluenceFactorImportRow;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.formula.normalize.VariableAliasIndex;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.FinanceBasePriceImportService;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 影响因素 Excel 导入实现 —— EasyExcel 解析 + upsert + 别名刷新。
 *
 * <p>核心流程：
 * <ol>
 *   <li>{@link #importExcel} 用 EasyExcel 按表头名把每行装进 {@link InfluenceFactorImportRow}</li>
 *   <li>交给 {@link #importRows} 统一处理校验、upsert（按 {@code priceMonth+shortName+priceSource} 定位）、批次 ID</li>
 *   <li>成功后 try-catch 调 {@code VariableAliasIndex.refresh()}，别名冲突只打警告不失败</li>
 * </ol>
 *
 * <p>upsert 规则（priceMonth/shortName/priceSource 三者共同定位一条记录）：
 * <ul>
 *   <li>命中已存在行 → 覆盖 price/priceOriginal/factorName/unit + 写新 batchId</li>
 *   <li>未命中 → insert 一条新行</li>
 * </ul>
 * 为什么不用 factor_code 做主键：Excel 自身没这列，resolveFactorCode 的启发式覆盖率低。
 */
@Service
public class FinanceBasePriceImportServiceImpl implements FinanceBasePriceImportService {

  private static final Logger log = LoggerFactory.getLogger(FinanceBasePriceImportServiceImpl.class);

  /** Excel 的第 1 行是标题单元（"XX年X月参照基准"），第 2 行才是真表头。 */
  private static final int HEADER_ROW_NUMBER = 2;

  /** 1-based 的真实 Excel 行号：EasyExcel 回调的 rowIndex 是 0-based，数据第 1 行对应 Excel 第 3 行。 */
  private static final int EXCEL_ROW_OFFSET = HEADER_ROW_NUMBER + 1;

  private final FinanceBasePriceMapper financeBasePriceMapper;
  private final VariableAliasIndex variableAliasIndex;

  public FinanceBasePriceImportServiceImpl(
      FinanceBasePriceMapper financeBasePriceMapper,
      VariableAliasIndex variableAliasIndex) {
    this.financeBasePriceMapper = financeBasePriceMapper;
    this.variableAliasIndex = variableAliasIndex;
  }

  @Override
  public InfluenceFactorImportResponse importExcel(InputStream input, String priceMonth) {
    if (input == null) {
      InfluenceFactorImportResponse empty = new InfluenceFactorImportResponse();
      empty.getErrors().add(new ErrorRow(null, null, "Excel 流为空"));
      empty.setSkipped(1);
      return empty;
    }
    List<InfluenceFactorImportRow> rows = new ArrayList<>();
    List<ErrorRow> parseErrors = new ArrayList<>();
    EasyExcel.read(input, InfluenceFactorImportRow.class,
            new CollectingListener(rows, parseErrors))
        .sheet()
        .headRowNumber(HEADER_ROW_NUMBER)
        .doRead();
    InfluenceFactorImportResponse response = importRows(rows, priceMonth);
    // 把 EasyExcel 阶段的解析错误并入最终响应
    if (!parseErrors.isEmpty()) {
      response.getErrors().addAll(parseErrors);
      response.setSkipped(response.getSkipped() + parseErrors.size());
    }
    return response;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public InfluenceFactorImportResponse importRows(
      List<InfluenceFactorImportRow> rows, String priceMonth) {
    InfluenceFactorImportResponse response = new InfluenceFactorImportResponse();
    String batchId = UUID.randomUUID().toString();
    response.setBatchId(batchId);

    if (!StringUtils.hasText(priceMonth)) {
      response.getErrors().add(new ErrorRow(null, null, "priceMonth 必填"));
      response.setSkipped(1);
      return response;
    }
    if (rows == null || rows.isEmpty()) {
      return response;
    }

    String month = priceMonth.trim();
    int imported = 0;
    int skipped = 0;
    for (int idx = 0; idx < rows.size(); idx++) {
      InfluenceFactorImportRow row = rows.get(idx);
      int excelRow = EXCEL_ROW_OFFSET + idx;
      String validateError = validate(row);
      if (validateError != null) {
        response.getErrors().add(new ErrorRow(
            excelRow, row == null ? null : row.getShortName(), validateError));
        skipped++;
        continue;
      }
      upsertRow(row, month, batchId);
      imported++;
    }
    response.setImported(imported);
    response.setSkipped(skipped);

    // 别名索引刷新：财务因素新增可能带来新别名；失败不回滚本次导入。
    try {
      variableAliasIndex.refresh();
    } catch (RuntimeException e) {
      log.warn("别名索引刷新失败（导入本身已成功）: {}", e.getMessage());
    }
    return response;
  }

  /** 按 (priceMonth, shortName, priceSource) 定位已存在行：命中 update，否则 insert。 */
  private void upsertRow(InfluenceFactorImportRow row, String priceMonth, String batchId) {
    String shortName = row.getShortName().trim();
    String priceSource = StringUtils.hasText(row.getPriceSource())
        ? row.getPriceSource().trim() : "未指定";

    FinanceBasePrice existing = financeBasePriceMapper.selectOne(
        Wrappers.lambdaQuery(FinanceBasePrice.class)
            .eq(FinanceBasePrice::getPriceMonth, priceMonth)
            .eq(FinanceBasePrice::getShortName, shortName)
            .eq(FinanceBasePrice::getPriceSource, priceSource)
            .last("LIMIT 1"));

    FinanceBasePrice entity = existing != null ? existing : new FinanceBasePrice();
    entity.setPriceMonth(priceMonth);
    entity.setSeq(row.getSeq());
    entity.setFactorName(row.getFactorName());
    entity.setShortName(shortName);
    entity.setPriceSource(priceSource);
    entity.setPrice(row.getPrice());
    entity.setPriceOriginal(row.getPriceOriginal());
    entity.setUnit(StringUtils.hasText(row.getUnit()) ? row.getUnit().trim() : "公斤");
    if (!StringUtils.hasText(entity.getLinkType())) {
      // 影响因素 10 默认是固定价；联动类因素由前端单独维护
      entity.setLinkType("固定");
    }
    entity.setImportBatchId(batchId);
    // 写入边界显式注入当前登录账号的 BU：MetaObjectHandler 在部分调用链下拿不到
    // SecurityContext，导致老批次落成 NULL 行被 BusinessUnitInterceptor 过滤看不见。
    String buType = BusinessUnitContext.getCurrentBusinessUnitType();
    if (StringUtils.hasText(buType)) {
      entity.setBusinessUnitType(buType);
    }

    if (existing == null) {
      financeBasePriceMapper.insert(entity);
    } else {
      financeBasePriceMapper.updateById(entity);
    }
  }

  /** 必填字段校验；返回 null 表示通过，否则返回错误文案。 */
  private String validate(InfluenceFactorImportRow row) {
    if (row == null) {
      return "行为空";
    }
    if (!StringUtils.hasText(row.getShortName())) {
      return "简称为空，无法定位因素";
    }
    if (row.getPrice() == null) {
      return "价格为空";
    }
    if (row.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
      return "价格必须 > 0";
    }
    return null;
  }

  /**
   * EasyExcel 监听器：把每一行解析结果塞进 rows；解析异常塞进 parseErrors。
   *
   * <p>为什么独立成类：EasyExcel 要求监听器可以被它反射构造或作为参数，
   * 写成匿名内部类也能用，拆开是为了测试时可以直接喂 List 跳过 IO。
   */
  private static final class CollectingListener
      extends AnalysisEventListener<InfluenceFactorImportRow> {

    private final List<InfluenceFactorImportRow> sink;
    private final List<ErrorRow> errors;

    CollectingListener(List<InfluenceFactorImportRow> sink, List<ErrorRow> errors) {
      this.sink = sink;
      this.errors = errors;
    }

    @Override
    public void invoke(InfluenceFactorImportRow data, AnalysisContext context) {
      sink.add(data);
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
      // no-op：收集完成交给外层 Service
    }

    @Override
    public void onException(Exception exception, AnalysisContext context) {
      Integer rowIndex = context == null || context.readRowHolder() == null
          ? null
          : context.readRowHolder().getRowIndex() + 1; // 1-based
      errors.add(new ErrorRow(rowIndex, null,
          "Excel 解析失败: " + exception.getMessage()));
    }
  }
}
