package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MonthlyRepriceWorkerResourcesTest {
  @Test void defaultPoolsReserveFourOrdinaryQuoteThreadsAndControlConnections() {
    assertThatCode(()->MonthlyRepriceWorkerResources.verifyLimits(20,8,1,4)).doesNotThrowAnyException();
    assertThatCode(()->MonthlyRepriceWorkerResources.verifyLimits(24,8,2,4)).doesNotThrowAnyException();
  }

  @Test void increasingMonthlyConcurrencyCannotSpendQuotationCapacity() {
    assertThatThrownBy(()->MonthlyRepriceWorkerResources.verifyLimits(20,8,2,4))
        .hasMessageContaining("主连接池至少23");
    assertThatThrownBy(()->MonthlyRepriceWorkerResources.verifyLimits(100,8,5,4))
        .hasMessageContaining("月价连接池至少9");
  }

  @Test void smallPoolsMustStillReserveDispatchAndHeartbeat() {
    assertThatThrownBy(()->MonthlyRepriceWorkerResources.verifyLimits(3,8,1,0))
        .hasMessageContaining("主连接池至少8");
    assertThatCode(()->MonthlyRepriceWorkerResources.verifyLimits(8,1,1,0)).doesNotThrowAnyException();
    assertThatThrownBy(()->MonthlyRepriceWorkerResources.verifyLimits(20,8,0,4))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
