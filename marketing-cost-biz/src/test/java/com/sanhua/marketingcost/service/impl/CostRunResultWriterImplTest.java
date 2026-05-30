package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.service.CostRunResultService;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostRunResultWriterImplTest {

  @Test
  void writesQuoteResultAndDetailsIdempotently() {
    FakeResultService resultService = new FakeResultService();
    PartMapperState partState = new PartMapperState();
    CostMapperState costState = new CostMapperState();
    CostRunResultWriterImpl writer =
        new CostRunResultWriterImpl(resultService, partState.proxy(), costState.proxy());
    OaForm form = new OaForm();
    form.setOaNo("OA-001");
    OaFormItem item = new OaFormItem();
    item.setMaterialNo("P-001");
    CostRunObjectResult result = result();

    writer.writeQuoteResult(result, form, item);
    writer.writeQuoteResult(result, form, item);

    assertThat(resultService.totalCostUpdates).containsExactly(new BigDecimal("88.123456"), new BigDecimal("88.123456"));
    assertThat(resultService.savedItems).containsExactly(item, item);
    assertThat(partState.rows).hasSize(1);
    assertThat(partState.rows.get(0).getOaNo()).isEqualTo("OA-001");
    assertThat(partState.rows.get(0).getProductCode()).isEqualTo("P-001");
    assertThat(partState.rows.get(0).getPartCode()).isEqualTo("PART-001");
    assertThat(partState.rows.get(0).getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(costState.rows).hasSize(1);
    assertThat(costState.rows.get(0).getCostCode()).isEqualTo("TOTAL");
    assertThat(costState.rows.get(0).getLineNo()).isEqualTo(1);
    assertThat(costState.rows.get(0).getAmount()).isEqualByComparingTo("88.123456");
  }

  private CostRunObjectResult result() {
    return CostRunObjectResult.of(
        CostRunContext.quote("OA-001", 1L, "P-001", null, "客户A", "COMMERCIAL", "2026-05", "OBJ-1"),
        null,
        null,
        List.of(partItem()),
        List.of(totalCost("88.123456")));
  }

  private CostRunPartItemDto partItem() {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setOaNo("OA-001");
    item.setProductCode("P-001");
    item.setPartCode("PART-001");
    item.setPartName("Part");
    item.setPartQty(new BigDecimal("2"));
    item.setUnitPrice(new BigDecimal("3"));
    item.setAmount(new BigDecimal("6"));
    return item;
  }

  private CostRunCostItemDto totalCost(String amount) {
    CostRunCostItemDto item = new CostRunCostItemDto();
    item.setCostCode("TOTAL");
    item.setCostName("不含税总成本");
    item.setAmount(new BigDecimal(amount));
    return item;
  }

  private static final class FakeResultService implements CostRunResultService {
    private final List<BigDecimal> totalCostUpdates = new ArrayList<>();
    private final List<OaFormItem> savedItems = new ArrayList<>();

    @Override
    public com.sanhua.marketingcost.dto.CostRunResultDto getResult(String oaNo, String productCode) {
      return null;
    }

    @Override
    public void saveOrUpdate(OaForm form, OaFormItem item) {
      savedItems.add(item);
    }

    @Override
    public void updateTotalCost(String oaNo, String productCode, BigDecimal totalCost) {
      totalCostUpdates.add(totalCost);
    }
  }

  private static final class PartMapperState {
    private final List<CostRunPartItem> rows = new ArrayList<>();

    CostRunPartItemMapper proxy() {
      InvocationHandler handler =
          (proxy, method, args) -> {
            if ("deleteQuoteItems".equals(method.getName())) {
              String oaNo = (String) args[0];
              String productCode = (String) args[1];
              rows.removeIf(row -> oaNo.equals(row.getOaNo()) && productCode.equals(row.getProductCode()));
              return 1;
            }
            if ("insert".equals(method.getName())) {
              rows.add((CostRunPartItem) args[0]);
              return 1;
            }
            throw new UnsupportedOperationException(method.getName());
          };
      return (CostRunPartItemMapper)
          Proxy.newProxyInstance(
              CostRunPartItemMapper.class.getClassLoader(),
              new Class<?>[] {CostRunPartItemMapper.class},
              handler);
    }
  }

  private static final class CostMapperState {
    private final List<CostRunCostItem> rows = new ArrayList<>();

    CostRunCostItemMapper proxy() {
      InvocationHandler handler =
          (proxy, method, args) -> {
            if ("deleteQuoteItems".equals(method.getName())) {
              String oaNo = (String) args[0];
              String productCode = (String) args[1];
              rows.removeIf(row -> oaNo.equals(row.getOaNo()) && productCode.equals(row.getProductCode()));
              return 1;
            }
            if ("insert".equals(method.getName())) {
              rows.add((CostRunCostItem) args[0]);
              return 1;
            }
            throw new UnsupportedOperationException(method.getName());
          };
      return (CostRunCostItemMapper)
          Proxy.newProxyInstance(
              CostRunCostItemMapper.class.getClassLoader(),
              new Class<?>[] {CostRunCostItemMapper.class},
              handler);
    }
  }
}
