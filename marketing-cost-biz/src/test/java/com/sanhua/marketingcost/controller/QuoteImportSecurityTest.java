package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.ingest.*;
import java.util.Arrays;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class QuoteImportSecurityTest {
  private AnnotationConfigApplicationContext context;
  private QuoteIngestController controller;
  private final MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", new byte[]{1});

  @BeforeEach void setup() {
    context = new AnnotationConfigApplicationContext(Config.class);
    controller = context.getBean(QuoteIngestController.class);
  }
  @AfterEach void close() { SecurityContextHolder.clearContext(); context.close(); }
  private void login(String... authorities) {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test", "unused",
        Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"bu_staff", "bu_director", "finance_reviewer", "viewer", "admin_other"})
  void nonAdminCannotUseAnyImportEndpointEvenWithLegacyAndWildcardPermissions(String role) {
    login("ROLE_" + role, "ingest:quote:import", "ingest:quote:mock-create", "*:*:*");
    assertThatThrownBy(() -> controller.ingest(new QuoteIngestRequest())).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.previewExcel(file)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.commitExcel(file)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.previewPdf(file)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.commitPdf(file)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.listExcelTemplates()).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.downloadExcelTemplate("FI-SC-006", new MockHttpServletResponse())).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(context.getBean(QuoteIngestService.class), context.getBean(QuoteExcelImportService.class),
        context.getBean(QuotePdfImportService.class), context.getBean(QuoteExcelTemplateService.class));
  }

  @ParameterizedTest @ValueSource(strings = {"admin", "ADMIN"})
  void actualAdminKeepsImportPreviewCommitAndTemplates(String role) throws Exception {
    login("ROLE_" + role);
    controller.ingest(new QuoteIngestRequest());
    controller.previewExcel(file); controller.commitExcel(file);
    controller.previewPdf(file); controller.commitPdf(file); controller.listExcelTemplates();
    var template = new QuoteExcelTemplateFile(com.sanhua.marketingcost.enums.QuoteExcelTemplateType.FI_SC_006, new byte[]{1, 2});
    when(context.getBean(QuoteExcelTemplateService.class).getTemplate("FI-SC-006")).thenReturn(template);
    var response = new MockHttpServletResponse(); controller.downloadExcelTemplate("FI-SC-006", response);
    assertThat(response.getContentAsByteArray()).containsExactly((byte)1, (byte)2);
    verify(context.getBean(QuoteIngestService.class)).ingest(any());
    verify(context.getBean(QuoteExcelImportService.class)).commit(any(), eq("test.pdf"));
    verify(context.getBean(QuotePdfImportService.class)).commit(any(), eq("test.pdf"));
  }

  @Test void ordinaryQuoteBomAndLogsPermissionsRemainUsable() {
    login("ROLE_bu_staff", "ingest:quote:list", "ingest:quote-log:list");
    controller.bomStatus("OA-EXISTING"); controller.logs(1, 20, null, null, null);
    verify(context.getBean(QuoteBomStatusService.class)).listByOaNo("OA-EXISTING");
    verify(context.getBean(QuoteRequestQueryService.class)).pageLogs(1, 20, null, null, null);
  }

  @Configuration @EnableMethodSecurity
  static class Config {
    @Bean(name = "ss") PermissionService permissions() { return new PermissionService(); }
    @Bean QuoteIngestService ingest() { return mock(QuoteIngestService.class); }
    @Bean QuoteExcelImportService excel() { return mock(QuoteExcelImportService.class); }
    @Bean QuotePdfImportService pdf() { return mock(QuotePdfImportService.class); }
    @Bean QuoteExcelTemplateService templates() { return mock(QuoteExcelTemplateService.class); }
    @Bean QuoteBomStatusService bom() { return mock(QuoteBomStatusService.class); }
    @Bean QuoteRequestQueryService query() { return mock(QuoteRequestQueryService.class); }
    @Bean QuoteIngestController controller(QuoteIngestService a, QuoteExcelImportService b, QuotePdfImportService c,
        QuoteExcelTemplateService d, QuoteBomStatusService e, QuoteRequestQueryService f) {
      return new QuoteIngestController(a, b, c, d, e, f);
    }
  }
}
