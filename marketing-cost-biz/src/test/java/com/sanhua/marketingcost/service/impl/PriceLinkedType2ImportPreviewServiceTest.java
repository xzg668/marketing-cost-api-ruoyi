package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sanhua.marketingcost.dto.PriceLinkedType2ImportPreviewResponse;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("PLI2-10 类型2导入只读预检")
class PriceLinkedType2ImportPreviewServiceTest {

  @Test
  @DisplayName("预检完成解析、匹配、因素复用、公式和税对账但零写入")
  void previewBuildsCompleteResultWithoutWrites() {
    PriceLinkedType2ImportTestSupport support = new PriceLinkedType2ImportTestSupport();

    PriceLinkedType2ImportPreviewResponse response =
        support.orchestrator.preview(support.command(null));

    assertThat(response.getTemplateType()).isEqualTo("TYPE2");
    assertThat(response.getFileSha256()).hasSize(64);
    assertThat(response.isCanConfirm()).isTrue();
    assertThat(response.getBusinessSheetName()).isEqualTo("Sheet1");
    assertThat(response.getImportDataSheetName()).isEqualTo("importdata1");
    assertThat(response.getFactorRowCount()).isOne();
    assertThat(response.getMatchedRowCount()).isOne();
    assertThat(response.getFormulaConvertedCount()).isOne();
    assertThat(response.getCanonicalFactorReusedCount()).isOne();
    assertThat(response.getRows()).singleElement().satisfies(row -> {
      assertThat(row.isImportable()).isTrue();
      assertThat(row.getSourceFormula()).isEqualTo("$E$2+G6");
      assertThat(row.getSystemFormula()).isEqualTo("[factor_identity_191]+23");
      assertThat(row.getSystemTaxIncludedPrice()).isEqualByComparingTo("113");
      assertThat(row.getSystemTaxExcludedPrice()).isEqualByComparingTo("100");
    });
    verifyNoInteractions(
        support.factorUpsertService,
        support.importBasisService,
        support.batchService,
        support.batchMapper);
  }

  @Test
  @DisplayName("同一文件每次预检得到相同SHA-256")
  void previewHashIsDeterministic() {
    PriceLinkedType2ImportTestSupport support = new PriceLinkedType2ImportTestSupport();

    String first = support.orchestrator.preview(support.command(null)).getFileSha256();
    String second = support.orchestrator.preview(support.command(null)).getFileSha256();

    assertThat(second).isEqualTo(first);
  }

  @Test
  @DisplayName("预检入口声明只读事务")
  void previewDeclaresReadOnlyTransaction() throws Exception {
    Method method = PriceLinkedType2ImportOrchestratorImpl.class.getMethod(
        "preview", com.sanhua.marketingcost.dto.PriceLinkedImportCommand.class);
    Transactional annotation = method.getAnnotation(Transactional.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.readOnly()).isTrue();
  }
}
