package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparison;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/monthly-reprice/1130/comparison")
public class MonthlyCostComparisonController {
  private final MonthlyCostComparisonService service;
  private final com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonExportService exports;
  public MonthlyCostComparisonController(MonthlyCostComparisonService service,
      com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonExportService exports){this.service=service;this.exports=exports;}
  @GetMapping
  @PreAuthorize(MonthlyRepriceQueryController.QUERY_PERMISSION)
  public CommonResult<MonthlyCostComparisonService.Page> compare(@RequestParam String baselineMonth,
      @RequestParam String comparisonMonth,@RequestParam(required=false) String metrics,
      @RequestParam(required=false) String oaNo,@RequestParam(required=false) Long itemId,
      @RequestParam(required=false) String productCode,@RequestParam(defaultValue="1") int page,
      @RequestParam(defaultValue="20") int pageSize) {
    try {return CommonResult.success(service.compare(new MonthlyCostComparisonService.Request(baselineMonth,comparisonMonth,
        metrics(metrics),oaNo,itemId,productCode,page,pageSize)));}
    catch(IllegalArgumentException failure){return CommonResult.error(400,failure.getMessage());}
  }
  @GetMapping("/export")
  @PreAuthorize(MonthlyRepriceQueryController.QUERY_PERMISSION)
  public void export(@RequestParam String baselineMonth,@RequestParam String comparisonMonth,
      @RequestParam(required=false) String metrics,@RequestParam(required=false) String oaNo,
      @RequestParam(required=false) Long itemId,@RequestParam(required=false) String productCode,
      jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
    var request=new MonthlyCostComparisonService.Request(baselineMonth,comparisonMonth,metrics(metrics),oaNo,itemId,productCode,1,100);
    try(var file=exports.export(request)) {
      response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
      response.setCharacterEncoding("UTF-8");
      response.setHeader("Content-Disposition","attachment; filename*=UTF-8''"+
          java.net.URLEncoder.encode(file.filename(),java.nio.charset.StandardCharsets.UTF_8).replace("+","%20"));
      response.setContentLengthLong(java.nio.file.Files.size(file.path()));
      java.nio.file.Files.copy(file.path(),response.getOutputStream());
    }
  }
  private static List<MonthlyCostComparison.Metric> metrics(String selection) {
    return selection==null?null:"NONE".equals(selection)?List.of():java.util.Arrays.stream(selection.split(","))
        .map(String::trim).map(MonthlyCostComparison.Metric::valueOf).toList();
  }

}
