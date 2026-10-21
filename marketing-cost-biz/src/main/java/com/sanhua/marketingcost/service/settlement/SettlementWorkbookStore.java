package com.sanhua.marketingcost.service.settlement;

import java.io.IOException;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 按业务单元和内容摘要留存原件，多个价格修订共享同一份文件。 */
@Component
public class SettlementWorkbookStore {
  private final Path root;

  public SettlementWorkbookStore(@Value("${price.settlement.workbooks-root:${quote.technical-data.attachments-root:./data/technical-data}/settlement}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  public String save(String businessUnit, byte[] bytes) {
    String hash = SettlementWorkbookParser.sha256(bytes);
    Path target = path(businessUnit, hash);
    Path temporary = null;
    Exception primaryFailure = null;
    try {
      Files.createDirectories(target.getParent());
      if (!Files.exists(target)) {
        temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
        Files.write(temporary, bytes);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
      }
      read(businessUnit, hash);
      return businessUnit + "/" + hash;
    } catch (IOException failure) {
      primaryFailure = failure;
      throw new IllegalStateException("结算原件归档失败，请检查附件存储目录", failure);
    } catch (RuntimeException failure) {
      primaryFailure = failure;
      throw failure;
    }
    finally {
      if (temporary != null) try { Files.deleteIfExists(temporary); }
      catch (IOException failure) {
        if (primaryFailure != null) primaryFailure.addSuppressed(failure);
        else throw new IllegalStateException("结算原件临时文件清理失败", failure);
      }
    }
  }

  public byte[] read(String businessUnit, String hash) {
    try {
      byte[] bytes = Files.readAllBytes(path(businessUnit, hash));
      if (!SettlementWorkbookParser.sha256(bytes).equals(hash)) throw new IllegalStateException("结算原件摘要校验失败");
      return bytes;
    } catch (IOException failure) { throw new IllegalStateException("结算原件不可读取，请核查归档目录", failure); }
  }

  private Path path(String businessUnit, String hash) {
    if (!java.util.Set.of("COMMERCIAL", "HOUSEHOLD").contains(businessUnit == null ? "" : businessUnit))
      throw new IllegalArgumentException("原件业务单元无效");
    if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("结算原件摘要无效");
    return root.resolve(businessUnit).resolve(hash).resolve("workbook.bin");
  }
}
