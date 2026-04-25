package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验证所有需要事务保护的 Service 方法都正确标注了 @Transactional。
 * 这是一个反射测试，确保注解不会被意外删除。
 */
class TransactionalAnnotationTest {

  private static final List<MethodRef> REQUIRED = List.of(
      new MethodRef(AuxRateItemServiceImpl.class, "importItems"),
      new MethodRef(MaterialMasterServiceImpl.class, "importItems"),
      new MethodRef(AuxSubjectServiceImpl.class, "importItems"),
      new MethodRef(SalaryCostServiceImpl.class, "importItems"),
      new MethodRef(PriceRangeItemServiceImpl.class, "importItems"),
      new MethodRef(BomManualItemServiceImpl.class, "importItems"),
      new MethodRef(ProductPropertyServiceImpl.class, "importItems"),
      new MethodRef(PriceFixedItemServiceImpl.class, "importItems"),
      new MethodRef(MaterialPriceTypeServiceImpl.class, "importItems"),
      new MethodRef(PriceLinkedItemServiceImpl.class, "importItems"),
      new MethodRef(OtherExpenseRateServiceImpl.class, "importItems"),
      new MethodRef(QualityLossRateServiceImpl.class, "importItems"),
      new MethodRef(ManufactureRateServiceImpl.class, "importItems"),
      new MethodRef(DepartmentFundRateServiceImpl.class, "importItems"),
      new MethodRef(ThreeExpenseRateServiceImpl.class, "importItems"),
      new MethodRef(FinanceBasePriceServiceImpl.class, "importPrices"),
      new MethodRef(PriceSettleServiceImpl.class, "delete"),
      new MethodRef(PriceSettleServiceImpl.class, "importSettle"),
      // T5.5：BomManageItemServiceImpl.refresh 已整体下线为 no-op（见类注释），
      // 不再涉及写库，@Transactional 没有语义，故从 REQUIRED 名单里移除。
      new MethodRef(PriceLinkedCalcServiceImpl.class, "refresh")
  );

  @Test
  @DisplayName("所有写操作方法必须标注 @Transactional")
  void testAllRequiredMethodsHaveTransactional() {
    List<String> missing = new ArrayList<>();

    for (MethodRef ref : REQUIRED) {
      boolean found = false;
      for (Method method : ref.clazz.getDeclaredMethods()) {
        if (method.getName().equals(ref.methodName)) {
          Transactional ann = method.getAnnotation(Transactional.class);
          if (ann != null) {
            found = true;
            // 验证 rollbackFor 包含 Exception.class
            boolean hasRollback = false;
            for (Class<?> ex : ann.rollbackFor()) {
              if (Exception.class.isAssignableFrom(ex)) {
                hasRollback = true;
                break;
              }
            }
            assertTrue(hasRollback,
                ref.clazz.getSimpleName() + "." + ref.methodName
                    + " @Transactional 缺少 rollbackFor = Exception.class");
          }
          break;
        }
      }
      if (!found) {
        missing.add(ref.clazz.getSimpleName() + "." + ref.methodName);
      }
    }

    assertTrue(missing.isEmpty(),
        "以下方法缺少 @Transactional 注解: " + String.join(", ", missing));
  }

  private record MethodRef(Class<?> clazz, String methodName) {}
}
