package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceVariableBindingDto;
import com.sanhua.marketingcost.dto.PriceVariableBindingPendingResponse;
import com.sanhua.marketingcost.dto.PriceVariableBindingRequest;
import com.sanhua.marketingcost.service.PriceVariableBindingService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行局部变量绑定控制器 —— V34 新增。
 *
 * <p>路由前缀：{@code /api/v1/price-linked/bindings}
 *
 * <ul>
 *   <li>{@code GET /bindings?linkedItemId=X} —— 当前生效列表
 *   <li>{@code GET /bindings/history?linkedItemId=X&tokenName=材料含税价格} —— 历史时间线
 *   <li>{@code POST /bindings} —— 新增 / UPSERT / 版本切换
 *   <li>{@code DELETE /bindings/{id}} —— 软删
 *   <li>{@code GET /bindings/pending} —— 待绑定联动行（公式含 B 组 token 但无绑定）
 * </ul>
 *
 * <p>权限：
 *
 * <ul>
 *   <li>读：{@code price:linked:binding:view}（有联动价查看权的人都可见）
 *   <li>写：{@code price:linked:binding:admin}（只有供管部 / 运维能改，避免误打断整列）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/price-linked/bindings")
public class PriceVariableBindingController {

  private final PriceVariableBindingService service;

  public PriceVariableBindingController(PriceVariableBindingService service) {
    this.service = service;
  }

  @PreAuthorize("@ss.hasPermi('price:linked:binding:view')")
  @GetMapping
  public CommonResult<List<PriceVariableBindingDto>> list(@RequestParam Long linkedItemId) {
    return CommonResult.success(service.listByLinkedItem(linkedItemId));
  }

  @PreAuthorize("@ss.hasPermi('price:linked:binding:view')")
  @GetMapping("/history")
  public CommonResult<List<PriceVariableBindingDto>> history(
      @RequestParam Long linkedItemId, @RequestParam String tokenName) {
    return CommonResult.success(service.getHistory(linkedItemId, tokenName));
  }

  @PreAuthorize("@ss.hasPermi('price:linked:binding:admin')")
  @PostMapping
  public CommonResult<Long> save(@Valid @RequestBody PriceVariableBindingRequest request) {
    return CommonResult.success(service.save(request));
  }

  @PreAuthorize("@ss.hasPermi('price:linked:binding:admin')")
  @DeleteMapping("/{id}")
  public CommonResult<Void> remove(@PathVariable Long id) {
    service.softDelete(id);
    return CommonResult.success(null);
  }

  @PreAuthorize("@ss.hasPermi('price:linked:binding:view')")
  @GetMapping("/pending")
  public CommonResult<PriceVariableBindingPendingResponse> pending() {
    return CommonResult.success(service.getPending());
  }
}
