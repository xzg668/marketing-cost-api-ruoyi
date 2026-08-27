package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.service.collaboration.scan.CurrentU9BomResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationCurrentU9BomGateway;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** 审核生效前逐个报价关联复核当月 U9 首查快照；只有明确 NOT_FOUND 才允许使用补录 BOM。 */
@Service
public class ApprovalBomSourcePolicy {
  private final QuoteCollaborationCurrentU9BomGateway u9Gateway;

  public ApprovalBomSourcePolicy(QuoteCollaborationCurrentU9BomGateway u9Gateway) {
    this.u9Gateway = u9Gateway;
  }

  public Decision inspect(
      QuoteCollaborationProductTask product,
      List<QuoteCollaborationQuoteLink> activeLinks) {
    if (product == null) throw invalid("产品任务不存在");
    if (!"FULL_BOM".equals(product.getPrimaryScope())) {
      return new Decision(false, List.of());
    }
    if (activeLinks == null || activeLinks.isEmpty()) {
      throw invalid("产品任务没有活动报价关联，无法复核当月 U9 BOM 快照");
    }
    List<LinkDecision> links = new ArrayList<>();
    boolean supplementRequired = false;
    for (QuoteCollaborationQuoteLink link : activeLinks) {
      QuoteCollaborationScanContext context = context(product, link);
      CurrentU9BomResult result = u9Gateway.read(context);
      if (result == null) throw invalid("U9 当前 BOM 查询没有返回结果");
      switch (result.status()) {
        case AVAILABLE -> links.add(new LinkDecision(link, context, result, false));
        case NOT_FOUND -> {
          supplementRequired = true;
          links.add(new LinkDecision(link, context, result, true));
        }
        case TIMEOUT -> throw invalid(first(result.message(), "U9 当前 BOM 查询超时，不能发布补录 BOM"));
        case ORGANIZATION_MISMATCH -> throw invalid(first(result.message(), "U9 BOM 组织不匹配，不能发布补录 BOM"));
        case DATA_EMPTY -> throw invalid(first(result.message(), "U9 当前 BOM 返回空数据，不能发布补录 BOM"));
        case ERROR -> throw invalid(first(result.message(), "U9 当前 BOM 查询失败，不能发布补录 BOM"));
      }
    }
    return new Decision(supplementRequired, List.copyOf(links));
  }

  private QuoteCollaborationScanContext context(
      QuoteCollaborationProductTask product, QuoteCollaborationQuoteLink link) {
    LocalDateTime now = LocalDateTime.now();
    return new QuoteCollaborationScanContext(
        link.getOaFormId(), link.getOaFormItemId(), link.getOaNo(), link.getAccountingMonth(),
        product.getBusinessUnitType(), product.getProductCode(), product.getProductName(),
        product.getProductSpec(), product.getProductModel(), product.getPriceOrgCode(),
        product.getMaterialOrgCode(), LocalDate.now(), now);
  }

  private static String first(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static CollaborationDomainException invalid(String message) {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.STATE_TRANSITION_INVALID, message);
  }

  public record Decision(boolean supplementRequired, List<LinkDecision> links) {
    public Decision {
      links = links == null ? List.of() : List.copyOf(links);
    }
  }

  public record LinkDecision(
      QuoteCollaborationQuoteLink link,
      QuoteCollaborationScanContext context,
      CurrentU9BomResult u9,
      boolean useSupplementBom) {}
}
