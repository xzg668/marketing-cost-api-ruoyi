package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sanhua.marketingcost.config.GlobalExceptionHandler;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.pricing.household.*;
import static com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HouseholdInquiryControllerTest {
  AnnotationConfigApplicationContext context;HouseholdInquiryService service;MockMvc mvc;
  static final String BASE="/api/v1/household-inquiries";
  @BeforeEach void setup(){context=new AnnotationConfigApplicationContext(Config.class);service=context.getBean(HouseholdInquiryService.class);mvc=MockMvcBuilders.standaloneSetup(context.getBean(HouseholdInquiryController.class)).setControllerAdvice(new GlobalExceptionHandler()).build();}
  @AfterEach void cleanup(){SecurityContextHolder.clearContext();context.close();}
  UsernamePasswordAuthenticationToken login(String permission){var auth=new UsernamePasswordAuthenticationToken("finance",null,List.of(new SimpleGrantedAuthority(permission)));auth.setDetails(Map.of("businessUnitType","COMMERCIAL"));SecurityContextHolder.getContext().setAuthentication(auth);return auth;}
  @ParameterizedTest @ValueSource(strings={"","/history?materialCode=001Ab","/12","/12/financial-reference","/12/original","/imports/12","/imports/12/original","/import-previews/preview-id"})
  void everyReadEndpointEnforcesItsActualMethodSecurity(String path) throws Exception {
    var auth=login("unrelated");mvc.perform(get(BASE+path).principal(auth)).andExpect(status().isForbidden());verifyNoInteractions(service);
  }
  @Test void importPermissionIsCheckedForBothPreviewAndPublishAndCancel() throws Exception {
    var auth=login("price:household-inquiry:list");
    mvc.perform(multipart(BASE+"/import-preview").file(new MockMultipartFile("file","x.xls","application/octet-stream",new byte[]{1})).file(new MockMultipartFile("priceContext","","application/json","{\"unit\":\"件\",\"currency\":\"CNY\",\"taxBasis\":\"EXCLUDED\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))).param("sourceMonth","2026-10").principal(auth)).andExpect(status().isForbidden());
    mvc.perform(post(BASE+"/import-publish").contentType(MediaType.APPLICATION_JSON).content("{\"previewId\":\"a\",\"publishRequestId\":\"b\",\"previewFingerprint\":\"c\"}").principal(auth)).andExpect(status().isForbidden());
    mvc.perform(post(BASE+"/import-previews/a/cancel").principal(auth)).andExpect(status().isForbidden());verifyNoInteractions(service);
  }
  @Test void ordinaryQueryPermissionCannotReadFinancialReferencesOrOriginalBytes() throws Exception {
    var auth=login("price:household-inquiry:query");
    mvc.perform(get(BASE+"/12/financial-reference").principal(auth)).andExpect(status().isForbidden());mvc.perform(get(BASE+"/12/original").principal(auth)).andExpect(status().isForbidden());verifyNoInteractions(service);
  }
  @Test void listUsesAuthenticatedBusinessUnitAndSerializesExactAmountsWithoutFinancialColumns() throws Exception {
    var auth=login("price:household-inquiry:list");
    var row=new PriceView(12,"001Ab","线圈","S",1,true,"12.123456789012","0.085123456789","2026-10",new PriceContext("件","CNY",com.sanhua.marketingcost.service.pricing.monthly.TaxBasis.EXCLUDED),8,java.time.LocalDateTime.parse("2026-10-23T10:00:00"),List.of(3));
    when(service.current("COMMERCIAL","001Ab",1,20)).thenReturn(new PricePage(List.of(row),1,1,20));
    var result=mvc.perform(get(BASE).param("materialCode","001Ab").param("businessUnitType","HOUSEHOLD").principal(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].unitPrice").value("12.123456789012")).andExpect(jsonPath("$.data.rows[0].householdRate").value("0.085123456789")).andReturn();
    assertThat(result.getResponse().getContentAsString()).doesNotContain("financialReference","referenceJson","fileKey");verify(service).current("COMMERCIAL","001Ab",1,20);
  }
  @Test void publishRejectsExtraClientPricesOrSelectedRows() throws Exception {
    var auth=login("price:household-inquiry:import");
    mvc.perform(post(BASE+"/import-publish").contentType(MediaType.APPLICATION_JSON).content("{\"previewId\":\"a\",\"publishRequestId\":\"b\",\"previewFingerprint\":\"c\",\"unitPrice\":0}").principal(auth)).andExpect(status().isBadRequest());verifyNoInteractions(service);
  }
  @Test void publishUsesOnlyTheAuthenticatedActorAndReservedRequestIdentity() throws Exception {
    var auth=login("price:household-inquiry:import");var request=new PublishRequest("a","b","c");
    when(service.publish("COMMERCIAL","finance",request)).thenReturn(new PublishResult(9,"b","PUBLISHED",List.of(),0,0,"0".repeat(64)));
    mvc.perform(post(BASE+"/import-publish").contentType(MediaType.APPLICATION_JSON).content("{\"previewId\":\"a\",\"publishRequestId\":\"b\",\"previewFingerprint\":\"c\"}").principal(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    verify(service).publish("COMMERCIAL","finance",request);
  }
  @Test void stablePublicationFailureIsAnHttpConflictAndDoesNotBecomeSuccess() throws Exception {
    var auth=login("price:household-inquiry:import");when(service.publish(eq("COMMERCIAL"),eq("finance"),any())).thenThrow(new HouseholdInquiryFailure("STALE_PREVIEW",409,"目标已变化"));
    mvc.perform(post(BASE+"/import-publish").contentType(MediaType.APPLICATION_JSON).content("{\"previewId\":\"a\",\"publishRequestId\":\"b\",\"previewFingerprint\":\"c\"}").principal(auth)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409)).andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.startsWith("STALE_PREVIEW:")));
  }
  @Test void originalDownloadHasPermissionAndUsesSavedFileBytesAndDigest() throws Exception {
    var auth=login("price:household-inquiry:download");byte[] original={1,2,3};when(service.original("COMMERCIAL",12)).thenReturn(new OriginalFile("原件.xls","a".repeat(64),original));
    mvc.perform(get(BASE+"/12/original").principal(auth)).andExpect(status().isOk()).andExpect(content().bytes(original)).andExpect(header().string("X-File-SHA256","a".repeat(64))).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("attachment")));
  }
  @Configuration @EnableMethodSecurity static class Config {
    @Bean("ss") PermissionService permissions(){return new PermissionService();}
    @Bean HouseholdInquiryService service(){return mock(HouseholdInquiryService.class);}
    @Bean HouseholdInquiryController controller(HouseholdInquiryService service){return new HouseholdInquiryController(service);}
  }
}
