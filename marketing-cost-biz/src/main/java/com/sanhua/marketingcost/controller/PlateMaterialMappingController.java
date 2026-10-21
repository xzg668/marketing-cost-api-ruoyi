package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.entity.PlateMaterialMapping;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.SysUserService;
import com.sanhua.marketingcost.service.platematerial.PlateMaterial;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialImportService;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialMappingService;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialWorkbookParser;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/plate-material-mappings")
public class PlateMaterialMappingController {
  public record SaveRequest(String model, String plateMaterial, Integer expectedVersion) {}

  private final PlateMaterialMappingService mappings;
  private final PlateMaterialImportService imports;
  private final SysUserService users;

  public PlateMaterialMappingController(PlateMaterialMappingService mappings,
      PlateMaterialImportService imports, SysUserService users) {
    this.mappings = mappings;
    this.imports = imports;
    this.users = users;
  }

  @GetMapping
  @PreAuthorize("@ss.hasPermi('base:plate-material:list')")
  public CommonResult<PlateMaterialMappingService.MappingPage> list(
      @RequestParam(required = false) String model, @RequestParam(required = false) String plateMaterial,
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(mappings.list(unit(), model, plateMaterial, page, size));
  }

  @GetMapping("/options")
  @PreAuthorize("@ss.hasAnyPermi('base:plate-material:list','base:plate-material:add','base:plate-material:edit','base:plate-material:import')")
  public CommonResult<List<String>> options() {
    return CommonResult.success(PlateMaterial.options());
  }

  @PostMapping
  @PreAuthorize("@ss.hasPermi('base:plate-material:add')")
  @OperationLog(module = "型号—板片材质对照表", operationType = OperationType.INSERT)
  public CommonResult<PlateMaterialMapping> create(@RequestBody SaveRequest request, Principal principal) {
    return CommonResult.success(mappings.create(unit(), request.model(), request.plateMaterial(), actor(principal)));
  }

  @PutMapping("/{id}")
  @PreAuthorize("@ss.hasPermi('base:plate-material:edit')")
  @OperationLog(module = "型号—板片材质对照表", operationType = OperationType.UPDATE)
  public CommonResult<PlateMaterialMapping> update(@PathVariable long id,
      @RequestBody SaveRequest request, Principal principal) {
    return CommonResult.success(mappings.update(unit(), id, request.model(), request.plateMaterial(),
        request.expectedVersion(), actor(principal)));
  }

  @PostMapping("/import-preview")
  @PreAuthorize("@ss.hasPermi('base:plate-material:import')")
  public CommonResult<PlateMaterialImportService.Preview> preview(@RequestParam MultipartFile file) throws IOException {
    return CommonResult.success(imports.preview(bytes(file), file.getOriginalFilename(), unit()));
  }

  @PostMapping("/import-confirm")
  @PreAuthorize("@ss.hasPermi('base:plate-material:import')")
  @OperationLog(module = "型号—板片材质对照表", operationType = OperationType.IMPORT)
  public CommonResult<PlateMaterialImportService.ImportResult> confirm(@RequestParam MultipartFile file,
      @RequestParam String fingerprint, Principal principal) throws IOException {
    return CommonResult.success(imports.confirm(bytes(file), file.getOriginalFilename(), unit(), fingerprint, actor(principal)));
  }

  @ExceptionHandler({ConcurrencyFailureException.class, DuplicateKeyException.class})
  @ResponseStatus(HttpStatus.CONFLICT)
  public CommonResult<Void> conflict(Exception failure) {
    return CommonResult.error(409, "型号已存在或配置已变化，请刷新或重新预览后确认");
  }

  private static byte[] bytes(MultipartFile file) throws IOException {
    if (file == null || file.isEmpty() || file.getSize() > PlateMaterialWorkbookParser.MAX_BYTES) {
      throw new IllegalArgumentException("请上传非空且不超过 10 MB 的 xlsx 文件");
    }
    return file.getBytes();
  }

  private static String unit() {
    return BusinessUnitContext.getCurrentBusinessUnitType();
  }

  private long actor(Principal principal) {
    var user = principal == null ? null : users.findByUsername(principal.getName());
    if (user == null || user.getUserId() == null || user.getUserId() <= 0) {
      throw new IllegalArgumentException("请使用有效系统账号维护型号配置");
    }
    return user.getUserId();
  }
}
