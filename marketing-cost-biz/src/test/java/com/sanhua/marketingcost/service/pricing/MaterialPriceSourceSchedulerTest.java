package com.sanhua.marketingcost.service.pricing;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.scheduler.MaterialPriceSourceScheduler;
import org.junit.jupiter.api.Test;
class MaterialPriceSourceSchedulerTest {
  @Test void disabledJobDoesNotGenerateData() {
    var refresh=mock(MaterialPriceSourceRefresh.class);
    new MaterialPriceSourceScheduler(refresh,false).update();
    verifyNoInteractions(refresh);
  }
  @Test void oneUnitFailureDoesNotPreventTheOtherUnitRefresh() {
    var refresh=mock(MaterialPriceSourceRefresh.class);
    when(refresh.refresh("COMMERCIAL","SCHEDULED_0400")).thenThrow(new IllegalStateException("source unavailable"));
    new MaterialPriceSourceScheduler(refresh,true).update();
    verify(refresh).refresh("COMMERCIAL","SCHEDULED_0400");
    verify(refresh).refresh("HOUSEHOLD","SCHEDULED_0400");
  }
}
