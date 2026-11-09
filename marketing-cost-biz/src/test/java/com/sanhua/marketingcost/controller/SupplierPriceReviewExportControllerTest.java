package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.service.pricing.SupplierPriceReviewExportService;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReviewWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;

class SupplierPriceReviewExportControllerTest {
  @Test void usesExistingReadPermissionAndOnlyReturnsReadOnlyExportBytes() throws Exception {
    var workflow = mock(SupplierPriceReviewWorkflow.class);
    var exports = mock(SupplierPriceReviewExportService.class);
    when(exports.export("OA", 1L, "2026-11")).thenReturn(new byte[] {1, 2, 3});
    var controller = new SupplierPriceReviewController(workflow, exports);
    var response = new MockHttpServletResponse();
    controller.export("OA", 1L, "2026-11", response);
    assertThat(response.getContentAsByteArray()).containsExactly(1, 2, 3);
    assertThat(response.getContentType()).contains("spreadsheetml.sheet");
    assertThat(response.getHeader("Content-Disposition")).contains("filename*=UTF-8''").endsWith(".xlsx");
    assertThat(SupplierPriceReviewController.class.getMethod("export", String.class, Long.class,
        String.class, jakarta.servlet.http.HttpServletResponse.class).getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('ingest:quote:list')");
    verifyNoInteractions(workflow);
  }
}
