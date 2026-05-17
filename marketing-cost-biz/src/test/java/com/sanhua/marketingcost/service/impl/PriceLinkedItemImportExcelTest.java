package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.BindingCandidate;
import com.sanhua.marketingcost.dto.BindingCandidateBuildResult;
import com.sanhua.marketingcost.dto.FactorUploadBatchCreateRequest;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.FactorRowParseResult;
import com.sanhua.marketingcost.dto.FactorRowRefSaveResult;
import com.sanhua.marketingcost.dto.FactorSheetParseResult;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.dto.FormulaFactorRef;
import com.sanhua.marketingcost.dto.LinkedFormulaRow;
import com.sanhua.marketingcost.dto.LinkedFormulaSheetParseResult;
import com.sanhua.marketingcost.dto.LinkedFormulaWorkbookParseResult;
import com.sanhua.marketingcost.dto.PriceLinkedAutoBindingWriteRequest;
import com.sanhua.marketingcost.dto.PriceLinkedAutoBindingWriteResult;
import com.sanhua.marketingcost.dto.ResolvedFactorRef;
import com.sanhua.marketingcost.dto.StandardBindingDecision;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.normalize.FormulaDisplayRenderer;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.FormulaSyntaxException;
import com.sanhua.marketingcost.formula.normalize.FormulaValidator;
import com.sanhua.marketingcost.formula.normalize.VariableAliasIndex;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.service.FactorMonthlyPriceUpsertService;
import com.sanhua.marketingcost.service.FactorUploadBatchService;
import com.sanhua.marketingcost.service.PriceLinkedAutoBindingWriteService;
import com.sanhua.marketingcost.service.PriceLinkedBindingCandidateBuilder;
import com.sanhua.marketingcost.service.PriceLinkedFactorWorkbookParser;
import com.sanhua.marketingcost.service.PriceLinkedFormulaFactorRefParser;
import com.sanhua.marketingcost.service.PriceLinkedFormulaFactorRefResolver;
import com.sanhua.marketingcost.service.PriceLinkedFormulaWorkbookParser;
import com.sanhua.marketingcost.service.PriceLinkedStandardBindingService;
import com.sanhua.marketingcost.service.PriceVariableBindingService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code PriceLinkedItemServiceImpl.importExcel} 单测 —— T18 核心路径。
 *
 * <p>覆盖：
 * <ol>
 *   <li>正常路径：2 行联动 + 1 行固定 → 分别走 linked/fixed 表</li>
 *   <li>含非法公式行：该行记入 errors 不入库，其余正常</li>
 *   <li>物料代码为空：skip 不触达任何 Mapper</li>
 * </ol>
 *
 * <p>MP LambdaQuery 依赖 TableInfo 缓存，故 {@code @BeforeAll} 手工预热两个实体。
 */
class PriceLinkedItemImportExcelTest {

