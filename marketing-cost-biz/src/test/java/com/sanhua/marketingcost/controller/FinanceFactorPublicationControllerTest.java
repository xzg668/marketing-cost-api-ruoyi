package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.financefactor.*;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class FinanceFactorPublicationControllerTest {
  private AnnotationConfigApplicationContext context;
  private FinanceFactorPublicationController controller;
  private FinanceFactorPublicationService publisher;
  private FinanceFactorPriceReader reader;
  private FinanceFactorCuAdjustmentService copper;
  private final MockMultipartFile file =
      new MockMultipartFile("file", "基价.xlsx", "application/octet-stream", new byte[] {1, 2});

  @BeforeEach
  void setup() {
    context = new AnnotationConfigApplicationContext(Config.class);
    controller = context.getBean(FinanceFactorPublicationController.class);
    publisher = context.getBean(FinanceFactorPublicationService.class);
    reader = context.getBean(FinanceFactorPriceReader.class);
    copper = context.getBean(FinanceFactorCuAdjustmentService.class);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    context.close();
  }

  @Test
  void readPermissionCannotImportOrAdjust() {
    authenticate("price:finance-base:list");
    assertThat(controller.read("2026-10", "210", null, Condition.GENERAL, null).isSuccess())
        .isTrue();
    assertThatThrownBy(() -> controller.preview(file, "2026-10", "210", null, null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> controller.publish(file, "2026-10", "210", null, null, "hash", () -> "finance"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.previewCu(1L, adjustment()))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.publishCu(1L, adjustment(), () -> "finance"))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(publisher, copper);
  }

  @Test
  void serverBusinessScopeFileAndFingerprintAreForwarded() throws Exception {
    authenticate("price:finance-base:import");
    controller.preview(file, "2026-10", "220", "Sheet1", "BOARD");
    var scope = new FinanceFactorImportContext("HOUSEHOLD", "220", "2026-10", "Sheet1", "BOARD");
    verify(publisher).preview(aryEq(new byte[] {1, 2}), eq("基价.xlsx"), eq(scope));
    controller.publish(
        file, "2026-10", "220", "Sheet1", "BOARD", "checked", () -> "financial.user");
    verify(publisher)
        .publish(
            aryEq(new byte[] {1, 2}),
            eq("基价.xlsx"),
            eq(scope),
            eq("checked"),
            eq("financial.user"));
    assertThatThrownBy(
            () -> controller.publish(file, "2026-10", "220", null, "BOARD", "checked", null))
        .hasMessageContaining("登录");
  }

  @Test
  void copperPermissionDoesNotExposeOtherFinancialFactors() throws Exception {
    authenticate("cost:finance-cu-base:query");
    var cu =
        new FactorPriceSnapshot.FactorValue(
            1L, 2L, "铜", "Cu", new BigDecimal("90.1234567890123456"), "CNY/KG", true, List.of());
    var snapshot =
        new FactorPriceSnapshot(
            new FactorPriceScope("HOUSEHOLD", "210", "2026-10", "CU_90000", Condition.GENERAL),
            1L,
            1,
            "基价.xlsx",
            "file",
            "content",
            Map.of("AVG|1#CU", cu, "AVG|PCU", cu),
            List.of("CU_90000"),
            "finance",
            null,
            null);
    when(reader.read(any(), any(), any())).thenReturn(snapshot);
    var data = controller.readCu("2026-10", "210", null, Condition.GENERAL, null).getData();
    assertThat(data.factors()).containsOnlyKeys("AVG|1#CU");
    assertThat(new ObjectMapper().valueToTree(data).at("/factors/AVG|1#CU/price").textValue())
        .isEqualTo("90.1234567890123456");
    assertThatThrownBy(() -> controller.read("2026-10", "210", null, Condition.GENERAL, null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void copperEditUsesSamePublicationAndOnlyReturnsConfirmationToken() throws Exception {
    authenticate("cost:finance-cu-base:edit");
    var preview =
        new FinanceFactorImportPreview(
            null,
            null,
            null,
            null,
            "content",
            "checked",
            List.of(),
            "财务调整",
            "FINANCE_CU_ADJUSTMENT");
    when(copper.preview(2L, new BigDecimal("95000"), "财务调整", "HOUSEHOLD")).thenReturn(preview);
    var result = controller.previewCu(2L, adjustment()).getData();
    assertThat(new ObjectMapper().writeValueAsString(result))
        .isEqualTo("{\"previewFingerprint\":\"checked\"}");
    controller.publishCu(2L, adjustment(), () -> "operator");
    verify(copper).publish(2L, new BigDecimal("95000"), "财务调整", "HOUSEHOLD", "checked", "operator");
    assertThatThrownBy(() -> controller.publishCu(2L, adjustment(), null))
        .hasMessageContaining("登录");
  }

  private FinanceFactorPublicationController.CuAdjustment adjustment() {
    return new FinanceFactorPublicationController.CuAdjustment(
        new BigDecimal("95000"), "财务调整", "checked");
  }

  private void authenticate(String permission) {
    var auth =
        new UsernamePasswordAuthenticationToken(
            "finance", null, List.of(new SimpleGrantedAuthority(permission)));
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "HOUSEHOLD"));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  @Configuration
  @EnableMethodSecurity
  static class Config {
    @Bean("ss")
    PermissionService permissions() {
      return new PermissionService();
    }

    @Bean
    FinanceFactorPublicationService publisher() {
      return mock(FinanceFactorPublicationService.class);
    }

    @Bean
    FinanceFactorPriceReader reader() {
      return mock(FinanceFactorPriceReader.class);
    }

    @Bean
    FinanceFactorCuAdjustmentService copper() {
      return mock(FinanceFactorCuAdjustmentService.class);
    }

    @Bean
    FinanceFactorPublicationController controller(
        FinanceFactorPublicationService publisher,
        FinanceFactorPriceReader reader,
        FinanceFactorCuAdjustmentService copper) {
      return new FinanceFactorPublicationController(publisher, reader, copper);
    }
  }
}
