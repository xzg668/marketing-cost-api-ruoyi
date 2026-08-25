package com.sanhua.marketingcost.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuoteBomStatusErrorMessageContractTest {

  @Test
  @DisplayName("BOM 状态恢复成功时必须把历史错误文案更新为 NULL")
  void errorMessageMustParticipateInNullUpdates() throws Exception {
    Field field = QuoteBomStatus.class.getDeclaredField("errorMessage");

    TableField mapping = field.getAnnotation(TableField.class);

    assertThat(mapping).isNotNull();
    assertThat(mapping.updateStrategy()).isEqualTo(FieldStrategy.ALWAYS);
  }
}