  private PriceLinkedItemMapper itemMapper;
  private PriceFixedItemMapper fixedItemMapper;
  private PriceVariableMapper priceVariableMapper;
  private FormulaNormalizer formulaNormalizer;
  private PriceLinkedItemServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceLinkedItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceFixedItem.class);
  }

  @BeforeEach
  void setUp() {
    itemMapper = mock(PriceLinkedItemMapper.class);
    fixedItemMapper = mock(PriceFixedItemMapper.class);
    priceVariableMapper = mock(PriceVariableMapper.class);
    // 用 stub 的 Normalizer 避开真实 VariableAliasIndex 依赖：非空即通过，空串抛语法异常
    formulaNormalizer = new FormulaNormalizer(
        mock(VariableAliasIndex.class), mock(RowLocalPlaceholderRegistry.class)) {
      @Override
      public String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
          return "";
        }
        if (raw.contains("!!INVALID!!")) {
          throw new FormulaSyntaxException("mocked invalid formula");
        }
        return "[NORMALIZED]" + raw;
      }
    };
    // Plan B T6：toDto 需要 renderer；import 路径只走写表，renderer 读路径不触发
    FormulaDisplayRenderer renderer = new FormulaDisplayRenderer(
        mock(PriceVariableMapper.class), mock(RowLocalPlaceholderRegistry.class));
    // 方案 A 加严：toDto 需要 validator；import 路径不触发，此处给个空白名单 mock 即可
    PriceVariableMapper validatorMapper = mock(PriceVariableMapper.class);
    when(validatorMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of());
    FormulaValidator validator = new FormulaValidator(
        validatorMapper, mock(RowLocalPlaceholderRegistry.class));
    validator.init();
    service = new PriceLinkedItemServiceImpl(
        itemMapper,
        fixedItemMapper,
        mock(FinanceBasePriceMapper.class),
        priceVariableMapper,
        mock(PriceVariableBindingService.class),
        mock(FactorVariableRegistryImpl.class),
        formulaNormalizer,
        renderer,
        validator);
  }

  @Test
  @DisplayName("importExcel：联动行 + 固定行 按 orderType 分流")
  void importExcel_routesByOrderType() throws Exception {
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(fixedItemMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    byte[] xlsx = buildXlsx(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "电解铜+加工费", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"},
        {"210", "供管处", "供应商B", "S002", "部品联动", "物料B", "M002", "SPEC-B", "千克",
            "Ag*0.012+Cu*0.5", 0.0, 0.0, 39.0, null, 345.1, "0", null, null, "联动"},
        {null, null, "SUS304板", null, null, "废不锈钢", "B", null, null,
            null, null, null, null, null, 7.0, "0", null, null, "固定"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-02", false);

    assertThat(resp.getBatchId()).isNotBlank();
    assertThat(resp.getLinkedCount()).isEqualTo(2);
    assertThat(resp.getFixedCount()).isEqualTo(1);
    assertThat(resp.getSkipped()).isZero();
    assertThat(resp.getErrors()).isEmpty();
    verify(itemMapper, times(2)).insert(any(PriceLinkedItem.class));
    verify(fixedItemMapper).insert(any(PriceFixedItem.class));
  }

  @Test
  @DisplayName("importExcel：联动行含非法公式 → 行 skip，其他正常")
  void importExcel_invalidFormulaRowSkipped() throws Exception {
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    byte[] xlsx = buildXlsx(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "电解铜+加工费", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"},
        {"210", "供管处", "供应商B", "S002", "部品联动", "物料B", "M002", "SPEC-B", "千克",
            "!!INVALID!!", 0.0, 0.0, 10.0, null, 50.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-02", false);

    assertThat(resp.getLinkedCount()).isEqualTo(1);
    assertThat(resp.getSkipped()).isEqualTo(1);
    assertThat(resp.getErrors()).hasSize(1);
    assertThat(resp.getErrors().get(0).getMaterialCode()).isEqualTo("M002");
    assertThat(resp.getErrors().get(0).getMessage()).contains("公式非法");
    verify(itemMapper, times(1)).insert(any(PriceLinkedItem.class));
    verify(fixedItemMapper, never()).insert(any(PriceFixedItem.class));
  }

  @Test
  @DisplayName("importExcel：影响因素 sheet 在前时，仍自动定位联动价 sheet 导入")
  void importExcel_factorSheetFirstStillReadsLinkedSheet() throws Exception {
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    byte[] xlsx = buildXlsxWithFactorSheetFirst(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "电解铜+加工费", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false);

    assertThat(resp.getLinkedCount()).isEqualTo(1);
    assertThat(resp.getSkipped()).isZero();
    verify(itemMapper).insert(any(PriceLinkedItem.class));
  }

  @Test
  @DisplayName("importExcel：包含影响因素和联动公式时串起 V2 自动绑定链路")
  void importExcel_withFactorSheetUsesV2AutoBindingPipeline() throws Exception {
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    doAnswer(invocation -> {
      PriceLinkedItem item = invocation.getArgument(0);
      item.setId(88L);
      return 1;
    }).when(itemMapper).insert(any(PriceLinkedItem.class));

    PriceLinkedFactorWorkbookParser factorParser = mock(PriceLinkedFactorWorkbookParser.class);
    FactorUploadBatchService batchService = mock(FactorUploadBatchService.class);
    FactorMonthlyPriceUpsertService monthlyPriceService = mock(FactorMonthlyPriceUpsertService.class);
    PriceLinkedFormulaWorkbookParser formulaWorkbookParser = mock(PriceLinkedFormulaWorkbookParser.class);
    PriceLinkedFormulaFactorRefParser refParser = mock(PriceLinkedFormulaFactorRefParser.class);
    PriceLinkedFormulaFactorRefResolver refResolver = mock(PriceLinkedFormulaFactorRefResolver.class);
    PriceLinkedBindingCandidateBuilder candidateBuilder = mock(PriceLinkedBindingCandidateBuilder.class);
    PriceLinkedStandardBindingService standardService = mock(PriceLinkedStandardBindingService.class);
    PriceLinkedAutoBindingWriteService writeService = mock(PriceLinkedAutoBindingWriteService.class);

    FactorWorkbookParseResult factorWorkbook = factorWorkbook(rowResultSource());
    when(factorParser.parse(any(), any())).thenReturn(factorWorkbook);
    FactorUploadBatch batch = new FactorUploadBatch();
    batch.setId(77L);
    when(batchService.createFactorBatch(any())).thenReturn(batch);
    when(monthlyPriceService.upsert(any(), any(), any(), any(), any(), any()))
        .thenReturn(upsertResult());
    FactorRowRefSaveResult rowRefSaveResult = new FactorRowRefSaveResult();
    rowRefSaveResult.setFactorUploadBatchId(77L);
    when(batchService.saveRowRefs(any(), any(), any())).thenReturn(rowRefSaveResult);
    when(formulaWorkbookParser.parse(any(), any())).thenReturn(formulaWorkbook());
    when(refParser.parse(any())).thenReturn(java.util.List.of(formulaRef()));
    when(refResolver.resolve(any(), any())).thenReturn(java.util.List.of(resolvedRef()));
    when(candidateBuilder.build(any(), any(), any(), any())).thenReturn(candidateResult());
    when(standardService.checkAndRecord(any())).thenReturn(java.util.List.of(decision()));
    PriceLinkedAutoBindingWriteResult writeResult = new PriceLinkedAutoBindingWriteResult();
    writeResult.addWritten("材料含税价格", 9001L);
    when(writeService.write(any())).thenReturn(writeResult);

    injectV2Service("factorWorkbookParser", factorParser);
    injectV2Service("factorUploadBatchService", batchService);
    injectV2Service("factorMonthlyPriceUpsertService", monthlyPriceService);
    injectV2Service("formulaWorkbookParser", formulaWorkbookParser);
    injectV2Service("formulaFactorRefParser", refParser);
    injectV2Service("formulaFactorRefResolver", refResolver);
    injectV2Service("bindingCandidateBuilder", candidateBuilder);
    injectV2Service("standardBindingService", standardService);
    injectV2Service("autoBindingWriteService", writeService);
    injectV2Service("importResultClassifier", new PriceLinkedImportResultClassifierImpl());

    byte[] xlsx = buildXlsxWithFactorSheetFirst(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "下料重量*材料含税价格", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false, "COMMERCIAL", "monthly.xlsx");

    assertThat(resp.getFactorUploadBatchId()).isEqualTo(77L);
    assertThat(resp.getFactorRecognizedCount()).isEqualTo(1);
    assertThat(resp.getFactorRows()).hasSize(1);
    assertThat(resp.getFactorRows().getFirst().getFactorSeqNo()).isEqualTo("64");
    assertThat(resp.getFactorRows().getFirst().getFactorName()).isEqualTo("SUS304全名");
    assertThat(resp.getFactorRows().getFirst().getShortName()).isEqualTo("SUS304/2Bδ0.6-900");
    assertThat(resp.getFactorRows().getFirst().getPriceSource()).isEqualTo("出厂价");
    assertThat(resp.getFactorRows().getFirst().getUnit()).isEqualTo("公斤");
    assertThat(resp.getMonthlyPriceCreatedCount()).isEqualTo(1);
    assertThat(resp.getNewHistoryBindingCount()).isEqualTo(1);
    assertThat(resp.getAutoBindingCount()).isEqualTo(1);
    assertThat(resp.getLinkedCount()).isEqualTo(1);
    verify(batchService).saveRowRefs(any(), any(), any());
    verify(standardService).checkAndRecord(any());
    verify(writeService).write(any());
  }

  @Test
  @DisplayName("importExcel：中文联动公式列非法时，优先使用单价原始公式反推的标准公式入库")
  void importExcel_prefersExcelDerivedFormulaWhenDisplayFormulaInvalid() throws Exception {
    AtomicReference<PriceLinkedItem> inserted = new AtomicReference<>();
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    doAnswer(invocation -> {
      PriceLinkedItem item = invocation.getArgument(0);
      item.setId(89L);
      inserted.set(item);
      return 1;
    }).when(itemMapper).insert(any(PriceLinkedItem.class));

    PriceLinkedFactorWorkbookParser factorParser = mock(PriceLinkedFactorWorkbookParser.class);
    FactorUploadBatchService batchService = mock(FactorUploadBatchService.class);
    FactorMonthlyPriceUpsertService monthlyPriceService = mock(FactorMonthlyPriceUpsertService.class);
    PriceLinkedFormulaWorkbookParser formulaWorkbookParser = mock(PriceLinkedFormulaWorkbookParser.class);
    PriceLinkedFormulaFactorRefParser refParser = mock(PriceLinkedFormulaFactorRefParser.class);
    PriceLinkedFormulaFactorRefResolver refResolver = mock(PriceLinkedFormulaFactorRefResolver.class);
    PriceLinkedBindingCandidateBuilder candidateBuilder = mock(PriceLinkedBindingCandidateBuilder.class);
    PriceLinkedStandardBindingService standardService = mock(PriceLinkedStandardBindingService.class);
    PriceLinkedAutoBindingWriteService writeService = mock(PriceLinkedAutoBindingWriteService.class);

    when(factorParser.parse(any(), any())).thenReturn(factorWorkbook(rowResultSource()));
    FactorUploadBatch batch = new FactorUploadBatch();
    batch.setId(77L);
    when(batchService.createFactorBatch(any())).thenReturn(batch);
    when(monthlyPriceService.upsert(any(), any(), any(), any(), any(), any()))
        .thenReturn(upsertResult());
    FactorRowRefSaveResult rowRefSaveResult = new FactorRowRefSaveResult();
    rowRefSaveResult.setFactorUploadBatchId(77L);
    when(batchService.saveRowRefs(any(), any(), any())).thenReturn(rowRefSaveResult);
    when(formulaWorkbookParser.parse(any(), any())).thenReturn(formulaWorkbookWithDerived());
    when(refParser.parse(any())).thenReturn(java.util.List.of(formulaRef()));
    when(refResolver.resolve(any(), any())).thenReturn(java.util.List.of(resolvedRef()));
    when(candidateBuilder.build(any(), any(), any(), any())).thenReturn(candidateResult());
    when(standardService.checkAndRecord(any())).thenReturn(java.util.List.of(decision()));
    PriceLinkedAutoBindingWriteResult writeResult = new PriceLinkedAutoBindingWriteResult();
    writeResult.addWritten("材料含税价格", 9001L);
    when(writeService.write(any())).thenReturn(writeResult);

    injectV2Service("factorWorkbookParser", factorParser);
    injectV2Service("factorUploadBatchService", batchService);
    injectV2Service("factorMonthlyPriceUpsertService", monthlyPriceService);
    injectV2Service("formulaWorkbookParser", formulaWorkbookParser);
    injectV2Service("formulaFactorRefParser", refParser);
    injectV2Service("formulaFactorRefResolver", refResolver);
    injectV2Service("bindingCandidateBuilder", candidateBuilder);
    injectV2Service("standardBindingService", standardService);
    injectV2Service("autoBindingWriteService", writeService);
    injectV2Service("importResultClassifier", new PriceLinkedImportResultClassifierImpl());

    byte[] xlsx = buildXlsxWithFactorSheetFirst(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "!!INVALID!!", 30.0, 20.0, 12.8, null, 91.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false, "COMMERCIAL", "monthly.xlsx");

    assertThat(resp.getLinkedCount()).isEqualTo(1);
    assertThat(resp.getSkipped()).isZero();
    assertThat(inserted.get().getFormulaExpr())
        .isEqualTo("[NORMALIZED][blank_weight]*[factor_identity_1001]+[process_fee]");
    assertThat(inserted.get().getManualPrice()).isEqualByComparingTo("88.88");
    ArgumentCaptor<PriceVariable> variableCaptor = ArgumentCaptor.forClass(PriceVariable.class);
    verify(priceVariableMapper).insert(variableCaptor.capture());
    assertThat(variableCaptor.getValue().getVariableCode()).isEqualTo("factor_identity_1001");
    assertThat(variableCaptor.getValue().getAliasesJson())
        .isEqualTo("[\"SUS304/2Bδ0.6-900\",\"64#SUS304/2Bδ0.6-900\",\"factor_identity_1001\"]");
  }

  @Test
  @DisplayName("importExcel：Excel 原始公式尾部 /1.13 转成 taxIncluded=0，避免系统二次除税")
  void importExcel_movesFinalVatDivisorToTaxIncludedFlag() throws Exception {
    AtomicReference<PriceLinkedItem> inserted = new AtomicReference<>();
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    doAnswer(invocation -> {
      PriceLinkedItem item = invocation.getArgument(0);
      item.setId(90L);
      inserted.set(item);
      return 1;
    }).when(itemMapper).insert(any(PriceLinkedItem.class));

    PriceLinkedFactorWorkbookParser factorParser = mock(PriceLinkedFactorWorkbookParser.class);
    FactorUploadBatchService batchService = mock(FactorUploadBatchService.class);
    FactorMonthlyPriceUpsertService monthlyPriceService = mock(FactorMonthlyPriceUpsertService.class);
    PriceLinkedFormulaWorkbookParser formulaWorkbookParser = mock(PriceLinkedFormulaWorkbookParser.class);
    PriceLinkedFormulaFactorRefParser refParser = mock(PriceLinkedFormulaFactorRefParser.class);
    PriceLinkedFormulaFactorRefResolver refResolver = mock(PriceLinkedFormulaFactorRefResolver.class);
    PriceLinkedBindingCandidateBuilder candidateBuilder = mock(PriceLinkedBindingCandidateBuilder.class);
    PriceLinkedStandardBindingService standardService = mock(PriceLinkedStandardBindingService.class);
    PriceLinkedAutoBindingWriteService writeService = mock(PriceLinkedAutoBindingWriteService.class);

    when(factorParser.parse(any(), any())).thenReturn(factorWorkbook(rowResultSource()));
    FactorUploadBatch batch = new FactorUploadBatch();
    batch.setId(77L);
    when(batchService.createFactorBatch(any())).thenReturn(batch);
    when(monthlyPriceService.upsert(any(), any(), any(), any(), any(), any()))
        .thenReturn(upsertResult());
    FactorRowRefSaveResult rowRefSaveResult = new FactorRowRefSaveResult();
    rowRefSaveResult.setFactorUploadBatchId(77L);
    when(batchService.saveRowRefs(any(), any(), any())).thenReturn(rowRefSaveResult);
    when(formulaWorkbookParser.parse(any(), any())).thenReturn(formulaWorkbookWithFinalVatDivisor());
    when(refParser.parse(any())).thenReturn(java.util.List.of(formulaRef()));
    when(refResolver.resolve(any(), any())).thenReturn(java.util.List.of(resolvedRef()));
    when(candidateBuilder.build(any(), any(), any(), any())).thenReturn(candidateResult());
    when(standardService.checkAndRecord(any())).thenReturn(java.util.List.of(decision()));
    PriceLinkedAutoBindingWriteResult writeResult = new PriceLinkedAutoBindingWriteResult();
    writeResult.addWritten("材料含税价格", 9001L);
    when(writeService.write(any())).thenReturn(writeResult);

    injectV2Service("factorWorkbookParser", factorParser);
    injectV2Service("factorUploadBatchService", batchService);
    injectV2Service("factorMonthlyPriceUpsertService", monthlyPriceService);
    injectV2Service("formulaWorkbookParser", formulaWorkbookParser);
    injectV2Service("formulaFactorRefParser", refParser);
    injectV2Service("formulaFactorRefResolver", refResolver);
    injectV2Service("bindingCandidateBuilder", candidateBuilder);
    injectV2Service("standardBindingService", standardService);
    injectV2Service("autoBindingWriteService", writeService);
    injectV2Service("importResultClassifier", new PriceLinkedImportResultClassifierImpl());

    byte[] xlsx = buildXlsxWithFactorSheetFirst(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "!!INVALID!!", 30.0, 20.0, 12.8, null, 91.0, "1", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false, "COMMERCIAL", "monthly.xlsx");

    assertThat(resp.getLinkedCount()).isEqualTo(1);
    assertThat(resp.getSkipped()).isZero();
    assertThat(inserted.get().getFormulaExpr())
        .isEqualTo("[NORMALIZED]([blank_weight]*[factor_identity_1001]+[process_fee])");
    assertThat(inserted.get().getTaxIncluded()).isZero();
  }

  @Test
  @DisplayName("importExcel：灰度开关关闭时只导入行，不自动写绑定")
  void importExcel_autoBindingDisabledKeepsRowImportOnly() throws Exception {
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    PriceLinkedFactorWorkbookParser factorParser = mock(PriceLinkedFactorWorkbookParser.class);
    PriceLinkedAutoBindingWriteService writeService = mock(PriceLinkedAutoBindingWriteService.class);
    injectV2Service("factorWorkbookParser", factorParser);
    injectV2Service("autoBindingWriteService", writeService);
    service.setExcelAutoBindingEnabled(false);

    byte[] xlsx = buildXlsxWithFactorSheetFirst(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "下料重量*材料含税价格", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false, "COMMERCIAL", "monthly.xlsx");

    assertThat(resp.getLinkedCount()).isEqualTo(1);
    assertThat(resp.getAutoBindingCount()).isZero();
    assertThat(resp.getFactorUploadBatchId()).isNull();
    verify(itemMapper).insert(any(PriceLinkedItem.class));
    verify(factorParser, never()).parse(any(), any());
    verify(writeService, never()).write(any());
  }

  @Test
  @DisplayName("importExcel：APPEND_ONLY 遇到已有联动料号时跳过，不覆盖公式和绑定")
  void importExcel_appendOnlySkipsExistingLinkedItemAndBindings() throws Exception {
    PriceLinkedItem existing = existingLinkedItem();
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
    V2Mocks mocks = configureV2Pipeline();

    byte[] xlsx = buildXlsxWithFactorSheetFirst(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "下料重量*材料含税价格", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false,
        "COMMERCIAL", "monthly.xlsx", "APPEND_ONLY");

    assertThat(resp.getEffectiveStrategy()).isEqualTo("APPEND_ONLY");
    assertThat(resp.getImportPurpose()).isEqualTo("LINKED_APPEND_ONLY");
    assertThat(resp.getLinkedCount()).isZero();
    assertThat(resp.getLinkedCreatedCount()).isZero();
    assertThat(resp.getLinkedUpdatedCount()).isZero();
    assertThat(resp.getLinkedSkippedCount()).isEqualTo(1);
    verify(itemMapper, never()).insert(any(PriceLinkedItem.class));
    verify(itemMapper, never()).updateById(any(PriceLinkedItem.class));
    verify(mocks.writeService(), never()).write(any());
  }

  @Test
  @DisplayName("importExcel：APPEND_ONLY 新料号正常新增并写入自动绑定")
  void importExcel_appendOnlyCreatesNewLinkedItemAndBindings() throws Exception {
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    doAnswer(invocation -> {
      PriceLinkedItem item = invocation.getArgument(0);
      item.setId(88L);
      return 1;
    }).when(itemMapper).insert(any(PriceLinkedItem.class));
    V2Mocks mocks = configureV2Pipeline();

    byte[] xlsx = buildXlsxWithFactorSheetFirst(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "下料重量*材料含税价格", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false,
        "COMMERCIAL", "monthly.xlsx", "APPEND_ONLY");

    assertThat(resp.getLinkedCount()).isEqualTo(1);
    assertThat(resp.getLinkedCreatedCount()).isEqualTo(1);
    assertThat(resp.getLinkedUpdatedCount()).isZero();
    assertThat(resp.getLinkedSkippedCount()).isZero();
    verify(itemMapper).insert(any(PriceLinkedItem.class));
    verify(mocks.writeService()).write(any());

    ArgumentCaptor<FactorUploadBatchCreateRequest> batchCaptor =
        ArgumentCaptor.forClass(FactorUploadBatchCreateRequest.class);
    verify(mocks.batchService()).createFactorBatch(batchCaptor.capture());
    assertThat(batchCaptor.getValue().getImportPurpose()).isEqualTo("LINKED_APPEND_ONLY");
    assertThat(batchCaptor.getValue().getEffectiveStrategy()).isEqualTo("APPEND_ONLY");
    verify(mocks.monthlyPriceService()).upsert(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("importExcel：OVERRIDE_EFFECTIVE 覆盖已有料号公式并允许覆盖绑定")
  void importExcel_overrideEffectiveUpdatesExistingLinkedItemAndBindings() throws Exception {
    PriceLinkedItem existing = existingLinkedItem();
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
    V2Mocks mocks = configureV2Pipeline();

    byte[] xlsx = buildXlsxWithFactorSheetFirst(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "下料重量*材料含税价格", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false,
        "COMMERCIAL", "monthly.xlsx", "OVERRIDE_EFFECTIVE");

    assertThat(resp.getEffectiveStrategy()).isEqualTo("OVERRIDE_EFFECTIVE");
    assertThat(resp.getImportPurpose()).isEqualTo("LINKED_OVERRIDE_EFFECTIVE");
    assertThat(resp.getLinkedCount()).isEqualTo(1);
    assertThat(resp.getLinkedCreatedCount()).isZero();
    assertThat(resp.getLinkedUpdatedCount()).isEqualTo(1);
    ArgumentCaptor<PriceLinkedItem> itemCaptor = ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(itemMapper).updateById(itemCaptor.capture());
    assertThat(itemCaptor.getValue().getId()).isEqualTo(88L);
    assertThat(itemCaptor.getValue().getFormulaExpr()).contains("[NORMALIZED]");

    ArgumentCaptor<PriceLinkedAutoBindingWriteRequest> writeCaptor =
        ArgumentCaptor.forClass(PriceLinkedAutoBindingWriteRequest.class);
    verify(mocks.writeService()).write(writeCaptor.capture());
    assertThat(writeCaptor.getValue().getOverwriteManualBinding()).isTrue();

    ArgumentCaptor<FactorUploadBatchCreateRequest> batchCaptor =
        ArgumentCaptor.forClass(FactorUploadBatchCreateRequest.class);
    verify(mocks.batchService()).createFactorBatch(batchCaptor.capture());
    assertThat(batchCaptor.getValue().getImportPurpose()).isEqualTo("LINKED_OVERRIDE_EFFECTIVE");
    assertThat(batchCaptor.getValue().getEffectiveStrategy()).isEqualTo("OVERRIDE_EFFECTIVE");
  }

  @Test
  @DisplayName("importExcel：pricingMonth 为空 → 全跳过不碰任何 Mapper")
  void importExcel_missingPricingMonth_skipsAll() {
    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(new byte[]{1, 2}), null, false);

    assertThat(resp.getLinkedCount()).isZero();
    assertThat(resp.getFixedCount()).isZero();
    assertThat(resp.getSkipped()).isEqualTo(1);
    assertThat(resp.getErrors()).hasSize(1);
    verify(itemMapper, never()).insert(any(PriceLinkedItem.class));
    verify(fixedItemMapper, never()).insert(any(PriceFixedItem.class));
  }

  /**
   * 构造一个 1 行表头 + N 行数据的 xlsx —— 表头列顺序与 PriceItemExcelImportRow 19 列一致。
   * 传入数据每行 19 个对象（null 也可），按列顺序 cell。
   */
  private byte[] buildXlsx(Object[][] dataRows) throws Exception {
    try (XSSFWorkbook wb = new XSSFWorkbook();
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("原材料");
      // 表头行（顺序不必与 DTO 声明一致，EasyExcel 按表头名绑定字段）
      String[] headers = {
          "组织", "来源", "供应商名称", "供应商代码", "采购分类",
          "物料名称", "物料代码", "规格型号", "单位", "联动公式",
          "下料重", "净重", "加工费", "代理费", "单价",
          "是否含税", "生效日期", "失效日期", "订单类型"
      };
      Row header = sheet.createRow(0);
      for (int i = 0; i < headers.length; i++) {
        header.createCell(i).setCellValue(headers[i]);
      }
      for (int r = 0; r < dataRows.length; r++) {
        Row row = sheet.createRow(r + 1);
        Object[] v = dataRows[r];
        for (int c = 0; c < v.length; c++) {
          if (v[c] == null) {
            continue;
          }
          if (v[c] instanceof Number n) {
            row.createCell(c).setCellValue(n.doubleValue());
          } else {
            row.createCell(c).setCellValue(String.valueOf(v[c]));
          }
        }
      }
      wb.write(out);
      return out.toByteArray();
    }
  }

  private void injectV2Service(String fieldName, Object value) throws Exception {
    java.lang.reflect.Field field = PriceLinkedItemServiceImpl.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(service, value);
  }

  private V2Mocks configureV2Pipeline() throws Exception {
    PriceLinkedFactorWorkbookParser factorParser = mock(PriceLinkedFactorWorkbookParser.class);
    FactorUploadBatchService batchService = mock(FactorUploadBatchService.class);
    FactorMonthlyPriceUpsertService monthlyPriceService = mock(FactorMonthlyPriceUpsertService.class);
    PriceLinkedFormulaWorkbookParser formulaWorkbookParser = mock(PriceLinkedFormulaWorkbookParser.class);
    PriceLinkedFormulaFactorRefParser refParser = mock(PriceLinkedFormulaFactorRefParser.class);
    PriceLinkedFormulaFactorRefResolver refResolver = mock(PriceLinkedFormulaFactorRefResolver.class);
    PriceLinkedBindingCandidateBuilder candidateBuilder = mock(PriceLinkedBindingCandidateBuilder.class);
    PriceLinkedStandardBindingService standardService = mock(PriceLinkedStandardBindingService.class);
    PriceLinkedAutoBindingWriteService writeService = mock(PriceLinkedAutoBindingWriteService.class);

    when(factorParser.parse(any(), any())).thenReturn(factorWorkbook(rowResultSource()));
    FactorUploadBatch batch = new FactorUploadBatch();
    batch.setId(77L);
    when(batchService.createFactorBatch(any())).thenReturn(batch);
    when(monthlyPriceService.upsert(any(), any(), any(), any(), any(), any()))
        .thenReturn(upsertResult());
    FactorRowRefSaveResult rowRefSaveResult = new FactorRowRefSaveResult();
    rowRefSaveResult.setFactorUploadBatchId(77L);
    when(batchService.saveRowRefs(any(), any(), any())).thenReturn(rowRefSaveResult);
    when(formulaWorkbookParser.parse(any(), any())).thenReturn(formulaWorkbook());
    when(refParser.parse(any())).thenReturn(java.util.List.of(formulaRef()));
    when(refResolver.resolve(any(), any())).thenReturn(java.util.List.of(resolvedRef()));
    when(candidateBuilder.build(any(), any(), any(), any())).thenReturn(candidateResult());
    when(standardService.checkAndRecord(any())).thenReturn(java.util.List.of(decision()));
    PriceLinkedAutoBindingWriteResult writeResult = new PriceLinkedAutoBindingWriteResult();
    writeResult.addWritten("材料含税价格", 9001L);
    when(writeService.write(any())).thenReturn(writeResult);

    injectV2Service("factorWorkbookParser", factorParser);
    injectV2Service("factorUploadBatchService", batchService);
    injectV2Service("factorMonthlyPriceUpsertService", monthlyPriceService);
    injectV2Service("formulaWorkbookParser", formulaWorkbookParser);
    injectV2Service("formulaFactorRefParser", refParser);
    injectV2Service("formulaFactorRefResolver", refResolver);
    injectV2Service("bindingCandidateBuilder", candidateBuilder);
    injectV2Service("standardBindingService", standardService);
    injectV2Service("autoBindingWriteService", writeService);
    injectV2Service("importResultClassifier", new PriceLinkedImportResultClassifierImpl());
    return new V2Mocks(batchService, monthlyPriceService, writeService);
  }

  private PriceLinkedItem existingLinkedItem() {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setId(88L);
    item.setPricingMonth("2026-05");
    item.setMaterialCode("M001");
    item.setSupplierCode("S001");
    item.setSpecModel("SPEC-A");
    item.setFormulaExpr("[old_formula]");
    item.setOrderType("联动");
    return item;
  }

  private FactorRowParseResult rowResultSource() {
    FactorRowParseResult row = new FactorRowParseResult();
    row.setSourceSheetName("影响因素");
    row.setSourceRowNumber(64);
    row.setFactorSeqNo("64");
    row.setFactorName("SUS304全名");
    row.setShortName("SUS304/2Bδ0.6-900");
    row.setPriceSource("出厂价");
    row.setPrice(new java.math.BigDecimal("16.4"));
    row.setOriginalPrice(new java.math.BigDecimal("17.2"));
    row.setUnit("公斤");
    return row;
  }

  private FactorWorkbookParseResult factorWorkbook(FactorRowParseResult row) {
    FactorWorkbookParseResult workbook = new FactorWorkbookParseResult();
    workbook.setSourceFileName("monthly.xlsx");
    FactorSheetParseResult sheet = new FactorSheetParseResult();
    sheet.setSheetName("影响因素");
    sheet.getRows().add(row);
    workbook.getSheets().add(sheet);
    return workbook;
  }

  private FactorMonthlyPriceUpsertResult upsertResult() {
    FactorMonthlyPriceUpsertResult result = new FactorMonthlyPriceUpsertResult();
    result.setMonthlyPriceCreatedCount(1);
    FactorMonthlyPriceUpsertResult.RowResult row = new FactorMonthlyPriceUpsertResult.RowResult();
    row.setSourceSheetName("影响因素");
    row.setSourceRowNumber(64);
    row.setFactorIdentityId(1001L);
    row.setFactorMonthlyPriceId(2001L);
    row.setFactorSeqNo("64");
    row.setFactorName("SUS304全名");
    row.setShortName("SUS304/2Bδ0.6-900");
    row.setPriceSource("出厂价");
    row.setIdentityAction("CREATE");
    row.setMonthlyPriceAction("CREATE");
    row.setNewPrice(new java.math.BigDecimal("16.4"));
    row.setOriginalPrice(new java.math.BigDecimal("17.2"));
    row.setUnit("公斤");
    result.getRows().add(row);
    return result;
  }

  private LinkedFormulaWorkbookParseResult formulaWorkbook() {
    LinkedFormulaWorkbookParseResult workbook = new LinkedFormulaWorkbookParseResult();
    workbook.setSourceFileName("monthly.xlsx");
    LinkedFormulaSheetParseResult sheet = new LinkedFormulaSheetParseResult();
    sheet.setSheetName("联动公式");
    sheet.getRows().add(formulaRow());
    workbook.getSheets().add(sheet);
    return workbook;
  }

  private LinkedFormulaWorkbookParseResult formulaWorkbookWithDerived() {
    LinkedFormulaWorkbookParseResult workbook = new LinkedFormulaWorkbookParseResult();
    workbook.setSourceFileName("monthly.xlsx");
    LinkedFormulaSheetParseResult sheet = new LinkedFormulaSheetParseResult();
    sheet.setSheetName("联动公式");
    LinkedFormulaRow row = formulaRow();
    row.setFormulaText("!!INVALID!!");
    row.setPriceCellValue(new java.math.BigDecimal("88.88"));
    row.setExcelDerivedFormulaText("[blank_weight]*影响因素!$E$64/1000+[process_fee]");
    sheet.getRows().add(row);
    workbook.getSheets().add(sheet);
    return workbook;
  }

  private LinkedFormulaWorkbookParseResult formulaWorkbookWithFinalVatDivisor() {
    LinkedFormulaWorkbookParseResult workbook = new LinkedFormulaWorkbookParseResult();
    workbook.setSourceFileName("monthly.xlsx");
    LinkedFormulaSheetParseResult sheet = new LinkedFormulaSheetParseResult();
    sheet.setSheetName("联动公式");
    LinkedFormulaRow row = formulaRow();
    row.setFormulaText("!!INVALID!!");
    row.setPriceCellValue(new java.math.BigDecimal("78.65"));
    row.setExcelDerivedFormulaText("([blank_weight]*影响因素!$E$64/1000+[process_fee])/1.13");
    sheet.getRows().add(row);
    workbook.getSheets().add(sheet);
    return workbook;
  }

  private LinkedFormulaRow formulaRow() {
    LinkedFormulaRow row = new LinkedFormulaRow();
    row.setSourceSheetName("联动公式");
    row.setExcelRowNumber(2);
    row.setMaterialCode("M001");
    row.setLinkedItemImportKey("M001|S001|SPEC-A");
    row.setFormulaText("下料重量*材料含税价格");
    row.setPriceCellFormula("ROUND($I$2*影响因素!$E$64/1000,4)");
    row.setExcelDerivedFormulaText("[blank_weight]*影响因素!$E$64");
    row.setHasFormula(true);
    return row;
  }

  private FormulaFactorRef formulaRef() {
    FormulaFactorRef ref = new FormulaFactorRef();
    ref.setSheetName("影响因素");
    ref.setColumnName("E");
    ref.setRowNumber(64);
    ref.setRawRef("影响因素!$E$64");
    ref.setOrderIndex(1);
    return ref;
  }

  private ResolvedFactorRef resolvedRef() {
    ResolvedFactorRef ref = new ResolvedFactorRef();
    ref.setWorkbookName("monthly.xlsx");
    ref.setSheetName("影响因素");
    ref.setColumnName("E");
    ref.setRowNumber(64);
    ref.setRawRef("影响因素!$E$64");
    ref.setFactorIdentityId(1001L);
    ref.setFactorMonthlyPriceId(2001L);
    ref.setFactorSeqNo("64");
    ref.setShortName("SUS304/2Bδ0.6-900");
    ref.setPriceSource("出厂价");
    return ref;
  }

  private BindingCandidateBuildResult candidateResult() {
    BindingCandidateBuildResult result = new BindingCandidateBuildResult();
    BindingCandidate candidate = new BindingCandidate();
    candidate.setMaterialCode("M001");
    candidate.setLinkedItemImportKey("M001|S001|SPEC-A");
    candidate.setTokenName("材料含税价格");
    candidate.setFactorIdentityId(1001L);
    candidate.setFactorMonthlyPriceId(2001L);
    candidate.setSourceRef(resolvedRef());
    result.getCandidates().add(candidate);
    return result;
  }

  private StandardBindingDecision decision() {
    StandardBindingDecision decision = new StandardBindingDecision();
    decision.setMaterialCode("M001");
    decision.setTokenName("材料含税价格");
    decision.setAction(PriceLinkedStandardBindingServiceImpl.ACTION_CREATE_HISTORY);
    decision.setStandardBindingId(501L);
    decision.setNewFactorIdentityId(1001L);
    decision.setCandidate(candidateResult().getCandidates().getFirst());
    return decision;
  }

  private byte[] buildXlsxWithFactorSheetFirst(Object[][] dataRows) throws Exception {
    try (XSSFWorkbook wb = new XSSFWorkbook();
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet factor = wb.createSheet("影响因素");
      Row factorHeader = factor.createRow(0);
      factorHeader.createCell(0).setCellValue("序号");
      factorHeader.createCell(1).setCellValue("价表影响因素名称");
      factorHeader.createCell(2).setCellValue("简称");
      factorHeader.createCell(3).setCellValue("取价来源");
      factorHeader.createCell(4).setCellValue("价格");
      Row factorRow = factor.createRow(1);
      factorRow.createCell(0).setCellValue(64);
      factorRow.createCell(1).setCellValue("SUS304全名");
      factorRow.createCell(2).setCellValue("SUS304/2Bδ0.6-900");
      factorRow.createCell(3).setCellValue("出厂价");
      factorRow.createCell(4).setCellValue(16.4);

      Sheet linked = wb.createSheet("联动公式");
      String[] headers = {
          "组织", "来源", "供应商名称", "供应商代码", "采购分类",
          "物料名称", "物料代码", "规格型号", "单位", "联动公式",
          "下料重", "净重", "加工费", "代理费", "单价",
          "是否含税", "生效日期", "失效日期", "订单类型"
      };
      Row header = linked.createRow(0);
      for (int i = 0; i < headers.length; i++) {
        header.createCell(i).setCellValue(headers[i]);
      }
      for (int r = 0; r < dataRows.length; r++) {
        Row row = linked.createRow(r + 1);
        Object[] v = dataRows[r];
        for (int c = 0; c < v.length; c++) {
          if (v[c] == null) {
            continue;
          }
          if (v[c] instanceof Number n) {
            row.createCell(c).setCellValue(n.doubleValue());
          } else {
            row.createCell(c).setCellValue(String.valueOf(v[c]));
          }
        }
      }
      wb.write(out);
      return out.toByteArray();
    }
  }

  private record V2Mocks(
      FactorUploadBatchService batchService,
      FactorMonthlyPriceUpsertService monthlyPriceService,
      PriceLinkedAutoBindingWriteService writeService) {
  }
}
