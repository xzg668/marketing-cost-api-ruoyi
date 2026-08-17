package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-09 技术提交统一完整性门禁")
class TechnicalTaskValidatorTest {
  private final QuotePriceDraftRepository draftRepository = mock(QuotePriceDraftRepository.class);
  private final TechnicalTaskValidator validator = new TechnicalTaskValidator(draftRepository);

  @Test
  void reportsBomPackageAndEveryOpenPriceGap() {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setNeedBom(1);
    task.setNeedPackage(1);
    task.setNeedPrice(1);
    QuoteCollaborationGap price = priceGap("OPEN", "紫铜管");

    assertThat(validator.validate(task, List.of(price)))
        .extracting(issue -> issue.code())
        .containsExactly("BOM_DRAFT_MISSING", "PACKAGE_RESULT_MISSING", "PRICE_GAP_OPEN");
  }

  @Test
  void passesOnlyWithVerifiedBomPackageAndResolvedPriceRows() {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setNeedBom(1);
    task.setSupplementVersionId(88L);
    task.setElectronicBomFingerprint("fingerprint");
    task.setNeedPackage(1);
    task.setPackageReferenceId(99L);
    task.setNeedPrice(1);

    assertThat(validator.validate(task, List.of(priceGap("RESOLVED", "紫铜管"))))
        .isEmpty();
  }

  @Test
  void draftReadyCountsOnlyWhenCurrentDraftIsValidatedForTheSameTask() {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(7L);
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("210");
    task.setNeedPrice(1);
    QuoteCollaborationGap gap = priceGap("DRAFT_READY", "紫铜管");
    gap.setCurrentPriceDraftId(9L);
    QuotePriceDraft draft = new QuotePriceDraft();
    draft.setProductTaskId(7L);
    draft.setDraftStatus("VALIDATED");
    draft.setValidationStatus("PASSED");
    when(draftRepository.findById(9L, new CollaborationScope("COMMERCIAL", "210")))
        .thenReturn(Optional.of(draft));

    assertThat(validator.validate(task, List.of(gap))).isEmpty();
    draft.setValidationStatus("FAILED");
    assertThat(validator.validate(task, List.of(gap)))
        .extracting(issue -> issue.code()).containsExactly("PRICE_GAP_OPEN");
  }

  private static QuoteCollaborationGap priceGap(String status, String materialName) {
    QuoteCollaborationGap gap = new QuoteCollaborationGap();
    gap.setGapCategory("PRICE");
    gap.setGapStatus(status);
    gap.setMaterialName(materialName);
    return gap;
  }
}
