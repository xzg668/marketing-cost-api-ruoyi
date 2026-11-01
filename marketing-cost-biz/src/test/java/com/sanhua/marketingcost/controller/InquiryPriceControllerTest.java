package com.sanhua.marketingcost.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sanhua.marketingcost.config.GlobalExceptionHandler;
import com.sanhua.marketingcost.service.pricing.inquiry.InquiryPriceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class InquiryPriceControllerTest {
  @Test
  void scopedMissingSourceReturns404EvenWithTheGlobalFallbackHandler() throws Exception {
    var service =
        mock(InquiryPriceService.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    when(service.batch(any(), eq(7L)))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "询价导入记录不存在"));
    when(service.original(any(), eq(7L)))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "询价导入记录不存在"));
    var mvc =
        MockMvcBuilders.standaloneSetup(new InquiryPriceController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    for (String suffix : new String[] {"", "/original"})
      mvc.perform(get("/api/v1/inquiry-prices/imports/7" + suffix))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(404))
          .andExpect(jsonPath("$.msg").value("询价导入记录不存在"));
  }
}
