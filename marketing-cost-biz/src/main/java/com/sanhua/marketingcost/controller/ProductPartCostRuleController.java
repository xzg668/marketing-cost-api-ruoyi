package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.aspect.OperationLogDiffContext;
import com.sanhua.marketingcost.service.ProductPartCostRuleService;
import com.sanhua.marketingcost.service.ProductPartCostRuleService.Input;
import com.sanhua.marketingcost.service.ProductPartCostRuleService.Query;
import com.sanhua.marketingcost.service.ProductPartCostRuleService.ImportResult;
import com.sanhua.marketingcost.service.ProductPartCostRuleExcel;
import com.sanhua.marketingcost.service.costing.ProductPartCostRule;
import java.io.IOException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
import java.nio.charset.StandardCharsets;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rules/product-part-cost")
@PreAuthorize("@ss.hasAnyRole('admin','bu_director')")
public class ProductPartCostRuleController {
  private final ProductPartCostRuleService service;
  private final ProductPartCostRuleExcel excel;
  public ProductPartCostRuleController(ProductPartCostRuleService service, ProductPartCostRuleExcel excel) { this.service = service; this.excel = excel; }

  @GetMapping("/template")
  public ResponseEntity<byte[]> template() throws IOException {
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
        .filename("成品零件成本配置模板.xlsx", StandardCharsets.UTF_8).build().toString())
        .header(HttpHeaders.CONTENT_TYPE,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").body(excel.template());
  }

  @PostMapping("/import-excel")
  @OperationLog(module="成品零件成本配置", operationType=OperationType.INSERT)
  public CommonResult<ImportResult> importExcel(@RequestPart("file") MultipartFile file) throws IOException {
    if(file==null||file.isEmpty())throw new IllegalArgumentException("请上传填写后的.xlsx模板");
    if(file.getSize()>10L*1024*1024)throw new IllegalArgumentException("文件不能超过10MB");
    if(file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx"))
      throw new IllegalArgumentException("仅支持.xlsx，请下载模板填写后导入");
    try(var input=file.getInputStream()) {
      var parsed=excel.parse(input);
      return CommonResult.success(parsed.errors().isEmpty()?service.importNew(parsed.rows()):new ImportResult(0,parsed.errors()));
    }
  }

  @GetMapping
  public CommonResult<List<ProductPartCostRule>> list(@ModelAttribute Query query) {
    return CommonResult.success(service.list(query));
  }

  @GetMapping("/{id}")
  public CommonResult<ProductPartCostRule> get(@PathVariable long id) { return CommonResult.success(service.get(id)); }

  @PostMapping
  @OperationLog(module="成品零件成本配置", operationType=OperationType.INSERT, recordDiff=true)
  public CommonResult<ProductPartCostRule> create(@RequestBody Input input) {
    var result = service.create(input); OperationLogDiffContext.record(null, result);
    return CommonResult.success(result);
  }

  @PutMapping("/{id}")
  @OperationLog(module="成品零件成本配置", operationType=OperationType.UPDATE, recordDiff=true, targetIdParam="id")
  public CommonResult<ProductPartCostRule> update(@PathVariable long id, @RequestBody Input input) {
    var before = service.get(id); var result = service.update(id, input);
    OperationLogDiffContext.record(before, result); return CommonResult.success(result);
  }

  @DeleteMapping("/{id}")
  @OperationLog(module="成品零件成本配置", operationType=OperationType.DELETE, recordDiff=true, targetIdParam="id")
  public CommonResult<Boolean> delete(@PathVariable long id, @RequestParam long revision) {
    var before = service.get(id); service.delete(id, revision);
    OperationLogDiffContext.record(before, null); return CommonResult.success(true);
  }
}
