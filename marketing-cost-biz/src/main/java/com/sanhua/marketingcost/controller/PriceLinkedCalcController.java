package com.sanhua.marketingcost.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceLinkedCalcPageResponse;
import com.sanhua.marketingcost.dto.PriceLinkedCalcTraceResponse;
import com.sanhua.marketingcost.service.PriceLinkedCalcService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

/**
 * 联动价格计算控制器 —— 列表 / 刷新 / trace 查询。
 */
@RestController
@RequestMapping("/api/v1/price-linked")
public class PriceLinkedCalcController {
  private final PriceLinkedCalcService priceLinkedCalcService;

  public PriceLinkedCalcController(PriceLinkedCalcService priceLinkedCalcService) {
    this.priceLinkedCalcService = priceLinkedCalcService;
  }

  /** 查询联动价格计算列表 */
  @PreAuthorize("@ss.hasPermi('price:linked-calc:list')")
  @GetMapping("/calc")
  public CommonResult<PriceLinkedCalcPageResponse> list(
      @RequestParam(required = false) String oaNo,
      @RequestParam(required = false) String itemCode,
      @RequestParam(required = false) String shapeAttr,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<com.sanhua.marketingcost.dto.PriceLinkedCalcRow> pager =
        priceLinkedCalcService.page(oaNo, itemCode, shapeAttr, current, size);
    return CommonResult.success(new PriceLinkedCalcPageResponse(pager.getTotal(), pager.getRecords()));
  }

  /** 刷新联动价格计算数据 */
  @PreAuthorize("@ss.hasPermi('price:linked-calc:edit')")
  @PostMapping("/calc/refresh")
  public CommonResult<Integer> refresh(@RequestBody(required = false) java.util.Map<String, String> body) {
    String oaNo = body == null ? null : body.get("oaNo");
    if (!StringUtils.hasText(oaNo)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"missing oaNo");
    }
    return CommonResult.success(priceLinkedCalcService.refresh(oaNo));
  }

  /**
   * 按 {@code lp_price_linked_item.id} 当场跑 preview 产出 trace。
   *
   * <p>返回 {@code {id, traceJson}}；id 不存在返 {@code 404/NOT_FOUND}。
   * traceJson 里平铺 {@code rawExpr / normalizedExpr / variables / result / error}，
   * 公式语法或变量解析失败会体现在 {@code error} 字段，HTTP 仍为 200。
   */
  @PreAuthorize("@ss.hasPermi('price:linked-calc:list')")
  @GetMapping("/items/{id}/trace")
  public CommonResult<PriceLinkedCalcTraceResponse> trace(@PathVariable("id") Long id) {
    PriceLinkedCalcTraceResponse payload = priceLinkedCalcService.getTrace(id);
    if (payload == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.NOT_FOUND.getCode(), "linked item not found");
    }
    return CommonResult.success(payload);
  }

  /**
   * 按 {@code lp_price_linked_calc_item.id} 读最近一次 refresh 落库的 trace。
   *
   * <p>与 {@link #trace(Long)} 的 preview 口径互补：
   * <ul>
   *   <li>本接口 —— 该行"上次 refresh 实际跑的" trace，含 OA 金属锁价代入，
   *       和列表"部品单价"对得上</li>
   *   <li>{@code /items/{linkedItemId}/trace} —— 模板层 preview，按最新基价跑</li>
   * </ul>
   * 前端 OA 结果页行级"查看 trace"按钮走这个接口。
   */
  @PreAuthorize("@ss.hasPermi('price:linked-calc:list')")
  @GetMapping("/calc/{calcId}/trace")
  public CommonResult<PriceLinkedCalcTraceResponse> calcTrace(@PathVariable("calcId") Long calcId) {
    PriceLinkedCalcTraceResponse payload = priceLinkedCalcService.getCalcTrace(calcId);
    if (payload == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.NOT_FOUND.getCode(), "calc item not found");
    }
    return CommonResult.success(payload);
  }
}
