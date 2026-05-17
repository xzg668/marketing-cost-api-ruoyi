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
import com.sanhua.marketingcost.dto.ResolvedFactorRef;
import com.sanhua.marketingcost.dto.StandardBindingCheckRequest;
import com.sanhua.marketingcost.dto.StandardBindingDecision;
import com.sanhua.marketingcost.entity.MaterialFactorBindingStd;
import com.sanhua.marketingcost.mapper.MaterialFactorBindingStdMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PriceLinkedStandardBindingServiceImplTest {

  private MaterialFactorBindingStdMapper standardBindingMapper;
  private PriceLinkedStandardBindingServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, MaterialFactorBindingStd.class);
  }

  @BeforeEach
  void setUp() {
    standardBindingMapper = mock(MaterialFactorBindingStdMapper.class);
    service = new PriceLinkedStandardBindingServiceImpl(standardBindingMapper);
  }

  @Test
  @DisplayName("checkAndRecord：A 首次上传料号 M001，自动新增标准关系")
  void firstUploadCreatesHistory() {
    when(standardBindingMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(standardBindingMapper.insert(any(MaterialFactorBindingStd.class))).thenAnswer(inv -> {
      MaterialFactorBindingStd row = inv.getArgument(0);
      row.setId(501L);
      return 1;
    });

    List<StandardBindingDecision> decisions = service.checkAndRecord(request(
        "BU-A", "M001", "S001", 101L, true, List.of(candidate("M001", "S001", "材料含税价格", 1001L))));

    assertThat(decisions).hasSize(1);
    StandardBindingDecision decision = decisions.getFirst();
    assertThat(decision.getAction())
        .isEqualTo(PriceLinkedStandardBindingServiceImpl.ACTION_CREATE_HISTORY);
    assertThat(decision.getStandardBindingId()).isEqualTo(501L);
    assertThat(decision.getOldFactorIdentityId()).isNull();
    assertThat(decision.getNewFactorIdentityId()).isEqualTo(1001L);

    ArgumentCaptor<MaterialFactorBindingStd> captor =
        ArgumentCaptor.forClass(MaterialFactorBindingStd.class);
    verify(standardBindingMapper).insert(captor.capture());
    MaterialFactorBindingStd inserted = captor.getValue();
    assertThat(inserted.getBusinessUnitType()).isEqualTo("BU-A");
    assertThat(inserted.getMaterialCode()).isEqualTo("M001");
    assertThat(inserted.getSupplierCode()).isEqualTo("S001");
    assertThat(inserted.getTokenName()).isEqualTo("材料含税价格");
    assertThat(inserted.getFactorIdentityId()).isEqualTo(1001L);
    assertThat(inserted.getFirstImportBatchId()).isEqualTo(101L);
    assertThat(inserted.getLastImportBatchId()).isEqualTo(101L);
  }

  @Test
  @DisplayName("checkAndRecord：B 上传同料号且公式一致，校验通过并刷新最近批次")
  void consistentHistoryPassesAndRefreshes() {
    MaterialFactorBindingStd existing = standard("BU-A", "M001", "S001", "材料含税价格", 1001L);
    existing.setId(501L);
    existing.setFirstImportBatchId(101L);
    existing.setLastImportBatchId(101L);
    when(standardBindingMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

    List<StandardBindingDecision> decisions = service.checkAndRecord(request(
        "BU-A", "M001", "S001", 202L, true, List.of(candidate("M001", "S001", "材料含税价格", 1001L))));

    StandardBindingDecision decision = decisions.getFirst();
    assertThat(decision.getAction()).isEqualTo(PriceLinkedStandardBindingServiceImpl.ACTION_CONSISTENT);
    assertThat(decision.getStandardBindingId()).isEqualTo(501L);
    assertThat(decision.getOldFactorIdentityId()).isEqualTo(1001L);
    assertThat(decision.getNewFactorIdentityId()).isEqualTo(1001L);
    assertThat(existing.getLastImportBatchId()).isEqualTo(202L);
    verify(standardBindingMapper).updateById(existing);
  }

  @Test
  @DisplayName("checkAndRecord：B 上传同料号但公式指向不同身份，返回冲突且不覆盖")
  void conflictDoesNotOverwrite() {
    MaterialFactorBindingStd existing = standard("BU-A", "M001", "S001", "材料含税价格", 1001L);
    existing.setId(501L);
    when(standardBindingMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

    List<StandardBindingDecision> decisions = service.checkAndRecord(request(
        "BU-A", "M001", "S001", 202L, true, List.of(candidate("M001", "S001", "材料含税价格", 9999L))));

    StandardBindingDecision decision = decisions.getFirst();
    assertThat(decision.getAction()).isEqualTo(PriceLinkedStandardBindingServiceImpl.ACTION_CONFLICT);
    assertThat(decision.getStandardBindingId()).isEqualTo(501L);
    assertThat(decision.getOldFactorIdentityId()).isEqualTo(1001L);
    assertThat(decision.getNewFactorIdentityId()).isEqualTo(9999L);
    assertThat(decision.getReason()).contains("不一致");
    assertThat(existing.getFactorIdentityId()).isEqualTo(1001L);
    verify(standardBindingMapper, never()).updateById(any(MaterialFactorBindingStd.class));
    verify(standardBindingMapper, never()).insert(any(MaterialFactorBindingStd.class));
  }

  @Test
  @DisplayName("checkAndRecord：单价列无公式时不使用历史关系兜底，返回 FAILED")
  void missingFormulaDoesNotUseHistoryFallback() {
    List<StandardBindingDecision> decisions = service.checkAndRecord(request(
        "BU-A", "M001", "S001", 202L, false, List.of()));

    assertThat(decisions).hasSize(1);
    StandardBindingDecision decision = decisions.getFirst();
    assertThat(decision.getAction()).isEqualTo(PriceLinkedStandardBindingServiceImpl.ACTION_FAILED);
    assertThat(decision.getReason()).contains("重新导入带公式");
    verify(standardBindingMapper, never()).selectOne(any());
    verify(standardBindingMapper, never()).insert(any(MaterialFactorBindingStd.class));
    verify(standardBindingMapper, never()).updateById(any(MaterialFactorBindingStd.class));
  }

  @Test
  @DisplayName("checkAndRecord：每条绑定候选都有历史关系校验决策")
  void everyCandidateGetsDecision() {
    when(standardBindingMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(standardBindingMapper.insert(any(MaterialFactorBindingStd.class))).thenAnswer(inv -> 1);

    List<StandardBindingDecision> decisions = service.checkAndRecord(request(
        "BU-A", "M001", "S001", 101L, true,
        List.of(
            candidate("M001", "S001", "材料含税价格", 1001L),
            candidate("M001", "S001", "废料含税价格", 1002L))));

    assertThat(decisions).hasSize(2);
    assertThat(decisions).extracting(StandardBindingDecision::getTokenName)
        .containsExactly("材料含税价格", "废料含税价格");
  }

  private StandardBindingCheckRequest request(
      String businessUnitType,
      String materialCode,
      String supplierCode,
      Long batchId,
      boolean formulaAvailable,
      List<BindingCandidate> candidates) {
    StandardBindingCheckRequest request = new StandardBindingCheckRequest();
    request.setBusinessUnitType(businessUnitType);
    request.setMaterialCode(materialCode);
    request.setSupplierCode(supplierCode);
    request.setLinkedItemImportKey(materialCode + "|" + supplierCode + "|SPEC");
    request.setFactorUploadBatchId(batchId);
    request.setFormulaText("下料重量*材料含税价格-(下料重量-产品净重)*废料含税价格");
    request.setFormulaAvailable(formulaAvailable);
    request.setOperator("tester");
    request.getCandidates().addAll(candidates);
    return request;
  }

  private BindingCandidate candidate(
      String materialCode, String supplierCode, String tokenName, Long factorIdentityId) {
    BindingCandidate candidate = new BindingCandidate();
    candidate.setMaterialCode(materialCode);
    candidate.setLinkedItemImportKey(materialCode + "|" + supplierCode + "|SPEC");
    candidate.setTokenName(tokenName);
    candidate.setFactorIdentityId(factorIdentityId);
    candidate.setFactorMonthlyPriceId(factorIdentityId + 1000);
    ResolvedFactorRef sourceRef = new ResolvedFactorRef();
    sourceRef.setFactorIdentityId(factorIdentityId);
    sourceRef.setFactorMonthlyPriceId(factorIdentityId + 1000);
    candidate.setSourceRef(sourceRef);
    return candidate;
  }

  private MaterialFactorBindingStd standard(
      String businessUnitType,
      String materialCode,
      String supplierCode,
      String tokenName,
      Long factorIdentityId) {
    MaterialFactorBindingStd standard = new MaterialFactorBindingStd();
    standard.setBusinessUnitType(businessUnitType);
    standard.setMaterialCode(materialCode);
    standard.setSupplierCode(supplierCode);
    standard.setTokenName(tokenName);
    standard.setFactorIdentityId(factorIdentityId);
    standard.setStatus("ACTIVE");
    return standard;
  }
}
