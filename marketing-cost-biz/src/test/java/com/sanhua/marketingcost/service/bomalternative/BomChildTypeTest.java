package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-02 BOM子项类型标准化")
class BomChildTypeTest {

  @Test
  @DisplayName("中文权威原值映射为内部标准和替代类型")
  void mapsAuthoritativeChineseValues() {
    assertThat(BomChildType.fromSource("标准", false))
        .isEqualTo(BomChildType.STANDARD);
    assertThat(BomChildType.fromSource("替代", true))
        .isEqualTo(BomChildType.ALTERNATIVE);
  }

  @Test
  @DisplayName("比较前统一全半角、首尾空格、连续空白和大小写")
  void normalizesWidthWhitespaceAndCase() {
    assertThat(BomChildType.fromSource("　ＳＴＡＮＤＡＲＤ　", true))
        .isEqualTo(BomChildType.STANDARD);
    assertThat(BomChildType.fromSource("\uFEFF  alternative  ", true))
        .isEqualTo(BomChildType.ALTERNATIVE);
    assertThat(BomChildType.fromSource("  普通  ", false))
        .isEqualTo(BomChildType.NORMAL);
  }

  @Test
  @DisplayName("普通位置的空值是NORMAL，替代组上下文的空值是UNKNOWN")
  void treatsBlankAccordingToGroupContext() {
    assertThat(BomChildType.fromSource(null, false)).isEqualTo(BomChildType.NORMAL);
    assertThat(BomChildType.fromSource("  ", false)).isEqualTo(BomChildType.NORMAL);
    assertThat(BomChildType.fromSource(null, true)).isEqualTo(BomChildType.UNKNOWN);
    assertThat(BomChildType.fromSource("　", true)).isEqualTo(BomChildType.UNKNOWN);
  }

  @Test
  @DisplayName("未知文本不猜标准件且不根据segment3值推断")
  void keepsUnknownValueUnknownWithoutInference() {
    assertThat(BomChildType.fromSource("候选", false)).isEqualTo(BomChildType.UNKNOWN);
    assertThat(BomChildType.fromSource("102", true)).isEqualTo(BomChildType.UNKNOWN);
    assertThat(BomChildType.fromSource("201850659", true))
        .isEqualTo(BomChildType.UNKNOWN);
    assertThat(BomChildType.fromSource("芯体部件", true))
        .isEqualTo(BomChildType.UNKNOWN);
  }
}
