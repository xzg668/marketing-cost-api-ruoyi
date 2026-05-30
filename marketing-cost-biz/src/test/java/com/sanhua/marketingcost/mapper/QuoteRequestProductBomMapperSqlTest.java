package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.parsing.XPathParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("报价产品 BOM 准备 Mapper 注解 SQL")
class QuoteRequestProductBomMapperSqlTest {

  @Test
  @DisplayName("注解 SQL 可被 MyBatis XML 脚本解析")
  void selectScriptsAreValidXml() {
    for (Method method : QuoteRequestProductBomMapper.class.getDeclaredMethods()) {
      Select select = method.getAnnotation(Select.class);
      if (select == null) {
        continue;
      }
      String script = String.join("\n", select.value());
      assertThat(script).doesNotContain("<>");
      new XPathParser(script, false);
    }
  }
}
