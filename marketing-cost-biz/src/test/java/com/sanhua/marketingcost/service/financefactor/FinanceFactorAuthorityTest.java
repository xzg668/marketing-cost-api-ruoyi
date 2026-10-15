package com.sanhua.marketingcost.service.financefactor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.impl.*;
import org.junit.jupiter.api.Test;

class FinanceFactorAuthorityTest {
  private final FactorIdentityMapper identities = mock(FactorIdentityMapper.class);
  private final FactorMonthlyPriceMapper prices = mock(FactorMonthlyPriceMapper.class);
  private final FinanceFactorAuthority authority =
      new FinanceFactorAuthority(
          identities,
          prices,
          new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));

  @Test
  void publicCopperIsNotAUserDefinedTechnicalPrice() {
    assertThat(authority.manages("COMMERCIAL", "平均价", "cu")).isTrue();
    assertThat(authority.manages("COMMERCIAL", "平均价", "1#Cu")).isTrue();
    assertThat(authority.manages("COMMERCIAL", "平均价", "Pcu")).isFalse();
    assertThatThrownBy(() -> authority.requirePrivate(null)).hasMessageContaining("身份不存在");
  }

  @Test
  void publishedKeysRetainAuthorityButPrivateKeysAndOtherUnitsStayIndependent() {
    when(prices.countFinanceManagedKey("COMMERCIAL", "AVG|1#ZN")).thenReturn(1L);
    assertThat(authority.manages("COMMERCIAL", "平均价", "1#Zn")).isTrue();
    assertThat(authority.manages("OTHER", "平均价", "1#Zn")).isFalse();
    assertThat(authority.manages("COMMERCIAL", "现货价", "1#Zn")).isFalse();
  }

}
