package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.BindingCandidate;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceLinkedAutoBindingWriteResult;
import com.sanhua.marketingcost.dto.PriceLinkedImportResultClassifyRequest;
import com.sanhua.marketingcost.dto.ResolvedFactorRef;
import com.sanhua.marketingcost.dto.StandardBindingDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PriceLinkedImportResultClassifierImplTest {

  private final PriceLinkedImportResultClassifierImpl classifier =
      new PriceLinkedImportResultClassifierImpl();

  @Test
  @DisplayName("append：新料号新增历史校验关系有明确数量，自动写入有明确数量")
  void appendCountsNewHistoryAndAutoBinding() {
    PriceItemImportResponse response = new PriceItemImportResponse();
    PriceLinkedImportResultClassifyRequest request = baseRequest();
    request.getStandardDecisions().add(decision(
        PriceLinkedStandardBindingServiceImpl.ACTION_CREATE_HISTORY, 501L, null, 1001L));
    PriceLinkedAutoBindingWriteResult writeResult = new PriceLinkedAutoBindingWriteResult();
    writeResult.addWritten("材料含税价格", 9001L);
    request.setWriteResult(writeResult);

    classifier.append(response, request);

    assertThat(response.getNewHistoryBindingCount()).isEqualTo(1);
    assertThat(response.getConsistentHistoryBindingCount()).isZero();
    assertThat(response.getAutoBindingCount()).isEqualTo(1);
    assertThat(response.getBindingErrorCount()).isZero();
    assertThat(response.getBindingErrors()).isEmpty();
  }

  @Test
  @DisplayName("append：老料号历史关系一致有明确数量")
  void appendCountsConsistentHistory() {
    PriceItemImportResponse response = new PriceItemImportResponse();
    PriceLinkedImportResultClassifyRequest request = baseRequest();
    request.getStandardDecisions().add(decision(
        PriceLinkedStandardBindingServiceImpl.ACTION_CONSISTENT, 501L, 1001L, 1001L));

    classifier.append(response, request);

    assertThat(response.getNewHistoryBindingCount()).isZero();
    assertThat(response.getConsistentHistoryBindingCount()).isEqualTo(1);
    assertThat(response.getBindingErrorCount()).isZero();
  }

  @Test
  @DisplayName("append：冲突项展示已有关系和本次识别结果")
  void appendConflictAddsBindingErrorWithOldAndNewIdentity() {
    PriceItemImportResponse response = new PriceItemImportResponse();
    PriceLinkedImportResultClassifyRequest request = baseRequest();
    request.getStandardDecisions().add(decision(
        PriceLinkedStandardBindingServiceImpl.ACTION_CONFLICT, 501L, 1001L, 9999L));

    classifier.append(response, request);

    assertThat(response.getConflictBindingCount()).isEqualTo(1);
    assertThat(response.getBindingErrorCount()).isEqualTo(1);
    PriceItemImportResponse.BindingError error = response.getBindingErrors().getFirst();
    assertThat(error.getExcelRowNumber()).isEqualTo(3);
    assertThat(error.getMaterialCode()).isEqualTo("MAT-1001");
    assertThat(error.getTokenName()).isEqualTo("材料含税价格");
    assertThat(error.getFormula()).contains("影响因素!$E$64");
    assertThat(error.getRefSheet()).isEqualTo("影响因素");
    assertThat(error.getRefRow()).isEqualTo(64);
    assertThat(error.getExistingFactorIdentity()).isEqualTo(1001L);
    assertThat(error.getNewFactorIdentity()).isEqualTo(9999L);
    assertThat(error.getReason()).contains("不一致");
  }

  @Test
  @DisplayName("append：冲突写入跳过不重复生成绑定错误")
  void appendConflictWriteSkipDoesNotDuplicateBindingError() {
    PriceItemImportResponse response = new PriceItemImportResponse();
    PriceLinkedImportResultClassifyRequest request = baseRequest();
    request.getStandardDecisions().add(decision(
        PriceLinkedStandardBindingServiceImpl.ACTION_CONFLICT, 501L, 1001L, 9999L));
    PriceLinkedAutoBindingWriteResult writeResult = new PriceLinkedAutoBindingWriteResult();
    writeResult.addConflictSkipped("材料含税价格",
        "本次公式识别结果与历史标准关系不一致，默认不覆盖，请人工确认");
    request.setWriteResult(writeResult);

    classifier.append(response, request);

    assertThat(response.getConflictBindingCount()).isEqualTo(1);
    assertThat(response.getBindingErrorCount()).isEqualTo(1);
    assertThat(response.getBindingErrors()).hasSize(1);
    assertThat(response.getBindingErrors().getFirst().getReason()).contains("不一致");
  }

  @Test
  @DisplayName("append：无公式有明确原因")
  void appendNoFormulaAddsBindingError() {
    PriceItemImportResponse response = new PriceItemImportResponse();
    PriceLinkedImportResultClassifyRequest request = baseRequest();
    request.setFormulaAvailable(false);

    classifier.append(response, request);

    assertThat(response.getBindingErrorCount()).isEqualTo(1);
    assertThat(response.getBindingErrors().getFirst().getReason()).contains("单价列无公式");
  }

  @Test
  @DisplayName("append：行号找不到有明确原因和引用位置")
  void appendUnresolvedRefAddsBindingError() {
    PriceItemImportResponse response = new PriceItemImportResponse();
    PriceLinkedImportResultClassifyRequest request = baseRequest();
    ResolvedFactorRef unresolved = new ResolvedFactorRef();
    unresolved.setSheetName("影响因素");
    unresolved.setRowNumber(44);
    unresolved.setErrorMessage("找不到影响因素引用：sheet=影响因素, row=44");
    request.getResolvedRefs().add(unresolved);

    classifier.append(response, request);

    assertThat(response.getBindingErrorCount()).isEqualTo(1);
    PriceItemImportResponse.BindingError error = response.getBindingErrors().getFirst();
    assertThat(error.getRefSheet()).isEqualTo("影响因素");
    assertThat(error.getRefRow()).isEqualTo(44);
    assertThat(error.getReason()).contains("找不到影响因素引用");
  }

  @Test
  @DisplayName("append：人工绑定未覆盖有明确原因和数量")
  void appendManualSkippedAddsCountAndBindingError() {
    PriceItemImportResponse response = new PriceItemImportResponse();
    PriceLinkedImportResultClassifyRequest request = baseRequest();
    PriceLinkedAutoBindingWriteResult writeResult = new PriceLinkedAutoBindingWriteResult();
    writeResult.addManualSkipped("材料含税价格", "当前已有 MANUAL 人工绑定，默认不覆盖");
    request.setWriteResult(writeResult);

    classifier.append(response, request);

    assertThat(response.getManualSkippedCount()).isEqualTo(1);
    assertThat(response.getBindingErrorCount()).isEqualTo(1);
    assertThat(response.getBindingErrors().getFirst().getTokenName()).isEqualTo("材料含税价格");
    assertThat(response.getBindingErrors().getFirst().getReason()).contains("MANUAL");
  }

  private PriceLinkedImportResultClassifyRequest baseRequest() {
    PriceLinkedImportResultClassifyRequest request =
        new PriceLinkedImportResultClassifyRequest();
    request.setExcelRowNumber(3);
    request.setMaterialCode("MAT-1001");
    request.setFormula("ROUND($I$2*影响因素!$E$64/1000+K2,4)/1.13");
    request.setFormulaAvailable(true);
    return request;
  }

  private StandardBindingDecision decision(
      String action, Long standardBindingId, Long oldIdentityId, Long newIdentityId) {
    StandardBindingDecision decision = new StandardBindingDecision();
    decision.setMaterialCode("MAT-1001");
    decision.setTokenName("材料含税价格");
    decision.setAction(action);
    decision.setStandardBindingId(standardBindingId);
    decision.setOldFactorIdentityId(oldIdentityId);
    decision.setNewFactorIdentityId(newIdentityId);
    decision.setReason(action.equals(PriceLinkedStandardBindingServiceImpl.ACTION_CONFLICT)
        ? "本次公式识别结果与历史标准关系不一致，默认不覆盖，请人工确认"
        : "ok");
    decision.setCandidate(candidate(newIdentityId));
    return decision;
  }

  private BindingCandidate candidate(Long factorIdentityId) {
    BindingCandidate candidate = new BindingCandidate();
    candidate.setMaterialCode("MAT-1001");
    candidate.setTokenName("材料含税价格");
    candidate.setFactorIdentityId(factorIdentityId);
    candidate.setFactorMonthlyPriceId(2001L);
    ResolvedFactorRef source = new ResolvedFactorRef();
    source.setSheetName("影响因素");
    source.setRowNumber(64);
    source.setFactorIdentityId(factorIdentityId);
    source.setFactorMonthlyPriceId(2001L);
    candidate.setSourceRef(source);
    return candidate;
  }
}
