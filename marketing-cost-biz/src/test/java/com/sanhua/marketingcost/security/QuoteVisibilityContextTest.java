package com.sanhua.marketingcost.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActor;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class QuoteVisibilityContextTest {
  @AfterEach void clear() { QuoteVisibilityContext.clear(); }
  @Test void requestScopeCannotEscapeThroughTechnicalAdminPermission() {
    QuoteVisibilityContext.bind(QuoteVisibilityContext.Scope.PLATE, id -> id == 20);
    var actor = new TechnicalDataActor(11L,"报价员",Set.of("technical:data:admin:operate"));
    var task = new QuoteTechTask();task.setOaFormId(6L);task.setActiveFlag(1);task.setTaskStatus("UNASSIGNED");
    assertThat(actor.admin()).isTrue();
    assertThat(actor.canReadTask(task)).isFalse();
    assertThat(actor.canCoordinateTask(task)).isFalse();
    assertThat(actor.canFillUnassigned(task)).isFalse();
    task.setOaFormId(20L);
    assertThat(actor.canReadTask(task)).isTrue();
    assertThat(actor.canCoordinateTask(task)).isTrue();
    assertThat(actor.canFillUnassigned(task)).isTrue();
  }
  @Test void oaBoundActorStillCannotAccessAnotherForm() {
    var actor=new TechnicalDataActor(11L,"OA办理人",Set.of("technical:data:task:list"),20L);
    assertThat(actor.canAccessForm(20L)).isTrue();assertThat(actor.canAccessForm(6L)).isFalse();
  }
  @Test void completedAndFailedRequestsAlwaysReleaseTheirScope() throws Exception {
    var policy=mock(QuoteVisibilityPolicy.class);
    when(policy.resolve(any())).thenReturn(QuoteVisibilityContext.Scope.PLATE);
    var filter=new QuoteVisibilityFilter(policy);
    for(boolean failure: new boolean[]{false,true}) {
      var request=new MockHttpServletRequest();request.setServletPath("/api/v1/quote-requests");var response=new MockHttpServletResponse();
      try {
        filter.doFilter(request,response,(req,res)-> {
          assertThat(QuoteVisibilityContext.scope()).isEqualTo(QuoteVisibilityContext.Scope.PLATE);
          if(failure) throw new IllegalArgumentException("test failure");
        });
        assertThat(failure).isFalse();
      } catch(IllegalArgumentException ex) { assertThat(failure).isTrue(); }
      assertThat(QuoteVisibilityContext.scope()).isEqualTo(QuoteVisibilityContext.Scope.ALL);
      assertThat(QuoteVisibilityContext.canAccessForm(6L)).isTrue();
    }
  }
  @Test void integrationCallbacksDoNotInheritInternalQuotationScope() throws Exception {
    var policy=mock(QuoteVisibilityPolicy.class);
    var request=new MockHttpServletRequest();request.setServletPath("/integration/v1/oa/technical-callback");
    new QuoteVisibilityFilter(policy).doFilter(request,new MockHttpServletResponse(),(req,res)-> {
      assertThat(QuoteVisibilityContext.scope()).isEqualTo(QuoteVisibilityContext.Scope.ALL);
    });
    verifyNoInteractions(policy);
  }

}
