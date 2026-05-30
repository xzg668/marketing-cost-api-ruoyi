package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.BindingCandidate;
import com.sanhua.marketingcost.dto.PriceLinkedAutoBindingWriteRequest;
import com.sanhua.marketingcost.dto.PriceLinkedAutoBindingWriteResult;
import com.sanhua.marketingcost.dto.PriceVariableBindingRequest;
import com.sanhua.marketingcost.dto.ResolvedFactorRef;
import com.sanhua.marketingcost.dto.StandardBindingDecision;
import com.sanhua.marketingcost.entity.ExcelAutoBindingImportLog;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.entity.PriceVariableBinding;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl;
import com.sanhua.marketingcost.mapper.ExcelAutoBindingImportLogMapper;
import com.sanhua.marketingcost.mapper.PriceVariableBindingMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.PriceVariableBindingService;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PriceLinkedAutoBindingWriteServiceImplTest {

  private PriceVariableBindingMapper bindingMapper;
  private PriceVariableMapper priceVariableMapper;
  private PriceVariableBindingService priceVariableBindingService;
  private FactorVariableRegistryImpl factorVariableRegistry;
  private ExcelAutoBindingImportLogMapper autoBindingImportLogMapper;
  private PriceLinkedAutoBindingWriteServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceVariable.class);
    TableInfoHelper.initTableInfo(assistant, PriceVariableBinding.class);
    TableInfoHelper.initTableInfo(assistant, ExcelAutoBindingImportLog.class);
  }

  @BeforeEach
  void setUp() {
    bindingMapper = mock(PriceVariableBindingMapper.class);
    priceVariableMapper = mock(PriceVariableMapper.class);
    priceVariableBindingService = mock(PriceVariableBindingService.class);
    factorVariableRegistry = mock(FactorVariableRegistryImpl.class);
    autoBindingImportLogMapper = mock(ExcelAutoBindingImportLogMapper.class);
    service = new PriceLinkedAutoBindingWriteServiceImpl(
        bindingMapper, priceVariableMapper, priceVariableBindingService, factorVariableRegistry,
        autoBindingImportLogMapper);
  }

  @Test
  @DisplayName("write：新料号识别成功后写入 EXCEL_FORMULA 行级绑定")
  void writeCreatesExcelFormulaBinding() {
    when(bindingMapper.findCurrentByLinkedItemIdAndToken(88L, "材料含税价格")).thenReturn(null);
    when(priceVariableMapper.selectOne(any(Wrapper.class))).thenReturn(variable("SUS304/2B"));
    when(priceVariableBindingService.save(any(PriceVariableBindingRequest.class))).thenReturn(9001L);

    PriceLinkedAutoBindingWriteResult result = service.write(request(false,
        decision(PriceLinkedStandardBindingServiceImpl.ACTION_CREATE_HISTORY, "材料含税价格", 501L)));

    assertThat(result.getWrittenCount()).isEqualTo(1);
    assertThat(result.getManualSkippedCount()).isZero();
    assertThat(result.getErrorCount()).isZero();

    ArgumentCaptor<PriceVariableBindingRequest> captor =
        ArgumentCaptor.forClass(PriceVariableBindingRequest.class);
    verify(priceVariableBindingService).save(captor.capture());
    PriceVariableBindingRequest saved = captor.getValue();
    assertThat(saved.getLinkedItemId()).isEqualTo(88L);
    assertThat(saved.getTokenName()).isEqualTo("材料含税价格");
    assertThat(saved.getFactorCode()).isEqualTo("SUS304/2B");
    assertThat(saved.getFactorIdentityId()).isEqualTo(1001L);
    assertThat(saved.getFactorMonthlyPriceId()).isEqualTo(2001L);
    assertThat(saved.getStandardBindingId()).isEqualTo(501L);
    assertThat(saved.getFactorUploadBatchId()).isEqualTo(77L);
    assertThat(saved.getExcelSourceSheetName()).isEqualTo("影响因素");
    assertThat(saved.getExcelSourceCellRef()).isEqualTo("E64");
    assertThat(saved.getExcelFormula()).contains("影响因素!$E$64");
    assertThat(saved.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 5, 1));
    assertThat(saved.getSource()).isEqualTo("EXCEL_FORMULA");

    ArgumentCaptor<ExcelAutoBindingImportLog> logCaptor =
        ArgumentCaptor.forClass(ExcelAutoBindingImportLog.class);
    verify(autoBindingImportLogMapper).insert(logCaptor.capture());
    ExcelAutoBindingImportLog importLog = logCaptor.getValue();
    assertThat(importLog.getAction()).isEqualTo("AUTO_BOUND");
    assertThat(importLog.getStatus()).isEqualTo("SUCCESS");
    assertThat(importLog.getFactorUploadBatchId()).isEqualTo(77L);
    assertThat(importLog.getSourceWorkbookName()).isEqualTo("monthly.xlsx");
    assertThat(importLog.getSourceSheetName()).isEqualTo("影响因素");
    assertThat(importLog.getSourceCellRef()).isEqualTo("E64");
    assertThat(importLog.getSupplierCode()).isEqualTo("S001");
  }

  @Test
  @DisplayName("write：老料号历史关系校验一致时，仍按本次 Excel 公式写入")
  void writeConsistentDecision() {
    when(bindingMapper.findCurrentByLinkedItemIdAndToken(88L, "废料含税价格")).thenReturn(null);
    when(priceVariableMapper.selectOne(any(Wrapper.class))).thenReturn(variable("SUS304/2B"));
    when(priceVariableBindingService.save(any(PriceVariableBindingRequest.class))).thenReturn(9002L);

    PriceLinkedAutoBindingWriteResult result = service.write(request(false,
        decision(PriceLinkedStandardBindingServiceImpl.ACTION_CONSISTENT, "废料含税价格", 502L)));

    assertThat(result.getWrittenCount()).isEqualTo(1);
    verify(priceVariableBindingService).save(any(PriceVariableBindingRequest.class));
  }

  @Test
  @DisplayName("write：已有 MANUAL 绑定默认不覆盖")
  void writeSkipsManualByDefault() {
    PriceVariableBinding current = new PriceVariableBinding();
    current.setId(7L);
    current.setSource("MANUAL");
    when(bindingMapper.findCurrentByLinkedItemIdAndToken(88L, "材料含税价格")).thenReturn(current);

    PriceLinkedAutoBindingWriteResult result = service.write(request(false,
        decision(PriceLinkedStandardBindingServiceImpl.ACTION_CONSISTENT, "材料含税价格", 501L)));

    assertThat(result.getWrittenCount()).isZero();
    assertThat(result.getManualSkippedCount()).isEqualTo(1);
    assertThat(result.getRows().getFirst().getAction()).isEqualTo("SKIPPED_MANUAL");
    verify(priceVariableBindingService, never()).save(any());
    ArgumentCaptor<ExcelAutoBindingImportLog> logCaptor =
        ArgumentCaptor.forClass(ExcelAutoBindingImportLog.class);
    verify(autoBindingImportLogMapper).insert(logCaptor.capture());
    assertThat(logCaptor.getValue().getAction()).isEqualTo("SKIPPED_MANUAL");
    assertThat(logCaptor.getValue().getStatus()).isEqualTo("WARNING");
    assertThat(logCaptor.getValue().getMessage()).contains("默认不覆盖");
  }

  @Test
  @DisplayName("write：用户勾选覆盖后，允许覆盖 MANUAL 绑定")
  void writeOverwritesManualWhenAllowed() {
    PriceVariableBinding current = new PriceVariableBinding();
    current.setId(7L);
    current.setSource("MANUAL");
    when(bindingMapper.findCurrentByLinkedItemIdAndToken(88L, "材料含税价格")).thenReturn(current);
    when(priceVariableMapper.selectOne(any(Wrapper.class))).thenReturn(variable("SUS304/2B"));
    when(priceVariableBindingService.save(any(PriceVariableBindingRequest.class))).thenReturn(9003L);

    PriceLinkedAutoBindingWriteResult result = service.write(request(true,
        decision(PriceLinkedStandardBindingServiceImpl.ACTION_CONSISTENT, "材料含税价格", 501L)));

    assertThat(result.getWrittenCount()).isEqualTo(1);
    assertThat(result.getManualSkippedCount()).isZero();
    verify(priceVariableBindingService).save(any(PriceVariableBindingRequest.class));
    ArgumentCaptor<ExcelAutoBindingImportLog> logCaptor =
        ArgumentCaptor.forClass(ExcelAutoBindingImportLog.class);
    verify(autoBindingImportLogMapper).insert(logCaptor.capture());
    assertThat(logCaptor.getValue().getMessage()).contains("覆盖 MANUAL 绑定");
  }

  @Test
  @DisplayName("write：冲突决策不写入行级绑定")
  void writeSkipsConflictDecision() {
    PriceLinkedAutoBindingWriteResult result = service.write(request(false,
        decision(PriceLinkedStandardBindingServiceImpl.ACTION_CONFLICT, "材料含税价格", 501L)));

    assertThat(result.getWrittenCount()).isZero();
    assertThat(result.getConflictSkippedCount()).isEqualTo(1);
    assertThat(result.getErrorCount()).isZero();
    assertThat(result.getRows().getFirst().getAction()).isEqualTo("SKIPPED_CONFLICT");
    assertThat(result.getRows().getFirst().getReason()).contains("不一致");
    verify(priceVariableBindingService, never()).save(any());
    ArgumentCaptor<ExcelAutoBindingImportLog> logCaptor =
        ArgumentCaptor.forClass(ExcelAutoBindingImportLog.class);
    verify(autoBindingImportLogMapper).insert(logCaptor.capture());
    assertThat(logCaptor.getValue().getAction()).isEqualTo("SKIPPED_CONFLICT");
    assertThat(logCaptor.getValue().getStatus()).isEqualTo("WARNING");
  }

  @Test
  @DisplayName("write：变量不存在时自动登记财务变量并刷新变量缓存")
  void writeCreatesMissingFinanceVariable() {
    when(bindingMapper.findCurrentByLinkedItemIdAndToken(88L, "材料含税价格")).thenReturn(null);
    when(priceVariableMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(priceVariableBindingService.save(any(PriceVariableBindingRequest.class))).thenReturn(9004L);

    PriceLinkedAutoBindingWriteResult result = service.write(request(false,
        decision(PriceLinkedStandardBindingServiceImpl.ACTION_CONSISTENT, "材料含税价格", 501L)));

    assertThat(result.getWrittenCount()).isEqualTo(1);
    ArgumentCaptor<PriceVariable> captor = ArgumentCaptor.forClass(PriceVariable.class);
    verify(priceVariableMapper).insert(captor.capture());
    assertThat(captor.getValue().getVariableCode()).isEqualTo("SUS304/2B");
    assertThat(captor.getValue().getResolverKind()).isEqualTo("FINANCE");
    assertThat(captor.getValue().getResolverParams()).contains("\"shortName\":\"SUS304/2B\"");
    verify(factorVariableRegistry).invalidate();
  }

  private PriceLinkedAutoBindingWriteRequest request(
      boolean overwriteManualBinding, StandardBindingDecision decision) {
    PriceLinkedAutoBindingWriteRequest request = new PriceLinkedAutoBindingWriteRequest();
    request.setLinkedItemId(88L);
    request.setPricingMonth("2026-05");
    request.setFactorUploadBatchId(77L);
    request.setExcelFormula("ROUND($I$2*影响因素!$E$64/1000+K2,4)/1.13");
    request.setOverwriteManualBinding(overwriteManualBinding);
    request.getDecisions().add(decision);
    return request;
  }

  private StandardBindingDecision decision(String action, String tokenName, Long standardBindingId) {
    BindingCandidate candidate = new BindingCandidate();
    candidate.setMaterialCode("MAT-1001");
    candidate.setLinkedItemImportKey("MAT-1001|S001|SPEC");
    candidate.setTokenName(tokenName);
    candidate.setFactorIdentityId(1001L);
    candidate.setFactorMonthlyPriceId(2001L);
    candidate.setSourceRef(sourceRef());

    StandardBindingDecision decision = new StandardBindingDecision();
    decision.setMaterialCode("MAT-1001");
    decision.setTokenName(tokenName);
    decision.setAction(action);
    decision.setStandardBindingId(standardBindingId);
    decision.setOldFactorIdentityId(9999L);
    decision.setNewFactorIdentityId(1001L);
    decision.setReason(
        PriceLinkedStandardBindingServiceImpl.ACTION_CONFLICT.equals(action)
            ? "本次公式识别结果与历史标准关系不一致，默认不覆盖，请人工确认"
            : "本次公式识别结果与历史标准关系一致");
    decision.setCandidate(candidate);
    return decision;
  }

  private ResolvedFactorRef sourceRef() {
    ResolvedFactorRef ref = new ResolvedFactorRef();
    ref.setWorkbookName("monthly.xlsx");
    ref.setSheetName("影响因素");
    ref.setColumnName("E");
    ref.setRowNumber(64);
    ref.setRawRef("影响因素!$E$64");
    ref.setFactorIdentityId(1001L);
    ref.setFactorMonthlyPriceId(2001L);
    ref.setFactorSeqNo("64");
    ref.setShortName("SUS304/2B");
    ref.setPriceSource("出厂价");
    return ref;
  }

  private PriceVariable variable(String code) {
    PriceVariable variable = new PriceVariable();
    variable.setVariableCode(code);
    variable.setVariableName(code);
    return variable;
  }
}
