package com.toni.marketplace.common;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.MarketplaceApplication;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Structured JSON logging (backlog #137): logback-spring.xml keeps the
 * plain console pattern as the default and swaps the console encoder to
 * logstash-logback-encoder's {@link LogstashEncoder} only under the opt-in
 * {@code json-logs} Spring profile — one JSON object per line, with the
 * {@code requestId} MDC value (RequestIdFilter, #44) as a top-level field.
 *
 * <p>The profile tests boot a real context the way
 * {@code RefreshCookieSecureStartupCheckTest} (#130) does, because the
 * {@code <springProfile>} sections of logback-spring.xml are evaluated by
 * Spring Boot's logging system during startup — a plain unit test of the
 * XML file could not prove the wiring. The encoder-output test attaches a
 * LogstashEncoder (the class the profile configures) to a probe logger
 * over a byte stream and parses the emitted line as JSON. Honest scope:
 * this shapes log lines only; shipping/aggregation is the operator's.
 */
class JsonLoggingProfileTest {

  private static String[] bootArgs(String dbName) {
    return new String[] {
      "--spring.datasource.url=jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1;MODE=MySQL",
      "--spring.datasource.driver-class-name=org.h2.Driver",
      "--spring.datasource.username=sa",
      "--spring.datasource.password=",
      "--app.jwt.secret=" + randomKey(),
      "--app.auth.totp.encryption-key=" + randomKey(),
    };
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  /** Encoders of every appender currently attached to the root logger. */
  private static List<Encoder<?>> rootEncoders() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
    List<Encoder<?>> encoders = new ArrayList<>();
    for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
      Appender<ILoggingEvent> appender = it.next();
      if (appender instanceof OutputStreamAppender<?> streamAppender) {
        encoders.add(streamAppender.getEncoder());
      }
    }
    return encoders;
  }

  @Test
  void jsonLogsProfileBootsAndWiresTheLogstashEncoder() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(MarketplaceApplication.class)
            .profiles("json-logs")
            .run(bootArgs("jsonlogsprofile"))) {
      assertThat(context.getEnvironment().getActiveProfiles()).contains("json-logs");
      assertThat(rootEncoders())
          .as("under json-logs the root console appender must encode via LogstashEncoder")
          .anyMatch(LogstashEncoder.class::isInstance);
    }
  }

  @Test
  void defaultProfileKeepsThePlainPatternEncoder() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(MarketplaceApplication.class)
            .run(bootArgs("jsonlogsdefault"))) {
      assertThat(context.getEnvironment().getActiveProfiles()).doesNotContain("json-logs");
      assertThat(rootEncoders())
          .as("without the profile no LogstashEncoder may be wired — default output stays plain")
          .noneMatch(LogstashEncoder.class::isInstance)
          .anyMatch(PatternLayoutEncoder.class::isInstance);
    }
  }

  @Test
  void emittedJsonLineParsesAndCarriesRequestIdFromTheMdc() throws Exception {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

    LogstashEncoder encoder = new LogstashEncoder();
    encoder.setContext(context);
    encoder.start();

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
    appender.setContext(context);
    appender.setEncoder(encoder);
    appender.setOutputStream(out);
    appender.start();

    Logger probe = context.getLogger("com.toni.marketplace.common.JsonLoggingProbe");
    probe.addAppender(appender);
    boolean wasAdditive = probe.isAdditive();
    probe.setAdditive(false);
    try {
      MDC.put(RequestIdFilter.MDC_KEY, "req-json-137");
      probe.info("json probe message");
    } finally {
      MDC.remove(RequestIdFilter.MDC_KEY);
      probe.detachAppender(appender);
      probe.setAdditive(wasAdditive);
      appender.stop();
      encoder.stop();
    }

    String line = out.toString(StandardCharsets.UTF_8).trim();
    assertThat(line).as("the encoder must emit exactly one line").isNotEmpty().doesNotContain("\n");

    JsonNode json = new ObjectMapper().readTree(line);
    assertThat(json.get("@timestamp").asText()).isNotBlank();
    assertThat(json.get("level").asText()).isEqualTo("INFO");
    assertThat(json.get("logger_name").asText())
        .isEqualTo("com.toni.marketplace.common.JsonLoggingProbe");
    assertThat(json.get("message").asText()).isEqualTo("json probe message");
    assertThat(json.get("requestId").asText()).isEqualTo("req-json-137");
  }
}
