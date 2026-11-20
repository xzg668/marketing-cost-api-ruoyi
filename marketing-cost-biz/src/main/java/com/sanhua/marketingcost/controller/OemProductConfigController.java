package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.costing.OemProductConfigStore;
import com.sanhua.marketingcost.service.costing.OemProductConfigStore.Entry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/oem-product-configs")
@PreAuthorize("@ss.hasAnyRole('admin','bu_director','bu_staff')")
public class OemProductConfigController {
  private final OemProductConfigStore store;

  public OemProductConfigController(OemProductConfigStore store) {
    this.store = store;
  }

  public record Input(@NotBlank @Size(max = 80) String productCode,
      @Size(max = 255) String productModel, @Size(max = 255) String productSpec,
      @Size(max = 255) String productName,
      @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 6) BigDecimal oemRate, Long revision) {
    Entry entry(long id, long revision) {
      return new Entry(id, revision, productCode.trim(), clean(productModel), clean(productSpec), clean(productName), oemRate);
    }
  }

  @GetMapping
  public CommonResult<List<Entry>> list() {
    return CommonResult.success(store.list(unit()));
  }

  @PostMapping
  @OperationLog(module = "OEM产品配置表", operationType = OperationType.INSERT)
  public CommonResult<Boolean> create(@RequestBody @Valid Input input) {
    return save(() -> store.insert(unit(), input.entry(0, 1)));
  }

  @PutMapping("/{id}")
  @OperationLog(module = "OEM产品配置表", operationType = OperationType.UPDATE)
  public CommonResult<Boolean> update(@PathVariable long id, @RequestBody @Valid Input input) {
    if (input.revision() == null || input.revision() < 1)
      throw new IllegalArgumentException("请刷新后编辑OEM产品配置");
    return save(() -> store.update(unit(), input.entry(id, input.revision())));
  }

  @DeleteMapping("/{id}")
  @OperationLog(module = "OEM产品配置表", operationType = OperationType.DELETE)
  public CommonResult<Boolean> delete(@PathVariable long id, @RequestParam long revision) {
    store.delete(unit(), id, revision);
    return CommonResult.success(true);
  }

  private static CommonResult<Boolean> save(Runnable action) {
    try {
      action.run();
      return CommonResult.success(true);
    } catch (DuplicateKeyException duplicate) {
      throw new IllegalArgumentException("该料号已经配置为OEM产品");
    }
  }

  private static String unit() {
    String unit = BusinessUnitContext.getCurrentBusinessUnitType();
    if (!"COMMERCIAL".equals(unit) && !"HOUSEHOLD".equals(unit))
      throw new IllegalArgumentException("缺少当前业务单元");
    return unit;
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
