package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSaveResult;
import com.sanhua.marketingcost.dto.PriceLinkedImportCommand;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2StandardRow;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("PLI2-10 类型2确认导入编排")
class PriceLinkedType2ImportOrchestratorTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("全部成功时依次写因素月价、联动价版本、绑定和快照")
  void importsCompleteType2Workbook() {
    PriceLinkedType2ImportTestSupport support = new PriceLinkedType2ImportTestSupport();
    PriceLinkedImportCommand command = confirmedCommand(support);

    PriceItemImportResponse response = support.orchestrator.confirm(command);

    assertThat(response.getTemplateType()).isEqualTo("TYPE2");
    assertThat(response.getImportStatus()).isEqualTo("SUCCESS");
    assertThat(response.getFactorUploadBatchId()).isEqualTo(9001L);
    assertThat(response.getFactorRecognizedCount()).isOne();
    assertThat(response.getLinkedCount()).isOne();
    assertThat(response.getLinkedCreatedCount()).isOne();
    assertThat(response.getAutoBindingCount()).isOne();
    assertThat(response.getErrors()).isEmpty();
    assertThat(support.savedRequests).singleElement().satisfies(saved -> {
      assertThat(saved.getSourceUploadBatchId()).isEqualTo(9001L);
      assertThat(saved.getCandidateVersion().getMaterialName())
          .isEqualTo("测试产品-MAT-1");
      assertThat(saved.getCandidateVersion().getSpecModel()).isEqualTo("TYPE2-SPEC");
      assertThat(saved.getCandidateVersion().getBusinessUnitType())
          .isEqualTo("COMMERCIAL");
      assertThat(saved.getFactorMonthlyPriceIds()).containsEntry(191L, 6191L);
    });
    verify(support.batchMapper).updateById(any(FactorUploadBatch.class));
  }

  @Test
  @DisplayName("普通行公式失败时其他独立行继续，批次为部分成功")
  void continuesIndependentRowsWhenOneFormulaFails() {
    PriceLinkedType2ImportTestSupport support = new PriceLinkedType2ImportTestSupport();
    List<PriceLinkedType2ProductRow> products = List.of(
        support.product(6, "MAT-1", "供应商甲", "$E$2+G6"),
        support.product(7, "MAT-2", "供应商乙", "SUM(G7)"));
    List<PriceLinkedType2StandardRow> standards = List.of(
        support.standard(6, "MAT-1", "供应商甲", "SUP-A"),
        support.standard(7, "MAT-2", "供应商乙", "SUP-B"));
    support.useWorkbook(support.workbook(
        products, standards, List.of(support.copper()), List.of()));

    PriceItemImportResponse response =
        support.orchestrator.confirm(confirmedCommand(support));

    assertThat(response.getImportStatus()).isEqualTo("PARTIAL");
    assertThat(response.getBusinessRowCount()).isEqualTo(2);
    assertThat(response.getLinkedCount()).isOne();
    assertThat(response.getFormulaMismatchCount()).isOne();
    assertThat(response.getErrors()).anySatisfy(error -> {
      assertThat(error.getMaterialCode()).isEqualTo("MAT-2");
      assertThat(error.getErrorStage()).isEqualTo("ROW_VALIDATION");
    });
    assertThat(support.savedRequests).hasSize(1);
  }

  @Test
  @DisplayName("因素价格冲突时依赖行不入库且无任何确认写入")
  void blocksRowsDependingOnConflictingFactor() {
    PriceLinkedType2ImportTestSupport support = new PriceLinkedType2ImportTestSupport();
    when(support.factorResolver.resolve(anyList(), anyString(), anyString()))
        .thenReturn(List.of(support.conflictResolution(support.copper())));

    PriceItemImportResponse response =
        support.orchestrator.confirm(confirmedCommand(support));

    assertThat(response.getImportStatus()).isEqualTo("FAILED");
    assertThat(response.getCanonicalFactorConflictCount()).isPositive();
    assertThat(response.getLinkedCount()).isZero();
    assertThat(response.getErrors()).anySatisfy(error ->
        assertThat(error.getErrorCode()).isEqualTo("FACTOR_DEPENDENCY_BLOCKED"));
    verify(support.batchService, never()).createFactorBatch(any());
    verify(support.factorUpsertService, never())
        .upsert(any(), anyString(), anyString(), anyString(), any(), anyString());
    verify(support.importBasisService, never()).save(any());
  }

  @Test
  @DisplayName("单行公式无法转换时明确失败且不创建批次")
  void formulaConversionFailureDoesNotWrite() {
    PriceLinkedType2ImportTestSupport support = new PriceLinkedType2ImportTestSupport();
    support.useWorkbook(support.workbook(
        List.of(support.product(6, "MAT-1", "供应商甲", "SUM(G6)")),
        List.of(support.standard(6, "MAT-1", "供应商甲", "SUP-A")),
        List.of(support.copper()),
        List.of()));

    PriceItemImportResponse response =
        support.orchestrator.confirm(confirmedCommand(support));

    assertThat(response.getImportStatus()).isEqualTo("FAILED");
    assertThat(response.getFormulaConvertedCount()).isZero();
    assertThat(response.getErrors()).anySatisfy(error ->
        assertThat(error.getErrorStage()).isEqualTo("ROW_VALIDATION"));
    verify(support.batchService, never()).createFactorBatch(any());
  }

  @Test
  @DisplayName("重复导入由版本服务判定幂等，不创建第二个公式版本或覆盖绑定")
  void duplicateImportIsIdempotent() {
    PriceLinkedType2ImportTestSupport support = new PriceLinkedType2ImportTestSupport();
    when(support.importBasisService.save(any()))
        .thenReturn(new PriceLinkedImportBasisSaveResult(
            PriceLinkedImportBasisSaveResult.ACTION_DUPLICATE_SKIPPED,
            701L,
            701L,
            1));

    PriceItemImportResponse first =
        support.orchestrator.confirm(confirmedCommand(support));
    PriceItemImportResponse second =
        support.orchestrator.confirm(confirmedCommand(support));

    assertThat(first.getLinkedCount()).isZero();
    assertThat(second.getLinkedCount()).isZero();
    assertThat(first.getLinkedUnchangedSkippedCount()).isOne();
    assertThat(second.getLinkedUnchangedSkippedCount()).isOne();
    assertThat(first.getAutoBindingCount()).isZero();
    assertThat(second.getAutoBindingCount()).isZero();
  }

  @Test
  @DisplayName("同料号不同供应商代码分别保存为两个业务身份")
  void sameMaterialDifferentSuppliersStayIndependent() {
    PriceLinkedType2ImportTestSupport support = new PriceLinkedType2ImportTestSupport();
    support.useWorkbook(support.workbook(
        List.of(
            support.product(6, "MAT-X", "供应商甲", "$E$2+G6"),
            support.product(7, "MAT-X", "供应商乙", "$E$2+G7")),
        List.of(
            support.standard(6, "MAT-X", "供应商甲", "SUP-A"),
            support.standard(7, "MAT-X", "供应商乙", "SUP-B")),
        List.of(support.copper()),
        List.of()));

    PriceItemImportResponse response =
        support.orchestrator.confirm(confirmedCommand(support));

    assertThat(response.getLinkedCount()).isEqualTo(2);
    assertThat(support.savedRequests)
        .extracting(request -> request.getCandidateVersion().getSupplierCode())
        .containsExactly("SUP-A", "SUP-B");
  }

  @Test
  @DisplayName("普通用户不能为其他业务单元预检或确认")
  void enforcesBusinessUnitIsolation() {
    PriceLinkedType2ImportTestSupport support = new PriceLinkedType2ImportTestSupport();
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            "tester", "n/a", List.of(new SimpleGrantedAuthority("price:linked-item:import")));
    authentication.setDetails(Map.of(
        BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    byte[] bytes = {1, 2, 3, 4};
    PriceLinkedImportCommand command =
        support.command(bytes, support.hash(bytes), "HOUSEHOLD", false);

    assertThatThrownBy(() -> support.orchestrator.confirm(command))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("其他业务单元");
    verify(support.batchService, never()).createFactorBatch(any());
  }

  @Test
  @DisplayName("默认不走旧手工绑定覆盖入口，重复版本的人工绑定保持原样")
  void doesNotOverwriteManualBindingByDefault() {
    PriceLinkedType2ImportTestSupport support = new PriceLinkedType2ImportTestSupport();
    when(support.importBasisService.save(any()))
        .thenReturn(new PriceLinkedImportBasisSaveResult(
            PriceLinkedImportBasisSaveResult.ACTION_DUPLICATE_SKIPPED,
            702L,
            702L,
            1));

    PriceItemImportResponse response =
        support.orchestrator.confirm(confirmedCommand(support));

    assertThat(response.getAutoBindingCount()).isZero();
    assertThat(response.getLinkedUnchangedSkippedCount()).isOne();
    assertThat(response.getLinkedExpiredCount()).isZero();
  }

  private PriceLinkedImportCommand confirmedCommand(
      PriceLinkedType2ImportTestSupport support) {
    byte[] bytes = {1, 2, 3, 4};
    return support.command(bytes, support.hash(bytes), "COMMERCIAL", false);
  }
}
