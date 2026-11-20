package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.costing.OemProductConfigStore;
import com.sanhua.marketingcost.service.reprice.QuotationWrite;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/items/{itemId}/oem")
public class QuoteItemOemController {
  private final JdbcTemplate jdbc;
  private final OemProductConfigStore store;
  private final OaWorkflowAccessPolicy workflow;

  public QuoteItemOemController(JdbcTemplate jdbc, OemProductConfigStore store, OaWorkflowAccessPolicy workflow) {
    this.jdbc = jdbc;
    this.store = store;
    this.workflow = workflow;
  }

  public record Selection(@NotNull Boolean oem, @NotNull Boolean expectedOem) {}
  private record Item(long formId, String unit, String materialNo) {}

  @PutMapping
  @PreAuthorize("@ss.hasPermi('ingest:quote:list') and @ss.hasAnyRole('admin','bu_director','bu_staff')")
  @OperationLog(module = "报价产品是否OEM", operationType = OperationType.UPDATE)
  @QuotationWrite(holdUntilCompletion = true)
  @Transactional
  public CommonResult<Boolean> change(@PathVariable String oaNo, @PathVariable long itemId,
      @Valid @RequestBody Selection selection) {
    Item item = jdbc.query("""
        SELECT f.id,COALESCE(NULLIF(TRIM(i.business_unit_type),''),f.business_unit_type),i.material_no
        FROM oa_form f JOIN oa_form_item i ON i.oa_form_id=f.id
        WHERE f.oa_no=? AND i.id=? AND f.deleted=0 AND i.deleted=0
        FOR UPDATE
        """, (row, index) -> new Item(row.getLong(1), row.getString(2), row.getString(3)),
        oaNo, itemId).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("报价产品行不存在"));
    if (item.unit() == null || !Objects.equals(item.unit(), BusinessUnitContext.getCurrentBusinessUnitType())) {
      throw new IllegalArgumentException("报价产品行不属于当前业务单元");
    }
    workflow.requireCosting(item.formId());
    if (selection.oem() && (item.materialNo() == null || item.materialNo().isBlank())) {
      throw new IllegalArgumentException("请先补齐产品料号，再选择OEM");
    }
    boolean current = store.quoteOption(itemId, item.unit(), item.materialNo()).enabled();
    if (current != selection.oem()) {
      if (current != selection.expectedOem()) throw new IllegalArgumentException("OEM标记已变化，请刷新后重试");
      store.setQuoteItem(item.formId(), itemId, selection.oem());
    }
    return CommonResult.success(selection.oem());
  }
}
