package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
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
import com.sanhua.marketingcost.dto.PriceVariableBindingDto;
import com.sanhua.marketingcost.dto.ResolvedFactorRef;
import com.sanhua.marketingcost.dto.StandardBindingCheckRequest;
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
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assumptions;
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

  private static final Path QUASI_REAL_IMPORT_SAMPLE = Path.of(
      "/Users/xiexicheng/Documents/sales_cost/generated/月度联动价与影响因素-导入验证样例-2026-05.xlsx");

  private PriceLinkedItemMapper itemMapper;
  private PriceFixedItemMapper fixedItemMapper;
  private PriceVariableMapper priceVariableMapper;
  private PriceVariableBindingService priceVariableBindingService;
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
    priceVariableBindingService = mock(PriceVariableBindingService.class);
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
        priceVariableBindingService,
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
    assertThat(resp.getFormulaEffectiveDate()).isEqualTo("2026-02-01");
    assertThat(resp.getFactorPriceConflictStrategy()).isEqualTo("KEEP_EXISTING");
    assertThat(resp.getLinkedCount()).isEqualTo(2);
    assertThat(resp.getFixedCount()).isEqualTo(1);
    assertThat(resp.getSkipped()).isZero();
    assertThat(resp.getErrors()).isEmpty();
    ArgumentCaptor<PriceLinkedItem> linkedCaptor = ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(itemMapper, times(2)).insert(linkedCaptor.capture());
    assertThat(linkedCaptor.getAllValues())
        .extracting(PriceLinkedItem::getEffectiveFrom)
        .containsOnly(LocalDate.of(2026, 2, 1));
    assertThat(linkedCaptor.getAllValues())
        .extracting(PriceLinkedItem::getEffectiveTo)
        .containsOnlyNulls();
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
  @DisplayName("importExcel：新参数解析进响应，旧响应统计同步到版本化字段")
  void importExcel_newLifecycleParamsAreResolvedInResponse() throws Exception {
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    doAnswer(invocation -> {
      PriceLinkedItem item = invocation.getArgument(0);
      item.setId(88L);
      return 1;
    }).when(itemMapper).insert(any(PriceLinkedItem.class));
    V2Mocks mocks = configureV2Pipeline();
    FactorMonthlyPriceUpsertResult upsertResult = upsertResult();
    upsertResult.setMonthlyPriceCreatedCount(0);
    upsertResult.setMonthlyPriceUpdatedCount(2);
    upsertResult.setMonthlyPriceSkippedCount(3);
    when(mocks.monthlyPriceService().upsert(any(), any(), any(), any(), any(), any()))
        .thenReturn(upsertResult);

    byte[] xlsx = buildXlsxWithFactorSheetFirst(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "下料重量*材料含税价格", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false,
        "COMMERCIAL", "monthly.xlsx", null, "2026-04-15", "OVERWRITE");

    assertThat(resp.getFormulaEffectiveDate()).isEqualTo("2026-04-15");
    assertThat(resp.getFactorPriceConflictStrategy()).isEqualTo("OVERWRITE");
    assertThat(resp.getMonthlyPriceConflictCount()).isEqualTo(3);
    assertThat(resp.getMonthlyPriceOverwriteCount()).isEqualTo(2);
    assertThat(resp.getLinkedVersionCreatedCount()).isEqualTo(1);
    assertThat(resp.getLinkedUnchangedSkippedCount()).isZero();
    assertThat(resp.getLinkedExpiredCount()).isZero();
    ArgumentCaptor<String> factorStrategyCaptor = ArgumentCaptor.forClass(String.class);
    verify(mocks.monthlyPriceService())
        .upsert(any(), any(), any(), any(), any(), factorStrategyCaptor.capture());
    assertThat(factorStrategyCaptor.getValue()).isEqualTo("OVERWRITE");
  }

  @Test
  @DisplayName("importExcel：旧调用方式仍可用，新参数默认 pricingMonth 首日与 KEEP_EXISTING")
  void importExcel_legacyOverloadUsesNewDefaults() throws Exception {
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    byte[] xlsx = buildXlsx(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "电解铜+加工费", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false,
        "COMMERCIAL", "monthly.xlsx", "APPEND_ONLY");

    assertThat(resp.getEffectiveStrategy()).isEqualTo("APPEND_ONLY");
    assertThat(resp.getFormulaEffectiveDate()).isEqualTo("2026-05-01");
    assertThat(resp.getFactorPriceConflictStrategy()).isEqualTo("KEEP_EXISTING");
    assertThat(resp.getLinkedVersionCreatedCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("importExcel：formulaEffectiveDate 格式非法时给出明确错误")
  void importExcel_invalidFormulaEffectiveDateFailsFast() {
    assertThatThrownBy(() -> service.importExcel(
        new ByteArrayInputStream(new byte[]{1}), "2026-05", false,
        "COMMERCIAL", "monthly.xlsx", null, "2026/05/01", "KEEP_EXISTING"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("formulaEffectiveDate 格式错误");
  }

  @Test
  @DisplayName("importExcel：factorPriceConflictStrategy 非法时给出明确错误")
  void importExcel_invalidFactorConflictStrategyFailsFast() {
    assertThatThrownBy(() -> service.importExcel(
        new ByteArrayInputStream(new byte[]{1}), "2026-05", false,
        "COMMERCIAL", "monthly.xlsx", null, "2026-05-01", "BAD"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("factorPriceConflictStrategy 非法");
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
  @DisplayName("importExcel：用户 demo4 联动价 xls 导入响应保留影响因素识别条数")
  void importExcel_userDemo4LinkedWorkbookKeepsFactorRecognizedCount() throws Exception {
    Path sample = Path.of("/Users/xiexicheng/Desktop/demo4/联动价.xls");
    Assumptions.assumeTrue(Files.exists(sample), "用户 demo4 联动价 xls 不存在，跳过本地回归");
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    AtomicLong linkedId = new AtomicLong(8800L);
    List<PriceLinkedItem> insertedItems = new ArrayList<>();
    doAnswer(invocation -> {
      PriceLinkedItem item = invocation.getArgument(0);
      item.setId(linkedId.incrementAndGet());
      insertedItems.add(item);
      return 1;
    }).when(itemMapper).insert(any(PriceLinkedItem.class));
    QuasiRealV2Mocks mocks = configureQuasiRealV2Pipeline(new InMemoryLifecycleStore());

    PriceItemImportResponse resp;
    try (InputStream input = Files.newInputStream(sample)) {
      resp = service.importExcel(
          input, "2026-06", false, "COMMERCIAL", sample.getFileName().toString());
    }

    assertThat(resp.getFactorRecognizedCount()).isEqualTo(63);
    assertThat(resp.getFactorRows()).hasSize(63);
    assertThat(resp.getLinkedCount()).isEqualTo(12);
    assertThat(resp.getSkipped()).isZero();
    assertThat(resp.getErrors()).isEmpty();
    PriceLinkedItem material721250208 = insertedItems.stream()
        .filter(item -> "721250208".equals(item.getMaterialCode()))
        .findFirst()
        .orElseThrow();
    assertThat(material721250208.getFormulaExpr())
        .contains("[__material]")
        .contains("[__scrap]");

    ArgumentCaptor<PriceLinkedAutoBindingWriteRequest> writeCaptor =
        ArgumentCaptor.forClass(PriceLinkedAutoBindingWriteRequest.class);
    verify(mocks.writeService(), times(resp.getLinkedCount())).write(writeCaptor.capture());
    List<String> material721250208Tokens = writeCaptor.getAllValues().stream()
        .filter(request -> request.getDecisions().stream()
            .anyMatch(decision -> "721250208".equals(decision.getMaterialCode())))
        .flatMap(request -> request.getDecisions().stream())
        .map(StandardBindingDecision::getTokenName)
        .toList();
    assertThat(material721250208Tokens)
        .contains("材料价格", "废料价格");
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
  @DisplayName("importExcel：本次导入影响因素短名优先于系统老别名")
  void importExcel_prefersImportedFactorAliasOverGlobalAlias() throws Exception {
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

    when(factorParser.parse(any(), any())).thenReturn(factorWorkbook(a00RowResultSource()));
    FactorUploadBatch batch = new FactorUploadBatch();
    batch.setId(77L);
    when(batchService.createFactorBatch(any())).thenReturn(batch);
    when(monthlyPriceService.upsert(any(), any(), any(), any(), any(), any()))
        .thenReturn(a00UpsertResult());
    FactorRowRefSaveResult rowRefSaveResult = new FactorRowRefSaveResult();
    rowRefSaveResult.setFactorUploadBatchId(77L);
    when(batchService.saveRowRefs(any(), any(), any())).thenReturn(rowRefSaveResult);
    when(formulaWorkbookParser.parse(any(), any())).thenReturn(formulaWorkbookWithoutPriceCellFormula());
    when(refParser.parse(any())).thenReturn(java.util.List.of());
    when(refResolver.resolve(any(), any())).thenReturn(java.util.List.of());
    when(candidateBuilder.build(any(), any(), any(), any())).thenReturn(new BindingCandidateBuildResult());
    when(standardService.checkAndRecord(any())).thenReturn(java.util.List.of());

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
        {"210", "供管处", "联动表无自行添加", null, "部品联动", "铝棒", "301160037",
            "6061-T6 Φ48", "千克", "A00+加工费", null, null, null, null,
            24.899115, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-06", false, "COMMERCIAL", "monthly.xlsx");

    assertThat(resp.getLinkedCount()).isEqualTo(1);
    assertThat(resp.getSkipped()).isZero();
    assertThat(inserted.get().getFormulaExpr())
        .isEqualTo("[NORMALIZED][factor_identity_195]+加工费");
    ArgumentCaptor<PriceVariable> variableCaptor = ArgumentCaptor.forClass(PriceVariable.class);
    verify(priceVariableMapper).insert(variableCaptor.capture());
    assertThat(variableCaptor.getValue().getVariableCode()).isEqualTo("factor_identity_195");
    assertThat(variableCaptor.getValue().getAliasesJson())
        .isEqualTo("[\"A00\",\"9#A00\",\"factor_identity_195\"]");
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
  @DisplayName("importExcel：当前版本内容一致且已有等价绑定时跳过，不新增版本也不重写绑定")
  void importExcel_sameCurrentFormulaVersionSkipsLinkedItemAndBindings() throws Exception {
    PriceLinkedItem existing = sameDerivedFormulaLinkedItem();
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
    when(priceVariableBindingService.listByLinkedItem(88L))
        .thenReturn(List.of(currentBinding("材料含税价格")));
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
    assertThat(resp.getLinkedVersionCreatedCount()).isZero();
    assertThat(resp.getLinkedUnchangedSkippedCount()).isEqualTo(1);
    assertThat(resp.getLinkedExpiredCount()).isZero();
    verify(itemMapper, never()).insert(any(PriceLinkedItem.class));
    verify(itemMapper, never()).updateById(any(PriceLinkedItem.class));
    verify(mocks.writeService(), never()).write(any());
  }

  @Test
  @DisplayName("importExcel：当前版本内容一致但缺少行内变量绑定时补写绑定，不新增公式版本")
  void importExcel_sameCurrentFormulaVersionRepairsMissingAutoBindings() throws Exception {
    PriceLinkedItem existing = sameDerivedFormulaLinkedItem();
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
    when(priceVariableBindingService.listByLinkedItem(88L)).thenReturn(List.of());
    V2Mocks mocks = configureV2Pipeline();

    byte[] xlsx = buildXlsxWithFactorSheetFirst(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "下料重量*材料含税价格", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false,
        "COMMERCIAL", "monthly.xlsx", "APPEND_ONLY");

    assertThat(resp.getLinkedCount()).isZero();
    assertThat(resp.getLinkedCreatedCount()).isZero();
    assertThat(resp.getLinkedUpdatedCount()).isZero();
    assertThat(resp.getLinkedSkippedCount()).isEqualTo(1);
    assertThat(resp.getLinkedUnchangedSkippedCount()).isEqualTo(1);
    assertThat(resp.getAutoBindingCount()).isEqualTo(1);
    assertThat(resp.getNewHistoryBindingCount()).isEqualTo(1);
    verify(itemMapper, never()).insert(any(PriceLinkedItem.class));
    verify(itemMapper, never()).updateById(any(PriceLinkedItem.class));
    ArgumentCaptor<PriceLinkedAutoBindingWriteRequest> writeCaptor =
        ArgumentCaptor.forClass(PriceLinkedAutoBindingWriteRequest.class);
    verify(mocks.writeService()).write(writeCaptor.capture());
    assertThat(writeCaptor.getValue().getLinkedItemId()).isEqualTo(88L);
    assertThat(writeCaptor.getValue().getDecisions())
        .extracting(StandardBindingDecision::getTokenName)
        .containsExactly("材料含税价格");
  }

  @Test
  @DisplayName("importExcel：有供应商时按供应商+料号+业务单元匹配，规格不参与判重")
  void importExcel_currentVersionLookupUsesSupplierMaterialBusinessUnitAndIgnoresSpec() throws Exception {
    PriceLinkedItem existing = sameDerivedFormulaLinkedItem();
    existing.setSpecModel("OLD-SPEC");
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
    when(priceVariableBindingService.listByLinkedItem(88L))
        .thenReturn(List.of(currentBinding("材料含税价格")));
    V2Mocks mocks = configureV2Pipeline();

    byte[] xlsx = buildXlsxWithFactorSheetFirst(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "NEW-SPEC", "千克",
            "下料重量*材料含税价格", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false,
        "COMMERCIAL", "monthly.xlsx", "APPEND_ONLY");

    assertThat(resp.getLinkedSkippedCount()).isEqualTo(1);
    verify(itemMapper, never()).insert(any(PriceLinkedItem.class));
    verify(mocks.writeService(), never()).write(any());
    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(itemMapper).selectOne(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("material_code")
        .contains("business_unit_type")
        .contains("supplier_code")
        .doesNotContain("spec_model");
  }

  @Test
  @DisplayName("importExcel：供应商为空时按料号+业务单元匹配，供应商和规格都不参与判重")
  void importExcel_blankSupplierLookupUsesMaterialBusinessUnitOnly() throws Exception {
    PriceLinkedItem existing = sameDerivedFormulaLinkedItem();
    existing.setSupplierCode("OLD-SUPPLIER");
    existing.setSpecModel("OLD-SPEC");
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
    when(priceVariableBindingService.listByLinkedItem(88L))
        .thenReturn(List.of(currentBinding("材料含税价格")));
    V2Mocks mocks = configureV2Pipeline();

    byte[] xlsx = buildXlsxWithFactorSheetFirst(new Object[][] {
        {"210", "供管处", "供应商A", " ", "部品联动", "物料A", "M001", "NEW-SPEC", "千克",
            "下料重量*材料含税价格", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false,
        "COMMERCIAL", "monthly.xlsx", "APPEND_ONLY");

    assertThat(resp.getLinkedSkippedCount()).isEqualTo(1);
    verify(itemMapper, never()).insert(any(PriceLinkedItem.class));
    verify(mocks.writeService(), never()).write(any());
    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(itemMapper).selectOne(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("material_code")
        .contains("business_unit_type")
        .doesNotContain("supplier_code")
        .doesNotContain("spec_model");
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
    ArgumentCaptor<String> factorStrategyCaptor = ArgumentCaptor.forClass(String.class);
    verify(mocks.monthlyPriceService())
        .upsert(any(), any(), any(), any(), any(), factorStrategyCaptor.capture());
    assertThat(factorStrategyCaptor.getValue()).isEqualTo("KEEP_EXISTING");
  }

  @Test
  @DisplayName("importExcel：当前公式变化时旧版本失效，新版本插入并用新 linked_item_id 写绑定")
  void importExcel_formulaChangeExpiresOldVersionAndBindsNewVersion() throws Exception {
    PriceLinkedItem existing = existingLinkedItem();
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
    AtomicReference<PriceLinkedItem> inserted = new AtomicReference<>();
    doAnswer(invocation -> {
      PriceLinkedItem item = invocation.getArgument(0);
      item.setId(99L);
      inserted.set(item);
      return 1;
    }).when(itemMapper).insert(any(PriceLinkedItem.class));
    V2Mocks mocks = configureV2Pipeline();

    byte[] xlsx = buildXlsxWithFactorSheetFirst(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "下料重量*材料含税价格", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false,
        "COMMERCIAL", "monthly.xlsx", "OVERRIDE_EFFECTIVE", "2026-06-01", "KEEP_EXISTING");

    assertThat(resp.getEffectiveStrategy()).isEqualTo("OVERRIDE_EFFECTIVE");
    assertThat(resp.getImportPurpose()).isEqualTo("LINKED_OVERRIDE_EFFECTIVE");
    assertThat(resp.getLinkedCount()).isEqualTo(1);
    assertThat(resp.getLinkedCreatedCount()).isEqualTo(1);
    assertThat(resp.getLinkedUpdatedCount()).isEqualTo(1);
    ArgumentCaptor<PriceLinkedItem> itemCaptor = ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(itemMapper).updateById(itemCaptor.capture());
    assertThat(itemCaptor.getValue().getId()).isEqualTo(88L);
    assertThat(itemCaptor.getValue().getEffectiveTo()).isEqualTo(LocalDate.of(2026, 5, 31));
    assertThat(inserted.get().getId()).isEqualTo(99L);
    assertThat(inserted.get().getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 6, 1));
    assertThat(inserted.get().getEffectiveTo()).isNull();
    assertThat(inserted.get().getFormulaExpr()).contains("[NORMALIZED]");

    ArgumentCaptor<PriceLinkedAutoBindingWriteRequest> writeCaptor =
        ArgumentCaptor.forClass(PriceLinkedAutoBindingWriteRequest.class);
    verify(mocks.writeService()).write(writeCaptor.capture());
    assertThat(writeCaptor.getValue().getLinkedItemId()).isEqualTo(99L);
    assertThat(writeCaptor.getValue().getOverwriteManualBinding()).isTrue();

    ArgumentCaptor<FactorUploadBatchCreateRequest> batchCaptor =
        ArgumentCaptor.forClass(FactorUploadBatchCreateRequest.class);
    verify(mocks.batchService()).createFactorBatch(batchCaptor.capture());
    assertThat(batchCaptor.getValue().getImportPurpose()).isEqualTo("LINKED_OVERRIDE_EFFECTIVE");
    assertThat(batchCaptor.getValue().getEffectiveStrategy()).isEqualTo("OVERRIDE_EFFECTIVE");
  }

  @Test
  @DisplayName("importExcel：Excel 行级生效/失效日期不控制联动公式生命周期")
  void importExcel_ignoresRowEffectiveDatesForLinkedFormulaVersion() throws Exception {
    AtomicReference<PriceLinkedItem> inserted = new AtomicReference<>();
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    doAnswer(invocation -> {
      PriceLinkedItem item = invocation.getArgument(0);
      item.setId(91L);
      inserted.set(item);
      return 1;
    }).when(itemMapper).insert(any(PriceLinkedItem.class));

    byte[] xlsx = buildXlsx(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "电解铜+加工费", 0.0, 0.0, 12.8, null, 91.0, "0",
            "2026-03-01", "2026-03-31", "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false,
        "COMMERCIAL", "monthly.xlsx", null, "2026-02-01", "KEEP_EXISTING");

    assertThat(resp.getLinkedCount()).isEqualTo(1);
    assertThat(inserted.get().getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 2, 1));
    assertThat(inserted.get().getEffectiveTo()).isNull();
  }

  @Test
  @DisplayName("importExcel：formulaEffectiveDate 不晚于旧版本 effective_from 时记录倒挂失败行")
  void importExcel_rejectsFormulaEffectiveDateOverlap() throws Exception {
    PriceLinkedItem existing = existingLinkedItem();
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

    byte[] xlsx = buildXlsx(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "电解铜+加工费", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false,
        "COMMERCIAL", "monthly.xlsx", null, "2026-05-01", "KEEP_EXISTING");

    assertThat(resp.getLinkedCount()).isZero();
    assertThat(resp.getSkipped()).isEqualTo(1);
    assertThat(resp.getErrors()).hasSize(1);
    assertThat(resp.getErrors().getFirst().getMaterialCode()).isEqualTo("M001");
    assertThat(resp.getErrors().getFirst().getMessage()).contains("生命周期倒挂");
    verify(itemMapper, never()).insert(any(PriceLinkedItem.class));
    verify(itemMapper, never()).updateById(any(PriceLinkedItem.class));
  }

  @Test
  @DisplayName("T8 准真实 Excel 重复导入：未改公式不新增版本且对账快照不变")
  void importExcel_quasiRealWorkbookRepeatImportKeepsCurrentDataStable() throws Exception {
    Assumptions.assumeTrue(
        Files.exists(QUASI_REAL_IMPORT_SAMPLE),
        "准真实 Excel 不存在，跳过本地回归: " + QUASI_REAL_IMPORT_SAMPLE);
    byte[] xlsx = Files.readAllBytes(QUASI_REAL_IMPORT_SAMPLE);
    List<PriceLinkedItem> currentItems = new ArrayList<>();
    AtomicLong linkedItemId = new AtomicLong(100L);
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    doAnswer(invocation -> {
      PriceLinkedItem item = invocation.getArgument(0);
      item.setId(linkedItemId.incrementAndGet());
      currentItems.add(item);
      return 1;
    }).when(itemMapper).insert(any(PriceLinkedItem.class));

    InMemoryLifecycleStore lifecycleStore = new InMemoryLifecycleStore();
    QuasiRealV2Mocks mocks = configureQuasiRealV2Pipeline(lifecycleStore);

    PriceItemImportResponse first = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false,
        "COMMERCIAL", QUASI_REAL_IMPORT_SAMPLE.getFileName().toString(),
        null, "2026-05-01", "KEEP_EXISTING");

    assertThat(first.getFactorRecognizedCount()).isEqualTo(6);
    assertThat(first.getMonthlyPriceCreatedCount()).isEqualTo(6);
    assertThat(first.getMonthlyPriceConflictCount()).isZero();
    assertThat(first.getMonthlyPriceOverwriteCount()).isZero();
    assertThat(first.getLinkedCount()).isEqualTo(3);
    assertThat(first.getLinkedVersionCreatedCount()).isEqualTo(3);
    assertThat(first.getLinkedUnchangedSkippedCount()).isZero();
    assertThat(first.getAutoBindingCount()).isEqualTo(5);
    assertThat(currentItems).hasSize(3);
    assertThat(lifecycleStore.identityCount()).isEqualTo(6);
    assertThat(lifecycleStore.monthlyPriceCount()).isEqualTo(6);

    String beforeRepeatSnapshot = lifecycleSnapshot(currentItems, lifecycleStore);
    ArgumentCaptor<PriceLinkedAutoBindingWriteRequest> firstWriteCaptor =
        ArgumentCaptor.forClass(PriceLinkedAutoBindingWriteRequest.class);
    verify(mocks.writeService(), times(first.getLinkedCount())).write(firstWriteCaptor.capture());
    Map<Long, List<PriceVariableBindingDto>> currentBindings =
        currentBindingsFromWriteRequests(firstWriteCaptor.getAllValues());
    when(priceVariableBindingService.listByLinkedItem(any())).thenAnswer(invocation ->
        currentBindings.getOrDefault(invocation.getArgument(0), List.of()));
    clearInvocations(itemMapper, mocks.standardService(), mocks.writeService());
    when(itemMapper.selectOne(any(Wrapper.class)))
        .thenReturn(currentItems.get(0), currentItems.get(1), currentItems.get(2));

    PriceItemImportResponse second = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-05", false,
        "COMMERCIAL", QUASI_REAL_IMPORT_SAMPLE.getFileName().toString(),
        null, "2026-05-01", "KEEP_EXISTING");

    assertThat(second.getFactorRecognizedCount()).isEqualTo(6);
    assertThat(second.getMonthlyPriceCreatedCount()).isZero();
    assertThat(second.getMonthlyPriceUnchangedCount()).isEqualTo(6);
    assertThat(second.getMonthlyPriceConflictCount()).isZero();
    assertThat(second.getMonthlyPriceOverwriteCount()).isZero();
    assertThat(second.getLinkedCount()).isZero();
    assertThat(second.getLinkedVersionCreatedCount()).isZero();
    assertThat(second.getLinkedUnchangedSkippedCount()).isEqualTo(3);
    assertThat(second.getAutoBindingCount()).isZero();
    assertThat(second.getBindingErrors()).isEmpty();
    assertThat(lifecycleStore.identityCount()).isEqualTo(6);
    assertThat(lifecycleStore.monthlyPriceCount()).isEqualTo(6);
    assertThat(lifecycleSnapshot(currentItems, lifecycleStore))
        .isEqualTo(beforeRepeatSnapshot);
    verify(itemMapper, never()).insert(any(PriceLinkedItem.class));
    verify(itemMapper, never()).updateById(any(PriceLinkedItem.class));
    verify(mocks.standardService(), never()).checkAndRecord(any());
    verify(mocks.writeService(), never()).write(any());
  }

  @Test
  @DisplayName("list：默认只查询 effective_to 为空的当前版本")
  void list_filtersCurrentLinkedVersionsByDefault() {
    when(itemMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of());

    service.list("2026-05", null);

    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(itemMapper).selectList(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment()).contains("effective_to IS NULL");
  }

  @Test
  @DisplayName("list：includeHistory=true 时可显式查询历史失效版本")
  void list_includesExpiredLinkedVersionsWhenRequested() {
    PriceLinkedItem oldVersion = new PriceLinkedItem();
    oldVersion.setId(10L);
    oldVersion.setMaterialCode("M001");
    oldVersion.setEffectiveFrom(LocalDate.of(2026, 5, 1));
    oldVersion.setEffectiveTo(LocalDate.of(2026, 5, 31));
    PriceLinkedItem currentVersion = new PriceLinkedItem();
    currentVersion.setId(11L);
    currentVersion.setMaterialCode("M001");
    currentVersion.setEffectiveFrom(LocalDate.of(2026, 6, 1));
    currentVersion.setEffectiveTo(null);
    when(itemMapper.selectList(any(Wrapper.class)))
        .thenReturn(java.util.List.of(oldVersion, currentVersion));

    var list = service.list("2026-05", null, true);

    assertThat(list).hasSize(2);
    var effectiveToValues = list.stream()
        .map(com.sanhua.marketingcost.dto.PriceLinkedItemDto::getEffectiveTo)
        .toList();
    assertThat(effectiveToValues).contains(LocalDate.of(2026, 5, 31));
    assertThat(effectiveToValues).containsNull();
    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(itemMapper).selectList(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment()).doesNotContain("effective_to IS NULL");
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
    item.setBusinessUnitType("COMMERCIAL");
    item.setMaterialCode("M001");
    item.setSupplierCode("S001");
    item.setSpecModel("SPEC-A");
    item.setFormulaExpr("[old_formula]");
    item.setEffectiveFrom(LocalDate.of(2026, 5, 1));
    item.setOrderType("联动");
    return item;
  }

  private PriceLinkedItem sameDerivedFormulaLinkedItem() {
    PriceLinkedItem item = existingLinkedItem();
    item.setFormulaExpr("[NORMALIZED][blank_weight]*[__material]");
    item.setBlankWeight(BigDecimal.ZERO);
    item.setNetWeight(BigDecimal.ZERO);
    item.setProcessFee(new BigDecimal("12.8"));
    item.setManualPrice(new BigDecimal("91.0"));
    item.setTaxIncluded(0);
    return item;
  }

  private PriceVariableBindingDto currentBinding(String tokenName) {
    return currentBinding(88L, tokenName);
  }

  private PriceVariableBindingDto currentBinding(Long linkedItemId, String tokenName) {
    PriceVariableBindingDto binding = new PriceVariableBindingDto();
    binding.setId(7001L);
    binding.setLinkedItemId(linkedItemId);
    binding.setTokenName(tokenName);
    binding.setFactorCode("SUS304/2Bδ0.6-900");
    binding.setSource("EXCEL_FORMULA");
    return binding;
  }

  private Map<Long, List<PriceVariableBindingDto>> currentBindingsFromWriteRequests(
      List<PriceLinkedAutoBindingWriteRequest> requests) {
    Map<Long, List<PriceVariableBindingDto>> bindings = new LinkedHashMap<>();
    if (requests == null) {
      return bindings;
    }
    for (PriceLinkedAutoBindingWriteRequest request : requests) {
      if (request == null || request.getLinkedItemId() == null) {
        continue;
      }
      List<PriceVariableBindingDto> itemBindings =
          bindings.computeIfAbsent(request.getLinkedItemId(), key -> new ArrayList<>());
      for (StandardBindingDecision decision : request.getDecisions()) {
        if (decision != null && decision.getTokenName() != null) {
          itemBindings.add(currentBinding(request.getLinkedItemId(), decision.getTokenName()));
        }
      }
    }
    return bindings;
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

  private FactorRowParseResult a00RowResultSource() {
    FactorRowParseResult row = new FactorRowParseResult();
    row.setSourceSheetName("影响因素");
    row.setSourceRowNumber(9);
    row.setFactorSeqNo("9");
    row.setFactorName("长江现货市场AOO铝");
    row.setShortName("A00");
    row.setPriceSource("平均价");
    row.setPrice(new java.math.BigDecimal("23.386"));
    row.setOriginalPrice(new java.math.BigDecimal("21.52"));
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

  private FactorMonthlyPriceUpsertResult a00UpsertResult() {
    FactorMonthlyPriceUpsertResult result = new FactorMonthlyPriceUpsertResult();
    result.setMonthlyPriceCreatedCount(1);
    FactorMonthlyPriceUpsertResult.RowResult row = new FactorMonthlyPriceUpsertResult.RowResult();
    row.setSourceSheetName("影响因素");
    row.setSourceRowNumber(9);
    row.setFactorIdentityId(195L);
    row.setFactorMonthlyPriceId(257L);
    row.setFactorSeqNo("9");
    row.setFactorName("长江现货市场AOO铝");
    row.setShortName("A00");
    row.setPriceSource("平均价");
    row.setIdentityAction("UPDATE");
    row.setMonthlyPriceAction("CREATE");
    row.setNewPrice(new java.math.BigDecimal("23.386"));
    row.setOriginalPrice(new java.math.BigDecimal("21.52"));
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

  private LinkedFormulaWorkbookParseResult formulaWorkbookWithoutPriceCellFormula() {
    LinkedFormulaWorkbookParseResult workbook = new LinkedFormulaWorkbookParseResult();
    workbook.setSourceFileName("monthly.xlsx");
    LinkedFormulaSheetParseResult sheet = new LinkedFormulaSheetParseResult();
    sheet.setSheetName("联动公式");
    LinkedFormulaRow row = formulaRow();
    row.setMaterialCode("301160037");
    row.setLinkedItemImportKey("301160037||6061-T6 Φ48");
    row.setFormulaText("A00+加工费");
    row.setPriceCellFormula(null);
    row.setExcelDerivedFormulaText(null);
    row.setPriceCellValue(new java.math.BigDecimal("24.899115"));
    row.setHasFormula(false);
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

  private QuasiRealV2Mocks configureQuasiRealV2Pipeline(
      InMemoryLifecycleStore lifecycleStore) throws Exception {
    PriceLinkedFactorWorkbookParser factorParser = new PriceLinkedFactorWorkbookParserImpl();
    FactorUploadBatchService batchService = mock(FactorUploadBatchService.class);
    FactorMonthlyPriceUpsertService monthlyPriceService = mock(FactorMonthlyPriceUpsertService.class);
    PriceLinkedFormulaWorkbookParser formulaWorkbookParser = new PriceLinkedFormulaWorkbookParserImpl();
    PriceLinkedFormulaFactorRefParser refParser = new PriceLinkedFormulaFactorRefParserImpl();
    PriceLinkedFormulaFactorRefResolver refResolver = mock(PriceLinkedFormulaFactorRefResolver.class);
    PriceLinkedBindingCandidateBuilder candidateBuilder = new PriceLinkedBindingCandidateBuilderImpl();
    PriceLinkedStandardBindingService standardService = mock(PriceLinkedStandardBindingService.class);
    PriceLinkedAutoBindingWriteService writeService = mock(PriceLinkedAutoBindingWriteService.class);
    AtomicLong batchId = new AtomicLong(7700L);

    when(batchService.createFactorBatch(any())).thenAnswer(invocation -> {
      FactorUploadBatch batch = new FactorUploadBatch();
      batch.setId(batchId.incrementAndGet());
      return batch;
    });
    when(monthlyPriceService.upsert(any(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> lifecycleStore.upsert(
            invocation.getArgument(0),
            invocation.getArgument(1),
            invocation.getArgument(2),
            invocation.getArgument(4),
            invocation.getArgument(5)));
    when(batchService.saveRowRefs(any(), any(), any())).thenAnswer(invocation -> {
      FactorRowRefSaveResult result = new FactorRowRefSaveResult();
      result.setFactorUploadBatchId(invocation.getArgument(0));
      FactorMonthlyPriceUpsertResult upsertResult = invocation.getArgument(2);
      result.setInsertedCount(upsertResult == null ? 0 : upsertResult.getRows().size());
      return result;
    });
    when(refResolver.resolve(any(), any())).thenAnswer(invocation -> lifecycleStore.resolve(
        invocation.getArgument(0), invocation.getArgument(1)));
    when(standardService.checkAndRecord(any())).thenAnswer(invocation ->
        standardDecisions(invocation.getArgument(0)));
    when(writeService.write(any())).thenAnswer(invocation ->
        writeAllDecisions(invocation.getArgument(0)));

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
    return new QuasiRealV2Mocks(standardService, writeService);
  }

  private List<StandardBindingDecision> standardDecisions(StandardBindingCheckRequest request) {
    if (request == null || request.getCandidates() == null) {
      return List.of();
    }
    AtomicLong standardBindingId = new AtomicLong(5000L);
    List<StandardBindingDecision> decisions = new ArrayList<>();
    for (BindingCandidate candidate : request.getCandidates()) {
      StandardBindingDecision decision = new StandardBindingDecision();
      decision.setMaterialCode(candidate.getMaterialCode());
      decision.setTokenName(candidate.getTokenName());
      decision.setAction(PriceLinkedStandardBindingServiceImpl.ACTION_CREATE_HISTORY);
      decision.setStandardBindingId(standardBindingId.incrementAndGet());
      decision.setNewFactorIdentityId(candidate.getFactorIdentityId());
      decision.setReason("T8 准真实回归：历史关系一致，允许自动绑定");
      decision.setCandidate(candidate);
      decisions.add(decision);
    }
    return decisions;
  }

  private PriceLinkedAutoBindingWriteResult writeAllDecisions(
      PriceLinkedAutoBindingWriteRequest request) {
    PriceLinkedAutoBindingWriteResult result = new PriceLinkedAutoBindingWriteResult();
    if (request == null || request.getDecisions() == null) {
      return result;
    }
    long bindingId = 9000L;
    for (StandardBindingDecision decision : request.getDecisions()) {
      if (decision != null) {
        result.addWritten(decision.getTokenName(), ++bindingId);
      }
    }
    return result;
  }

  private String lifecycleSnapshot(
      List<PriceLinkedItem> items, InMemoryLifecycleStore lifecycleStore) {
    List<String> itemRows = items.stream()
        .sorted(Comparator.comparing(item -> text(item.getMaterialCode())))
        .map(item -> String.join("|",
            text(item.getMaterialCode()),
            text(item.getSupplierCode()),
            text(item.getSpecModel()),
            text(item.getFormulaExpr()),
            text(item.getFormulaExprCn()),
            text(item.getBlankWeight()),
            text(item.getNetWeight()),
            text(item.getProcessFee()),
            text(item.getAgentFee()),
            text(item.getManualPrice()),
            text(item.getTaxIncluded()),
            text(item.getEffectiveFrom()),
            text(item.getEffectiveTo())))
        .toList();
    return String.join("\n", itemRows) + "\n" + lifecycleStore.snapshot();
  }

  private String text(Object value) {
    return Objects.toString(value, "");
  }

  private static final class InMemoryLifecycleStore {
    private final Map<String, IdentityRecord> identities = new LinkedHashMap<>();
    private final Map<String, MonthlyPriceRecord> monthlyPrices = new LinkedHashMap<>();
    private final Map<String, FactorMonthlyPriceUpsertResult.RowResult> rowRefs =
        new LinkedHashMap<>();
    private long nextIdentityId = 1000L;
    private long nextMonthlyPriceId = 2000L;

    FactorMonthlyPriceUpsertResult upsert(
        FactorWorkbookParseResult parseResult,
        String priceMonth,
        String businessUnitType,
        Long sourceUploadBatchId,
        String factorPriceConflictStrategy) {
      FactorMonthlyPriceUpsertResult result = new FactorMonthlyPriceUpsertResult();
      if (parseResult == null) {
        return result;
      }
      for (FactorSheetParseResult sheet : parseResult.getSheets()) {
        for (FactorRowParseResult row : sheet.getRows()) {
          upsertRow(row, priceMonth, businessUnitType, sourceUploadBatchId,
              factorPriceConflictStrategy, result);
        }
      }
      return result;
    }

    List<ResolvedFactorRef> resolve(Long factorUploadBatchId, List<FormulaFactorRef> refs) {
      if (refs == null) {
        return List.of();
      }
      List<ResolvedFactorRef> resolved = new ArrayList<>();
      for (FormulaFactorRef ref : refs) {
        ResolvedFactorRef out = copyRef(ref);
        FactorMonthlyPriceUpsertResult.RowResult rowRef =
            ref == null ? null : rowRefs.get(cellKey(ref.getSheetName(), ref.getRowNumber()));
        if (rowRef == null) {
          out.setErrorMessage("T8 准真实回归未找到影响因素行号映射");
        } else {
          out.setFactorIdentityId(rowRef.getFactorIdentityId());
          out.setFactorMonthlyPriceId(rowRef.getFactorMonthlyPriceId());
          out.setFactorSeqNo(rowRef.getFactorSeqNo());
          out.setShortName(rowRef.getShortName());
          out.setPriceSource(rowRef.getPriceSource());
          out.setPrice(rowRef.getNewPrice());
        }
        resolved.add(out);
      }
      return resolved;
    }

    int identityCount() {
      return identities.size();
    }

    int monthlyPriceCount() {
      return monthlyPrices.size();
    }

    String snapshot() {
      List<String> monthlyRows = monthlyPrices.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .map(entry -> entry.getKey() + "=" + normalizePrice(entry.getValue().price()))
          .toList();
      return "identityCount=" + identityCount()
          + ";monthlyPriceCount=" + monthlyPriceCount()
          + ";monthly=" + String.join(",", monthlyRows);
    }

    private void upsertRow(
        FactorRowParseResult row,
        String priceMonth,
        String businessUnitType,
        Long sourceUploadBatchId,
        String factorPriceConflictStrategy,
        FactorMonthlyPriceUpsertResult result) {
      String identityKey = identityKey(row, businessUnitType);
      IdentityRecord identity = identities.get(identityKey);
      String identityAction;
      if (identity == null) {
        identity = new IdentityRecord(
            ++nextIdentityId,
            normalize(row.getFactorSeqNo()),
            normalize(row.getFactorName()),
            normalize(row.getShortName()),
            normalize(row.getPriceSource()));
        identities.put(identityKey, identity);
        result.setIdentityCreatedCount(result.getIdentityCreatedCount() + 1);
        identityAction = "CREATE";
      } else {
        result.setIdentityReusedCount(result.getIdentityReusedCount() + 1);
        identityAction = "REUSE";
      }

      String monthlyKey = identity.id() + "|" + normalize(priceMonth);
      MonthlyPriceRecord monthlyPrice = monthlyPrices.get(monthlyKey);
      BigDecimal oldPrice = monthlyPrice == null ? null : monthlyPrice.price();
      BigDecimal incomingPrice = normalizePrice(row.getPrice());
      String monthlyAction;
      if (monthlyPrice == null) {
        monthlyPrice = new MonthlyPriceRecord(++nextMonthlyPriceId, incomingPrice);
        monthlyPrices.put(monthlyKey, monthlyPrice);
        result.setMonthlyPriceCreatedCount(result.getMonthlyPriceCreatedCount() + 1);
        monthlyAction = "CREATE";
      } else if (samePrice(monthlyPrice.price(), incomingPrice)) {
        result.setMonthlyPriceUnchangedCount(result.getMonthlyPriceUnchangedCount() + 1);
        monthlyAction = "NO_CHANGE";
      } else if ("KEEP_EXISTING".equalsIgnoreCase(factorPriceConflictStrategy)) {
        result.setMonthlyPriceSkippedCount(result.getMonthlyPriceSkippedCount() + 1);
        result.setMonthlyPriceConflictCount(result.getMonthlyPriceConflictCount() + 1);
        monthlyAction = "CONFLICT_KEEP_EXISTING";
      } else {
        monthlyPrice.setPrice(incomingPrice);
        result.setMonthlyPriceUpdatedCount(result.getMonthlyPriceUpdatedCount() + 1);
        result.setMonthlyPriceOverwriteCount(result.getMonthlyPriceOverwriteCount() + 1);
        monthlyAction = "UPDATE";
      }

      FactorMonthlyPriceUpsertResult.RowResult rowResult =
          new FactorMonthlyPriceUpsertResult.RowResult();
      rowResult.setSourceSheetName(row.getSourceSheetName());
      rowResult.setSourceRowNumber(row.getSourceRowNumber());
      rowResult.setFactorIdentityId(identity.id());
      rowResult.setFactorMonthlyPriceId(monthlyPrice.id());
      rowResult.setFactorSeqNo(identity.factorSeqNo());
      rowResult.setFactorName(identity.factorName());
      rowResult.setShortName(identity.shortName());
      rowResult.setPriceSource(identity.priceSource());
      rowResult.setIdentityAction(identityAction);
      rowResult.setMonthlyPriceAction(monthlyAction);
      rowResult.setOldPrice(oldPrice);
      rowResult.setNewPrice(monthlyPrice.price());
      rowResult.setOriginalPrice(normalizePrice(row.getOriginalPrice()));
      rowResult.setUnit(normalize(row.getUnit()));
      result.getRows().add(rowResult);
      rowRefs.put(cellKey(row.getSourceSheetName(), row.getSourceRowNumber()), rowResult);
    }

    private ResolvedFactorRef copyRef(FormulaFactorRef ref) {
      ResolvedFactorRef out = new ResolvedFactorRef();
      if (ref == null) {
        return out;
      }
      out.setWorkbookName(ref.getWorkbookName());
      out.setSheetName(ref.getSheetName());
      out.setColumnName(ref.getColumnName());
      out.setRowNumber(ref.getRowNumber());
      out.setRawRef(ref.getRawRef());
      return out;
    }

    private String identityKey(FactorRowParseResult row, String businessUnitType) {
      return String.join("|",
          normalize(businessUnitType),
          normalize(row.getFactorSeqNo()),
          normalize(row.getFactorName()),
          normalize(row.getShortName()),
          normalize(row.getPriceSource()));
    }

    private static String cellKey(String sheetName, Integer rowNumber) {
      return normalize(sheetName) + "#" + rowNumber;
    }

    private static String normalize(String raw) {
      return raw == null ? "" : raw.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static BigDecimal normalizePrice(BigDecimal value) {
      return value == null ? null : value.stripTrailingZeros();
    }

    private static boolean samePrice(BigDecimal left, BigDecimal right) {
      if (left == null || right == null) {
        return left == right;
      }
      return left.compareTo(right) == 0;
    }

    private record IdentityRecord(
        Long id,
        String factorSeqNo,
        String factorName,
        String shortName,
        String priceSource) {
    }

    private static final class MonthlyPriceRecord {
      private final Long id;
      private BigDecimal price;

      private MonthlyPriceRecord(Long id, BigDecimal price) {
        this.id = id;
        this.price = price;
      }

      private Long id() {
        return id;
      }

      private BigDecimal price() {
        return price;
      }

      private void setPrice(BigDecimal price) {
        this.price = price;
      }
    }
  }

  private record QuasiRealV2Mocks(
      PriceLinkedStandardBindingService standardService,
      PriceLinkedAutoBindingWriteService writeService) {
  }

  private record V2Mocks(
      FactorUploadBatchService batchService,
      FactorMonthlyPriceUpsertService monthlyPriceService,
      PriceLinkedAutoBindingWriteService writeService) {
  }
}
