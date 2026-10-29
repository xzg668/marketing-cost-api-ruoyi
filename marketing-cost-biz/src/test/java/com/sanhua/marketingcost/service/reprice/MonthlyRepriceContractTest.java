package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceContract.*;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class MonthlyRepriceContractTest {
  @Test void everyLegalAndIllegalStateTransitionIsExplicit() {
    var allowed = java.util.Set.of("QUEUED:RUNNING","QUEUED:FAILED","RUNNING:CHECKING","RUNNING:FAILED",
        "CHECKING:WAIT_CONFIRM","CHECKING:FAILED","WAIT_CONFIRM:CONFIRMED","WAIT_CONFIRM:FAILED",
        "FAILED:QUEUED","FAILED:CHECKING");
    for (var from : State.values()) for (var to : State.values()) {
      if (allowed.contains(from+":"+to)) assertThatCode(() -> from.requireTransition(to)).doesNotThrowAnyException();
      else assertThatThrownBy(() -> from.requireTransition(to)).isInstanceOf(IllegalStateException.class);
    }
  }
  @Test void onlyDirectorConfirmationReleasesTheStateContract() {
    for (var state : State.values()) assertThat(state.blocksQuotation()).isEqualTo(state != State.CONFIRMED);
  }
  @Test void sameMaterialDifferentActualProductRowOrParentPathRemainsDistinct() {
    var first = new ObjectIdentity("COMMERCIAL","OA1",1);
    var second = new ObjectIdentity("COMMERCIAL","OA1",2);
    assertThat(first.key()).isNotEqualTo(second.key());
    var a = new LineIdentity(first,"root/a/x","root/a","00001");
    var b = new LineIdentity(first,"root/b/x","root/b","00001");
    assertThat(a.key()).isNotEqualTo(b.key());
    assertThat(a.versionKey(YearMonth.of(2025,12),10)).isNotEqualTo(a.versionKey(YearMonth.of(2026,1),10));
    assertThat(a.versionKey(YearMonth.of(2026,1),10)).isNotEqualTo(a.versionKey(YearMonth.of(2026,1),11));
  }
  @Test void identitiesRejectMissingRealCoordinatesAndDoNotNormalizeLeadingZeros() {
    assertThatThrownBy(() -> new ObjectIdentity("COMMERCIAL","OA",0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ObjectIdentity("ADMIN","OA",1)).isInstanceOf(IllegalArgumentException.class);
    var object = new ObjectIdentity("COMMERCIAL","000OA",1);
    assertThat(new LineIdentity(object,"a",null,"001").key()).isNotEqualTo(new LineIdentity(object,"a",null,"1").key());
    assertThatThrownBy(() -> new Source(object,0)).isInstanceOf(IllegalArgumentException.class);
  }
}
