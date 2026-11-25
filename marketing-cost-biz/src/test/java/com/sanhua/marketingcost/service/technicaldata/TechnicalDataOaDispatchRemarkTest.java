package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TechnicalDataOaDispatchRemarkTest {
  private static final String URL = "https://quote.example/collaboration/technical-data/forms/52";
  private static final String LABEL = "\n补录地址：";
  private static final String LINK = "<a href=\"" + URL + "\">打开补录</a>";

  @Test void escapesDescriptionAndAddressForHtml() {
    String result = TechnicalDataOaDispatchService.dispatchRemark(
        "技术补录：产品A<B>：张三（0002）补工资。", "https://quote.example/A&B/forms/52", 1);

    assertThat(result).isEqualTo("技术补录：产品A&lt;B&gt;：张三（0002）补工资。"
        + LABEL + "<a href=\"https://quote.example/A&amp;B/forms/52\">打开补录</a>");
  }

  @Test void shortensTheDescriptionAndKeepsTheFullLinkWhenHtmlWouldExceedTheLimit() {
    int available = 200 - (LABEL + LINK).length();
    String atLimit = "测".repeat(available);
    assertThat(TechnicalDataOaDispatchService.dispatchRemark(atLimit, URL, 8)).isEqualTo(atLimit + LABEL + LINK);

    assertThat(TechnicalDataOaDispatchService.dispatchRemark(atLimit + "试", URL, 8))
        .isEqualTo("技术补录：8个产品。\n" + LINK);
    assertThat(TechnicalDataOaDispatchService.dispatchRemark("测".repeat(201), URL, 18))
        .isEqualTo("技术补录：18个产品。\n" + LINK);
  }

  @Test void keepsTheLinkAloneWhenThereIsNoSpaceLeftForTheShortDescription() {
    String suffix = "x".repeat(200 - LINK.length());
    assertThat(TechnicalDataOaDispatchService.dispatchRemark("说明", URL + suffix, 1))
        .isEqualTo(LINK.replace(URL, URL + suffix))
        .hasSize(200);
  }

  @Test void usesTheOriginalPlainAddressWhenTheUrlItselfLeavesNoSpaceForHtml() {
    String longUrl = URL + "x".repeat(201 - LINK.length());
    assertThat(TechnicalDataOaDispatchService.dispatchRemark("说明", longUrl, 1))
        .isEqualTo("说明" + LABEL + longUrl);
  }

  @Test void keepsTheExistingFailureWhenEvenTheOriginalPlainAddressDoesNotFit() {
    String longUrl = URL + "x".repeat(201 - ("说明" + LABEL + URL).length());
    assertThatThrownBy(() -> TechnicalDataOaDispatchService.dispatchRemark("说明", longUrl, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("201", "200");
  }
}
