package com.sanhua.marketingcost.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Apply the V398 statements for the tables present in a deliberately partial test schema. */
public final class PriceWithdrawalTestSchema {
  private PriceWithdrawalTestSchema() {}

  public static void apply(JdbcTemplate jdbc, String... tables) throws IOException {
    String script = new ClassPathResource("db/V398__invoice_scrap_range_import_void.sql")
        .getContentAsString(StandardCharsets.UTF_8)
        .lines().filter(line -> !line.startsWith("--")).collect(Collectors.joining("\n"));
    for (String table : tables) {
      String statement = Arrays.stream(script.split(";"))
          .map(String::strip)
          .filter(sql -> sql.startsWith("ALTER TABLE " + table + "\n"))
          .findFirst().orElseThrow();
      jdbc.execute(statement);
    }
  }
}
