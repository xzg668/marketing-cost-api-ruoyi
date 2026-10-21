package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sanhua.marketingcost.config.GlobalExceptionHandler;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.SysUserService;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialImportService;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialMappingService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlateMaterialMappingControllerTest {
  private static final String URL = "/api/v1/plate-material-mappings";
  AnnotationConfigApplicationContext context;
  MockMvc mvc;
  PlateMaterialMappingService mappings;
  PlateMaterialImportService imports;
  SysUserService users;
  final MockMultipartFile file = new MockMultipartFile("file", "source.xlsx", "application/octet-stream", new byte[] {1, 2});

  @BeforeEach void setup() {
    context = new AnnotationConfigApplicationContext(Config.class);
    mappings = context.getBean(PlateMaterialMappingService.class);
    imports = context.getBean(PlateMaterialImportService.class);
    users = context.getBean(SysUserService.class);
    var user = new SysUser();
    user.setUserId(101L);
    when(users.findByUsername("finance")).thenReturn(user);
    mvc = MockMvcBuilders.standaloneSetup(context.getBean(PlateMaterialMappingController.class))
        .setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @AfterEach void clear() {
    SecurityContextHolder.clearContext();
    context.close();
  }

  @Test void technicalAndQuotationPermissionsDoNotGrantConfigurationAccess() throws Exception {
    for (String permission : List.of("technical:data:task:edit", "ingest:quote:cost-run:execute", "unrelated")) {
      authenticate(permission);
      mvc.perform(get(URL)).andExpect(status().isForbidden());
      mvc.perform(get(URL + "/options")).andExpect(status().isForbidden());
      mvc.perform(multipart(URL + "/import-preview").file(file)).andExpect(status().isForbidden());
      mvc.perform(multipart(URL + "/import-confirm").file(file).param("fingerprint", "old")).andExpect(status().isForbidden());
      mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
      mvc.perform(put(URL + "/1").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
    }
    verifyNoInteractions(mappings, imports);
  }

  @Test void listPermissionCannotCreateEditOrImportAndUsesAuthenticatedUnit() throws Exception {
    authenticate("base:plate-material:list");
    mvc.perform(get(URL).param("model", "A").param("businessUnitType", "COMMERCIAL"))
        .andExpect(status().isOk());
    verify(mappings).list("HOUSEHOLD", "A", null, 1, 20);
    mvc.perform(get(URL + "/options")).andExpect(status().isOk()).andExpect(jsonPath("$.data[5]").value("水水316L"));
    mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
    mvc.perform(put(URL + "/1").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
    mvc.perform(multipart(URL + "/import-preview").file(file)).andExpect(status().isForbidden());
    verifyNoInteractions(imports);
  }

  @Test void eachWriteOperationRequiresItsOwnPermissionAndDerivesActorServerSide() throws Exception {
    authenticate("base:plate-material:add");
    mvc.perform(post(URL).principal(() -> "finance").contentType(MediaType.APPLICATION_JSON).content(body()))
        .andExpect(status().isOk());
    verify(mappings).create("HOUSEHOLD", "A", "水水316L", 101);
    mvc.perform(put(URL + "/1").principal(() -> "finance").contentType(MediaType.APPLICATION_JSON).content(body()))
        .andExpect(status().isForbidden());
    authenticate("base:plate-material:edit");
    mvc.perform(put(URL + "/1").principal(() -> "finance").contentType(MediaType.APPLICATION_JSON).content(body()))
        .andExpect(status().isOk());
    verify(mappings).update("HOUSEHOLD", 1, "A", "水水316L", 0, 101);
    authenticate("base:plate-material:import");
    mvc.perform(get(URL + "/options")).andExpect(status().isOk());
    mvc.perform(multipart(URL + "/import-preview").file(file)).andExpect(status().isOk());
    verify(imports).preview(new byte[] {1, 2}, "source.xlsx", "HOUSEHOLD");
    mvc.perform(multipart(URL + "/import-confirm").file(file).param("fingerprint", "preview").principal(() -> "finance"))
        .andExpect(status().isOk());
    verify(imports).confirm(new byte[] {1, 2}, "source.xlsx", "HOUSEHOLD", "preview", 101);
    mvc.perform(multipart(URL + "/import-confirm").file(file).param("fingerprint", "preview"))
        .andExpect(status().isBadRequest());
  }

  @Test void duplicateIdentityIsAnExplicit409AndEmptyUploadsAre400() throws Exception {
    authenticate("base:plate-material:add");
    when(mappings.create(anyString(), anyString(), anyString(), anyLong())).thenThrow(new DuplicateKeyException("unique"));
    mvc.perform(post(URL).principal(() -> "finance").contentType(MediaType.APPLICATION_JSON).content(body()))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
    authenticate("base:plate-material:import");
    mvc.perform(multipart(URL + "/import-preview")
        .file(new MockMultipartFile("file", "source.xlsx", "application/octet-stream", new byte[0])))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(imports);
  }

  private static String body() {
    return """
        {"model":"A","plateMaterial":"水水316L","expectedVersion":0,
         "businessUnitType":"COMMERCIAL","updatedBy":999}
        """;
  }

  private void authenticate(String permission) {
    var auth = new UsernamePasswordAuthenticationToken("finance", "", List.of(new SimpleGrantedAuthority(permission)));
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "HOUSEHOLD"));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  @Configuration @EnableMethodSecurity
  static class Config {
    @Bean(name = "ss") PermissionService permissions() { return new PermissionService(); }
    @Bean PlateMaterialMappingService mappings() { return mock(PlateMaterialMappingService.class); }
    @Bean PlateMaterialImportService imports() { return mock(PlateMaterialImportService.class); }
    @Bean SysUserService users() { return mock(SysUserService.class); }
    @Bean PlateMaterialMappingController controller(PlateMaterialMappingService mappings,
        PlateMaterialImportService imports, SysUserService users) {
      return new PlateMaterialMappingController(mappings, imports, users);
    }
  }
}
