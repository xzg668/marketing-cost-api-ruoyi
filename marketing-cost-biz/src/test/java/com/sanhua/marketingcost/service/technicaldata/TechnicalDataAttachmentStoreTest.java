package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TechnicalDataAttachmentStoreTest {
  @TempDir Path root;
  @Test void keepsOriginalFileAndSeparatesProductsAndVersions() throws Exception {
    var store = new TechnicalDataAttachmentStore(root.toString(), new ObjectMapper());
    var first = store.save(1L, "辅料.xlsx", new byte[]{1, 2, 3});
    var second = store.save(1L, "修改.xlsx", new byte[]{4, 5, 6});
    assertThat(first.sha256()).isNotEqualTo(second.sha256());
    assertThat(store.read(1L, first.sha256()).bytes()).containsExactly(1, 2, 3);
    assertThat(store.save(1L, "重复上传.xlsx", new byte[]{1, 2, 3}).fileName()).isEqualTo("辅料.xlsx");
    assertThatThrownBy(() -> store.read(2L, first.sha256())).hasMessageContaining("不存在");
    assertThatThrownBy(() -> store.read(1L, "../../secret")).hasMessageContaining("标识无效");
    Files.write(root.resolve("1").resolve(first.sha256()).resolve("content.xlsx"), new byte[]{9});
    assertThatThrownBy(() -> store.read(1L, first.sha256())).hasMessageContaining("校验失败");
  }
}
