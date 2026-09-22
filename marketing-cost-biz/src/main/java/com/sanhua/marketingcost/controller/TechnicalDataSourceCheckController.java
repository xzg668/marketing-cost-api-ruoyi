package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;
import com.sanhua.marketingcost.integration.oa.directory.OaPersonDirectoryOption;
import com.sanhua.marketingcost.integration.oa.directory.OaPersonDirectoryService;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActorProvider;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataQuoteSourceReader;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskException;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/technical-data")
@PreAuthorize("@ss.hasAnyPermi('ingest:quote:cost-run:execute','technical:data:admin:operate')")
public class TechnicalDataSourceCheckController {
  public record Assignee(
      Long userId,
      String employeeNo,
      String name,
      String position,
      String department,
      String targetDepartment) {
    static Assignee from(OaPersonDirectoryOption person) {
      return new Assignee(person.userId(), person.employeeNo(), person.name(), person.position(),
          person.department(), person.targetDepartment());
    }
  }
  private final TechnicalDataQuoteSourceReader sources;
  private final TechnicalDataActorProvider actors;
  private final OaPersonDirectoryService people;

  public TechnicalDataSourceCheckController(
      TechnicalDataQuoteSourceReader sources,
      TechnicalDataActorProvider actors,
      OaPersonDirectoryService people) {
    this.sources = sources;
    this.actors = actors;
    this.people = people;
  }

  @PostMapping("/quote-items/{itemId}/check")
  public CommonResult<TechnicalDataSourceCheckResponse> check(@PathVariable Long itemId, @RequestParam String accountingMonth) {
    if (actors.current().shortSession()) return CommonResult.error(403, "短时办理会话不能检查其他报价产品");
    try { return CommonResult.success(sources.recheck(itemId, accountingMonth)); }
    catch (TechnicalDataTaskException exception) {
      return CommonResult.error(exception.code().name().equals("FORBIDDEN") ? 403 : 409, exception.getMessage());
    }
  }

  @GetMapping("/assignees")
  public CommonResult<List<Assignee>> assignees(
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "50") int limit) {
    if (actors.current().shortSession()
        || !"COMMERCIAL".equals(BusinessUnitContext.getCurrentBusinessUnitType())) {
      return CommonResult.error(403, "请选择业务单元后分派补录");
    }
    if (keyword != null && keyword.length() > 100) {
      return CommonResult.error(400, "人员搜索内容不能超过100个字符");
    }
    return CommonResult.success(people.search(keyword, limit).stream().map(Assignee::from).toList());
  }
}
