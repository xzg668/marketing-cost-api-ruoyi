package com.sanhua.marketingcost.service.collaboration;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UuidCollaborationNumberGenerator implements CollaborationNumberGenerator {

  private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
  private final Clock clock;

  public UuidCollaborationNumberGenerator() {
    this(Clock.systemDefaultZone());
  }

  UuidCollaborationNumberGenerator(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public String nextTaskNo() {
    return next("QCT");
  }

  @Override
  public String nextProductTaskNo() {
    return next("QCPT");
  }

  @Override
  public String nextGapNo() {
    return next("QCG");
  }

  @Override
  public String nextPriceDraftNo() {
    return next("QCPD");
  }

  @Override
  public String nextReviewNo() {
    return next("QCR");
  }

  @Override
  public String nextApprovedResultNo() {
    return next("QCAR");
  }

  private String next(String prefix) {
    String random = UUID.randomUUID().toString().replace("-", "")
        .substring(0, 20).toUpperCase(Locale.ROOT);
    return prefix + "-" + LocalDate.now(clock).format(DATE) + "-" + random;
  }
}
