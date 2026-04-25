package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceVariableRequest;
import com.sanhua.marketingcost.dto.RowLocalPlaceholderDto;
import com.sanhua.marketingcost.dto.VariableCatalogResponse;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.service.PriceVariableService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 价格变量控制器 —— 联动价模块"变量"相关查询与 CRUD 入口。
 *
 * <p>路由前缀：{@code /api/v1/price-linked}
 * <ul>
 *   <li>{@link #list(String)} {@code GET /variables} —— 后台管理平铺列表</li>
 *   <li>{@link #catalog()} {@code GET /variables/catalog} —— 前端编辑器三分组目录（T15）</li>
 *   <li>{@link #getOne(Long)} {@code GET /variables/{id}} —— 单条详情（T9a）</li>
 *   <li>{@link #create(PriceVariableRequest)} {@code POST /variables} —— 新增（T9a）</li>
 *   <li>{@link #update(Long, PriceVariableRequest)} {@code PUT /variables/{id}} —— 更新（T9a）</li>
 *   <li>{@link #remove(Long)} {@code DELETE /variables/{id}} —— 软删（T9a）</li>
 * </ul>
 *
 * <p>T9a 权限体系：读权限 {@code price:variable:list}；写权限 {@code price:variable:admin}
 * （避免普通查看者误改变量定义，打断整个联动价链）。
 */
@RestController
@RequestMapping("/api/v1/price-linked")
public class PriceVariableController {
  private final PriceVariableService priceVariableService;
  private final RowLocalPlaceholderRegistry rowLocalPlaceholderRegistry;

  public PriceVariableController(
      PriceVariableService priceVariableService,
      RowLocalPlaceholderRegistry rowLocalPlaceholderRegistry) {
    this.priceVariableService = priceVariableService;
    this.rowLocalPlaceholderRegistry = rowLocalPlaceholderRegistry;
  }

  /** 查询价格变量列表 —— 可按 status 过滤（管理页用）。 */
  @PreAuthorize("@ss.hasPermi('price:variable:list')")
  @GetMapping("/variables")
  public CommonResult<List<PriceVariable>> list(
      @RequestParam(required = false) String status) {
    return CommonResult.success(priceVariableService.list(status));
  }

  /**
   * 查询变量目录 —— T15 新增。
   *
   * <p>返回 {@code financeFactors}/{@code partContexts}/{@code formulaRefs} 三组，
   * 供前端公式编辑器做变量树展示。仅含 {@code status='active'} 的变量，{@code CONST} 不暴露。
   */
  @PreAuthorize("@ss.hasPermi('price:variable:catalog')")
  @GetMapping("/variables/catalog")
  public CommonResult<VariableCatalogResponse> catalog() {
    return CommonResult.success(priceVariableService.catalog());
  }

  /** 单条详情 —— 管理页编辑态用，返回原始 entity（含原始 resolver_params JSON 字符串）。 */
  @PreAuthorize("@ss.hasPermi('price:variable:list')")
  @GetMapping("/variables/{id}")
  public CommonResult<PriceVariable> getOne(@PathVariable Long id) {
    return CommonResult.success(priceVariableService.getById(id));
  }

  /** 新增 —— 服务层做唯一性 + schema 校验；失败抛 IllegalArgumentException → 400。 */
  @PreAuthorize("@ss.hasPermi('price:variable:admin')")
  @PostMapping("/variables")
  public CommonResult<Long> create(@Valid @RequestBody PriceVariableRequest request) {
    return CommonResult.success(priceVariableService.create(request));
  }

  /** 更新 —— variableCode 不允许改；写后 registry cache 自动失效。 */
  @PreAuthorize("@ss.hasPermi('price:variable:admin')")
  @PutMapping("/variables/{id}")
  public CommonResult<Void> update(
      @PathVariable Long id, @Valid @RequestBody PriceVariableRequest request) {
    priceVariableService.update(id, request);
    return CommonResult.success(null);
  }

  /**
   * 查询行局部占位符配置 —— V36 新增。
   *
   * <p>返回 {@code lp_row_local_placeholder} 表的当前生效视图，每项含
   * {@code code / displayName / tokenNames}。前端用它动态构建中英文映射，
   * 彻底避免硬编码 {@code __material / __scrap} 导致的"新增占位符要改前端"问题。
   *
   * <p>权限：复用变量查询权限 {@code price:variable:list}（同属"只读的元数据"语义）。
   */
  @PreAuthorize("@ss.hasPermi('price:variable:list')")
  @GetMapping("/row-local-placeholders")
  public CommonResult<List<RowLocalPlaceholderDto>> rowLocalPlaceholders() {
    Map<String, String> displays = rowLocalPlaceholderRegistry.displayNames();
    Map<String, List<String>> tokens = rowLocalPlaceholderRegistry.tokenNames();
    List<RowLocalPlaceholderDto> out = new ArrayList<>(displays.size());
    // 按 displays 的插入序（registry 内部保持 sort_order）组装
    for (Map.Entry<String, String> e : displays.entrySet()) {
      RowLocalPlaceholderDto dto = new RowLocalPlaceholderDto();
      dto.setCode(e.getKey());
      dto.setDisplayName(e.getValue());
      dto.setTokenNames(tokens.getOrDefault(e.getKey(), List.of()));
      out.add(dto);
    }
    return CommonResult.success(out);
  }

  /** 软删 —— 置 status=inactive，避免物理删破坏历史公式反向渲染。 */
  @PreAuthorize("@ss.hasPermi('price:variable:admin')")
  @DeleteMapping("/variables/{id}")
  public CommonResult<Void> remove(@PathVariable Long id) {
    priceVariableService.softDelete(id);
    return CommonResult.success(null);
  }
}
