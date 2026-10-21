package com.sanhua.marketingcost.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LegacySettlementRetirementControllerTest {
  @Test void allLegacyReadAndWriteRoutesExplicitlyRetireWithoutTouchingData() throws Exception {
    var mvc = MockMvcBuilders.standaloneSetup(new PriceSettleController()).build();
    for (HttpMethod method : new HttpMethod[]{HttpMethod.GET, HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE}) {
      for (String path : new String[]{"", "/1", "/import", "/1/items", "/items/1"}) {
        mvc.perform(request(method, "/api/v1/price-settle" + path))
            .andExpect(status().isGone()).andExpect(jsonPath("$.code").value(410))
            .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("新结算价格")));
      }
    }
  }
}
