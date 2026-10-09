package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SupplierPriceReviewWorkflowTest {
  private final QuotePricePrepareWorkbenchService prices = mock(QuotePricePrepareWorkbenchService.class);
  private final SupplierPriceDecisionMapper decisions = mock(SupplierPriceDecisionMapper.class);
  private final OaFormItemMapper items = mock(OaFormItemMapper.class);
  private final SupplierPriceReviewWorkflow workflow = new SupplierPriceReviewWorkflow(prices, decisions, items);
  private final String month = CostPricingPeriodUtils.currentPricingMonth();
  private final LocalDate date = CostPricingPeriodUtils.currentPricingDate();
  private final SupplierPriceScope scope = new SupplierPriceScope("COMMERCIAL", "OA-1", 1L, month, date,"210","MAT","FIXED");
  private final SupplierPriceReview review = new SupplierPriceReview("scope", "fingerprint", scope, List.of(), "PENDING",null,null,"待确认");
  @BeforeAll static void metadata() {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""), OaFormItem.class);
  }
  @BeforeEach void setup() {
    var auth = new UsernamePasswordAuthenticationToken("alice", "", List.of());
    auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
    when(items.selectOne(any(Wrapper.class))).thenReturn(new OaFormItem());
    when(prices.checkPriceSources(eq("OA-1"),eq(1L),any())).thenReturn(response(review));
  }
  @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
  @Test void yesRecordsWaitingAndNoRecordsFallback() {
    var yes=workflow.confirm("OA-1",1L,request(true,"fingerprint"),"alice");
    assertThat(yes.getDecision()).isEqualTo("WAIT_IMPORT");
    assertThat(yes.getPricingDate()).isEqualTo(date);
    var no=workflow.confirm("OA-1",1L,request(false,"fingerprint"),"alice");
    assertThat(no.getDecision()).isEqualTo("FALLBACK_HIGH");
    assertThat(no.getConfirmedBy()).isEqualTo("alice");
    verify(decisions,times(2)).insert(any(SupplierPriceDecision.class));
  }
  @Test void missingChoiceCannotDefaultToNo() {
    assertThatThrownBy(() -> workflow.confirm("OA-1",1L,request(null,"fingerprint"),"alice")).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(decisions);
  }
  @Test void staleCandidateSnapshotRequiresFreshConfirmation() {
    assertThatThrownBy(() -> workflow.confirm("OA-1",1L,request(false,"old"),"alice")).hasMessageContaining("已变化");
    verify(decisions,never()).insert(any(SupplierPriceDecision.class));
  }
  @Test void confirmationCannotCrossBusinessUnits() {
    var auth=new UsernamePasswordAuthenticationToken("other","",List.of());
    auth.setDetails(Map.of("businessUnitType","HOUSEHOLD"));
    SecurityContextHolder.getContext().setAuthentication(auth);
    assertThatThrownBy(() -> workflow.confirm("OA-1",1L,request(false,"fingerprint"),"other")).hasMessageContaining("其他业务单元");
    verify(decisions,never()).insert(any(SupplierPriceDecision.class));
  }
  @Test void duplicateSubmissionKeepsOneAuditRow() {
    var previous=new SupplierPriceDecision();previous.setDecision("WAIT_IMPORT");previous.setFingerprint("fingerprint");
    when(decisions.latest("scope")).thenReturn(previous);
    assertThat(workflow.confirm("OA-1",1L,request(true,"fingerprint"),"alice")).isSameAs(previous);
    verify(decisions,never()).insert(any(SupplierPriceDecision.class));
  }
  @Test void importClosesEveryResolvedMaterialForTheProduct() {
    var first=decision(1L,"scope");
    var second=decision(2L,"other-scope");
    when(decisions.waiting("COMMERCIAL",month)).thenReturn(List.of(first,second));
    when(prices.generate(eq("OA-1"),eq(1L),any())).thenReturn(response());
    workflow.reprice(first);
    assertThat(first.getRetryStatus()).isEqualTo("RESOLVED");
    assertThat(second.getRetryStatus()).isEqualTo("RESOLVED");
    verify(prices,times(1)).generate(eq("OA-1"),eq(1L),any());
  }
  private SupplierPriceReviewWorkflow.Confirmation request(Boolean has,String fingerprint) {
    return new SupplierPriceReviewWorkflow.Confirmation(month,"scope",fingerprint,has,date);
  }
  private SupplierPriceDecision decision(long id,String key) {
    var d=new SupplierPriceDecision();d.setId(id);d.setScopeKey(key);d.setBusinessUnitType("COMMERCIAL");d.setOaNo("OA-1");d.setOaFormItemId(1L);d.setPeriodMonth(month);d.setPricingDate(date);return d;
  }
  private QuotePricePrepareWorkbenchResponse response(SupplierPriceReview... reviews) {
    var r=new QuotePricePrepareWorkbenchResponse();r.setSupplierPriceReviews(List.of(reviews));return r;
  }
}
