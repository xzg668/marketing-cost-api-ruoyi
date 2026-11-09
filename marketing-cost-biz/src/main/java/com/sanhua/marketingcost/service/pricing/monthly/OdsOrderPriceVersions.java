package com.sanhua.marketingcost.service.pricing.monthly;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/** Latest price-approval document wins within each supplier; source business dates are preserved. */
final class OdsOrderPriceVersions {
  private static final Pattern DOCUMENT=Pattern.compile("(?:^|[-－])((?:DDJGCG05|CGDDCG05)([0-9]{8})([0-9]{4}))$");
  record Approval(String documentNumber, LocalDate documentDate) {}
  static Approval approval(String source) {
    if(source==null)return null;
    var match=DOCUMENT.matcher(source.strip());if(!match.find())return null;
    try{return new Approval(match.group(1),LocalDate.parse(match.group(2),DateTimeFormatter.BASIC_ISO_DATE));}
    catch(DateTimeParseException invalid){return null;}
  }
  record Price(String supplierCode, LocalDate effectiveDate, LocalDate expiryDate, BigDecimal price,String source) {
    Price(String supplierCode,LocalDate effectiveDate,LocalDate expiryDate,BigDecimal price) {
      this(supplierCode,effectiveDate,expiryDate,price,null);
    }
    Price {
      if (supplierCode == null || supplierCode.isBlank())
        throw new IllegalArgumentException("订单价格缺少供应商代码");
      supplierCode = supplierCode.strip();
      if (price == null) throw new IllegalArgumentException("订单价格缺少单价");
      if (price.signum() < 0) throw new IllegalArgumentException("订单价格不能为负数");
      price = price.stripTrailingZeros();
      source = source == null ? null : source.strip();
    }
  }

  private OdsOrderPriceVersions() {}

  /** Caller supplies one applicable company/material/specification/unit, without filtering null dates. */
  static Map<String, Price> latestPerSupplier(Collection<Price> rows) {
    var legacyOrder = Comparator.comparing(Price::effectiveDate,
        Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder()))
        .thenComparing(Price::expiryDate,Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder()))
        .thenComparing(Price::price);
    var selected = new TreeMap<String, Price>();
    for (var row : rows) selected.merge(row.supplierCode(), row,
        (previous, incoming) -> {
          var previousApproval=approval(previous.source());var incomingApproval=approval(incoming.source());
          int comparison;
          if(previousApproval==null && incomingApproval==null)comparison=legacyOrder.compare(incoming,previous);
          else if(previousApproval==null)comparison=1;
          else if(incomingApproval==null)comparison=-1;
          else {
            comparison=incomingApproval.documentDate().compareTo(previousApproval.documentDate());
            if(comparison==0)comparison=incoming.price().compareTo(previous.price());
          }
          return comparison>0?incoming:previous;
        });
    return Collections.unmodifiableMap(selected);
  }
}
