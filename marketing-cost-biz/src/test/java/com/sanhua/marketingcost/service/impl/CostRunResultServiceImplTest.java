package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CostRunResultServiceImplTest {

  @Test
  void saveOrUpdateMarksProductResultCalculatedEvenWhenOaIsNotCalculated() {
    ResultMapperState resultState = new ResultMapperState();
    CostRunResultServiceImpl service =
        new CostRunResultServiceImpl(
            resultState.proxy(),
            emptyProxy(ProductPropertyMapper.class),
            emptyProxy(MaterialMasterMapper.class));

    OaForm form = new OaForm();
    form.setOaNo("OA-001");
    form.setCustomer("客户A");
    form.setCalcStatus("未核算");
    form.setApplyDate(LocalDate.of(2026, 5, 20));
    form.setBusinessUnitType("COMMERCIAL");
    OaFormItem item = new OaFormItem();
    item.setMaterialNo("P-001");
    item.setProductName("产品A");
    item.setSunlModel("M-001");

    service.saveOrUpdate(form, item);

    assertThat(resultState.saved).isNotNull();
    assertThat(resultState.saved.getOaNo()).isEqualTo("OA-001");
    assertThat(resultState.saved.getProductCode()).isEqualTo("P-001");
    assertThat(resultState.saved.getCalcStatus()).isEqualTo("已核算");
    assertThat(resultState.saved.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(resultState.saved.getCalcAt()).isNotNull();
    assertThat(resultState.saved.getPeriod()).isEqualTo("2026-05");
  }

  private static final class ResultMapperState {
    private CostRunResult saved;

    CostRunResultMapper proxy() {
      InvocationHandler handler =
          (proxy, method, args) -> {
            return switch (method.getName()) {
              case "selectOne" -> null;
              case "insert" -> {
                saved = (CostRunResult) args[0];
                yield 1;
              }
              case "updateById" -> {
                saved = (CostRunResult) args[0];
                yield 1;
              }
              case "toString" -> "costRunResultMapper";
              case "hashCode" -> System.identityHashCode(proxy);
              case "equals" -> proxy == args[0];
              default -> throw new UnsupportedOperationException(method.getName());
            };
          };
      return (CostRunResultMapper)
          Proxy.newProxyInstance(
              CostRunResultMapper.class.getClassLoader(),
              new Class<?>[] {CostRunResultMapper.class},
              handler);
    }
  }

  private static <T> T emptyProxy(Class<T> type) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          return switch (method.getName()) {
            case "selectOne" -> null;
            case "toString" -> type.getSimpleName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
          };
        };
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }
}
