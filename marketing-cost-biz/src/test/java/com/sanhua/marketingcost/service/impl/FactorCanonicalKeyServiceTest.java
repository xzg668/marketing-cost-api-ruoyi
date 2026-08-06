package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-05 统一因素键")
class FactorCanonicalKeyServiceTest {

  private final FactorCanonicalKeyServiceImpl service =
      new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl());

  @Test
  @DisplayName("平均价和 Cu 简称生成 AVG|1#CU")
  void buildsCuAverageKey() {
    assertThat(service.build("平均价", "1#Cu")).isEqualTo("AVG|1#CU");
  }

  @Test
  @DisplayName("平均价和 Zn 简称生成 AVG|1#ZN")
  void buildsZnAverageKey() {
    assertThat(service.build("平均价", "1#Zn")).isEqualTo("AVG|1#ZN");
  }

  @Test
  @DisplayName("全角、大小写和空格不影响统一因素键")
  void normalizesWidthCaseAndWhitespace() {
    assertThat(service.build(" ＡＶＧ ", "１ ＃ ｃｕ "))
        .isEqualTo("AVG|1#CU");
  }

  @Test
  @DisplayName("现有统一因素键也按相同规则标准化")
  void normalizesPersistedKey() {
    assertThat(service.normalizeExistingKey(" average | １＃ｚｎ "))
        .isEqualTo("AVG|1#ZN");
  }

  @Test
  @DisplayName("未知取价来源保留其标准化文本而不是猜数据库身份")
  void keepsUnknownPriceSourceDynamic() {
    assertThat(service.build("供应商季度价", "ABC"))
        .isEqualTo("供应商季度价|ABC");
  }

  @Test
  @DisplayName("简称或取价来源为空时不生成不完整键")
  void rejectsIncompleteKey() {
    assertThat(service.build("平均价", " ")).isEmpty();
    assertThat(service.build(null, "1#Cu")).isEmpty();
  }
}
