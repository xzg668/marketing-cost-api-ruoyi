package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.PackageComponentSnapshotService;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
class PackageComponentPriceConcurrencyIntegrationTest extends BomMapperTestBase {
  @Autowired PackageComponentPriceMapper prices;
  @Autowired PackageComponentPriceDetailMapper details;
  @Autowired PackageComponentGapItemMapper gaps;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;

  @Test
  void firstPricesForDifferentQuotesDoNotLockEachOthersEmptyDetailRanges() throws Exception {
    var key = "TW18-PKG-" + UUID.randomUUID().toString().substring(0, 8);
    var snapshots = mock(PackageComponentSnapshotService.class);
    var router = mock(MaterialPriceRouterService.class);
    var barrier = new CyclicBarrier(2);
    var snapshot = new PackageComponentSnapshot();
    snapshot.setId(90001L);
    snapshot.setPriceOrgCode("210");
    snapshot.setPackageMaterialCode(key);
    snapshot.setPackageMaterialName("并行包装");
    snapshot.setPeriodMonth("2026-09");
    snapshot.setStatus("NORMAL");
    snapshot.setSourceTopProductCode(key + "-PRODUCT");
    var child = new PackageComponentSnapshotDetail();
    child.setId(90002L);
    child.setSnapshotId(snapshot.getId());
    child.setLineNo(1);
    child.setChildMaterialCode(key + "-CHILD");
    child.setChildMaterialName("纸箱");
    child.setQtyPerParent(new BigDecimal("2"));
    when(snapshots.ensureSnapshot(any())).thenAnswer(invocation -> {
      PackageSnapshotRequest request = invocation.getArgument(0);
      var selected = new PackageComponentSnapshot();
      org.springframework.beans.BeanUtils.copyProperties(snapshot, selected);
      selected.setSourceTopProductCode(request.getTopProductCode());
      return PackageSnapshotResult.of(selected, List.of(child), false);
    });
    when(router.listCandidates(anyString(), anyString(), any())).thenReturn(List.of(new PriceTypeRoute(
        child.getChildMaterialCode(), MaterialFormAttrEnum.PURCHASED, PriceTypeEnum.FIXED,
        1, null, null, "manual", "固定采购价")));
    var resolver = new PriceResolver() {
      @Override public PriceTypeEnum priceType() { return PriceTypeEnum.FIXED; }
      @Override public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
        try {
          // 两个事务均已创建主记录；旧实现此时各持有空明细范围的间隙锁。
          barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
          throw new IllegalStateException("并行取价未同时到达", failure);
        }
        return PriceResolveResult.hit(new BigDecimal("3"), "固定采购价");
      }
    };
    var service = new PackageComponentPriceServiceImpl(snapshots, router, prices, details, gaps, List.of(resolver));
    var transaction = new TransactionTemplate(transactions);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = List.of("A", "B").stream().map(suffix -> pool.submit(() -> {
        var auth = new UsernamePasswordAuthenticationToken("test", "unused", List.of(new SimpleGrantedAuthority("*:*:*")));
        auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
          var request = new PackagePriceRequest();
          request.setPackageMaterialCode(key);
          request.setPeriodMonth("2026-09");
          request.setOaNo(key + "-" + suffix);
          request.setQuoteNo(key + "-" + suffix);
          request.setTopProductCode(key + "-PRODUCT-" + suffix);
          request.setPriceOrgCode("210");
          request.setBomPurpose("主制造");
          request.setSourceType("U9");
          request.setAsOfDate(LocalDate.of(2026, 9, 20));
          request.setCalcBatchId(key + "-" + suffix);
          return transaction.execute(status -> service.ensurePrice(request));
        } finally {
          SecurityContextHolder.clearContext();
        }
      })).toList();
      for (var future : futures) {
        var result = future.get(20, TimeUnit.SECONDS);
        assertThat(result.isComplete()).isTrue();
        assertThat(result.getPrice().getTotalPrice()).isEqualByComparingTo("6");
      }
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_package_component_price WHERE package_material_code=?", Integer.class, key)).isEqualTo(2);
    assertThat(jdbc.queryForList("SELECT child_amount FROM lp_package_component_price_detail WHERE package_material_code=? ORDER BY id", BigDecimal.class, key))
        .hasSize(2).allSatisfy(amount -> assertThat(amount).isEqualByComparingTo("6"));
  }
}
