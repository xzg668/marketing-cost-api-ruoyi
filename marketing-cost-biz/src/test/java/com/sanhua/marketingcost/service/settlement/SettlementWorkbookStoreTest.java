package com.sanhua.marketingcost.service.settlement;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettlementWorkbookStoreTest {
  @TempDir Path directory;

  @Test void concurrentSameContentIsOneVerifiableArchiveAndOtherBusinessUnitsCannotReadIt() throws Exception {
    var store = new SettlementWorkbookStore(directory.toString());
    byte[] bytes = new byte[65536];
    new java.util.Random(42).nextBytes(bytes);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> store.save("COMMERCIAL", bytes));
      var b = pool.submit(() -> store.save("COMMERCIAL", bytes));
      assertThat(a.get()).isEqualTo(b.get());
    }
    String hash = SettlementWorkbookParser.sha256(bytes);
    assertThat(store.read("COMMERCIAL", hash)).isEqualTo(bytes);
    assertThatThrownBy(() -> store.read("HOUSEHOLD", hash)).hasMessageContaining("不可读取");
    try (var paths = Files.walk(directory)) { assertThat(paths.filter(Files::isRegularFile).toList()).hasSize(1); }
    assertThatThrownBy(() -> store.read("COMMERCIAL", "../" + hash)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void archiveCorruptionIsReportedWithoutReturningPartialOrFabricatedContent() throws Exception {
    var store = new SettlementWorkbookStore(directory.toString());
    byte[] bytes = {1, 2, 3};
    store.save("COMMERCIAL", bytes);
    String hash = SettlementWorkbookParser.sha256(bytes);
    Files.write(directory.resolve("COMMERCIAL").resolve(hash).resolve("workbook.bin"), new byte[]{9});
    assertThatThrownBy(() -> store.read("COMMERCIAL", hash)).hasMessageContaining("校验失败");
    assertThatThrownBy(() -> store.save("COMMERCIAL", bytes)).hasMessageContaining("校验失败");
  }
}
