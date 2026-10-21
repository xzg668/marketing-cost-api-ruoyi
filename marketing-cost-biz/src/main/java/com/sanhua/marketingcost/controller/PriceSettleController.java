package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 旧客户端明确停止使用旧结算来源；历史底稿读取不经过此维护接口。 */
@RestController
@RequestMapping("/api/v1/price-settle")
public class PriceSettleController {
  @RequestMapping({"", "/**"})
  public ResponseEntity<CommonResult<Void>> retired() {
    return ResponseEntity.status(HttpStatus.GONE).body(CommonResult.error(410,
        "旧结算来源已停止维护，请使用新结算价格入口；历史记录保留用于原核算追溯"));
  }
}
