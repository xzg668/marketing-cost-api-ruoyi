package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("联动价 Mapper 契约")
class PriceLinkedItemMapperContractTest {

  @Test
  @DisplayName("分页保留 MyBatis-Plus 默认 selectPage，不声明无 SQL 的覆盖方法")
  void paginationKeepsBaseMapperDefaultSelectPage() {
    assertThat(BaseMapper.class).isAssignableFrom(PriceLinkedItemMapper.class);
    assertThat(Arrays.stream(PriceLinkedItemMapper.class.getDeclaredMethods())
            .noneMatch(method -> "selectPage".equals(method.getName())))
        .isTrue();
  }

  @Test
  @DisplayName("普通列表和分页列表查询都带业务单元隔离")
  void selectListMethodsKeepDataScope() throws NoSuchMethodException {
    Method list = PriceLinkedItemMapper.class.getMethod("selectList", Wrapper.class);
    Method pagedList = PriceLinkedItemMapper.class.getMethod("selectList", IPage.class, Wrapper.class);

    assertThat(list.getAnnotation(DataScope.class)).isNotNull();
    assertThat(pagedList.getAnnotation(DataScope.class)).isNotNull();
  }
}
