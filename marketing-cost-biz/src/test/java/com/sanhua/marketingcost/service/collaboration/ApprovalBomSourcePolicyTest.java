package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.service.collaboration.scan.CurrentU9BomResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationCurrentU9BomGateway;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("电子图库补录 BOM 审核前 U9 优先策略")
class ApprovalBomSourcePolicyTest {
  private final QuoteCollaborationCurrentU9BomGateway gateway =
      mock(QuoteCollaborationCurrentU9BomGateway.class);
  private final ApprovalBomSourcePolicy policy = new ApprovalBomSourcePolicy(gateway);

  @Test
  void allLinksHaveU9SoSupplementIsNotPublished() {
    when(gateway.read(any())).thenReturn(CurrentU9BomResult.available("U9", "V2", null, 8));
    var result = policy.inspect(product(), List.of(link(1L, "2026-08"), link(2L, "2026-09")));
    assertThat(result.supplementRequired()).isFalse();
    assertThat(result.links()).hasSize(2).allSatisfy(row ->
        assertThat(row.useSupplementBom()).isFalse());
  }

  @Test
  void explicitNotFoundUsesSupplementForThatQuote() {
    when(gateway.read(any()))
        .thenReturn(CurrentU9BomResult.available("U9", "V2", null, 8))
        .thenReturn(CurrentU9BomResult.notFound("无BOM"));
    var result = policy.inspect(product(), List.of(link(1L, "2026-08"), link(2L, "2026-09")));
    assertThat(result.supplementRequired()).isTrue();
    assertThat(result.links()).extracting(ApprovalBomSourcePolicy.LinkDecision::useSupplementBom)
        .containsExactly(false, true);
  }

  @Test
  void timeoutNeverFallsBackToSupplement() {
    when(gateway.read(any())).thenReturn(CurrentU9BomResult.timeout("U9超时"));
    assertThatThrownBy(() -> policy.inspect(product(), List.of(link(1L, "2026-08"))))
        .isInstanceOf(CollaborationDomainException.class)
        .hasMessageContaining("U9超时");
  }

  @Test
  void emptyNeverFallsBackToSupplement() {
    when(gateway.read(any())).thenReturn(CurrentU9BomResult.dataEmpty("U9空数据"));
    assertThatThrownBy(() -> policy.inspect(product(), List.of(link(1L, "2026-08"))))
        .isInstanceOf(CollaborationDomainException.class)
        .hasMessageContaining("U9空数据");
  }

  @Test
  void organizationMismatchNeverFallsBackToSupplement() {
    when(gateway.read(any())).thenReturn(CurrentU9BomResult.organizationMismatch("组织不匹配"));
    assertThatThrownBy(() -> policy.inspect(product(), List.of(link(1L, "2026-08"))))
        .isInstanceOf(CollaborationDomainException.class)
        .hasMessageContaining("组织不匹配");
  }

  private QuoteCollaborationProductTask product() {
    QuoteCollaborationProductTask value = new QuoteCollaborationProductTask();
    value.setId(10L);
    value.setPrimaryScope("FULL_BOM");
    value.setProductCode("P-1");
    value.setProductName("产品");
    value.setProductSpec("S");
    value.setProductModel("M");
    value.setBusinessUnitType("COMMERCIAL");
    value.setApplicableOrgCode("210");
    value.setPriceOrgCode("210");
    value.setMaterialOrgCode("COMMERCIAL");
    return value;
  }

  private QuoteCollaborationQuoteLink link(Long id, String month) {
    QuoteCollaborationQuoteLink value = new QuoteCollaborationQuoteLink();
    value.setId(id);
    value.setOaFormId(20L + id);
    value.setOaFormItemId(30L + id);
    value.setOaNo("OA-" + id);
    value.setAccountingMonth(month);
    value.setActiveFlag(1);
    return value;
  }
}
