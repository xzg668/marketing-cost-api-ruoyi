package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 产品范围内按内容摘要保留原表，退回重传生成新文件，不覆盖已送审附件。 */
@Component
public class TechnicalDataAttachmentStore {
  private final Path root;
  private final ObjectMapper json;

  public TechnicalDataAttachmentStore(@Value("${quote.technical-data.attachments-root:./data/technical-data}") String root,
      ObjectMapper json) {
    this.root = Path.of(root).toAbsolutePath().normalize(); this.json = json;
  }

  public synchronized StoredFile save(Long productId, String fileName, byte[] bytes) {
    String hash = sha256(bytes);
    Path directory = directory(productId, hash);
    String name = Path.of(fileName.replace('\\', '/')).getFileName().toString();
    try {
      Files.createDirectories(directory);
      if (!Files.exists(directory.resolve("metadata.json"))) {
        // 未发布的中间文件不对外读取；完整写入内容后再发布元数据。
        Files.write(directory.resolve("content.xlsx"), bytes);
        Files.writeString(directory.resolve("metadata.json"), json.writeValueAsString(new Metadata(name, hash, bytes.length)), StandardOpenOption.CREATE_NEW);
      }
      return read(productId, hash);
    } catch (IOException exception) { throw new IllegalStateException("补录原表保存失败，请检查附件目录", exception); }
  }

  public StoredFile read(Long productId, String hash) {
    Path directory = directory(productId, hash);
    try {
      var metadata = json.readValue(Files.readString(directory.resolve("metadata.json")), Metadata.class);
      byte[] bytes = Files.readAllBytes(directory.resolve("content.xlsx"));
      if (!Objects.equals(metadata.sha256(), hash) || metadata.size() != bytes.length
          || !Objects.equals(sha256(bytes), hash)) throw new IllegalStateException("补录原表校验失败，请联系管理员核实文件");
      return new StoredFile(metadata.fileName(), hash, bytes);
    } catch (IOException exception) { throw new IllegalStateException("补录原表不存在或无法读取，请核实附件存储", exception); }
  }

  public static String sha256(byte[] bytes) {
    try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
  }

  private Path directory(Long productId, String hash) {
    if (productId == null || productId <= 0 || hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("补录文件标识无效");
    return root.resolve(productId.toString()).resolve(hash);
  }
  private record Metadata(String fileName, String sha256, long size) {}
  public record StoredFile(String fileName, String sha256, byte[] bytes) {}
}
