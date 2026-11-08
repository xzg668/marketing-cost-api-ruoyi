package com.sanhua.marketingcost.integration.drawing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class DrawingVerificationCallGuardTest {
  private static final URI ENDPOINT = URI.create("http://drawing.test:8011/verify");

  @Test void connectionFailureDoesNotBypassTheQueue() throws Exception {
    var source = mock(DataSource.class);
    when(source.getConnection()).thenThrow(new SQLException("unavailable"));
    assertThatThrownBy(() -> new DrawingVerificationCallGuard(source).execute(ENDPOINT,
        () -> { throw new AssertionError("must not call HTTP without lock"); }))
        .isInstanceOfSatisfying(DrawingVerificationException.class,
            e -> assertThat(e.getCode()).isEqualTo(DrawingVerificationException.Code.TRANSPORT_ERROR));
  }

  @Test void ambiguousLockAcquisitionAbortsTheConnectionInsteadOfReturningItToThePool() throws Exception {
    var source = mock(DataSource.class);
    var connection = mock(Connection.class);
    var statement = mock(PreparedStatement.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenThrow(new SQLException("lost response"));
    assertThatThrownBy(() -> new DrawingVerificationCallGuard(source).execute(ENDPOINT,
        () -> { throw new AssertionError("must not call HTTP without lock"); }))
        .isInstanceOf(DrawingVerificationException.class);
    verify(connection).abort(any());
    verify(connection).close();
  }

  @Test void failedReleaseAbortsTheConnectionEvenWhenTheHttpCallFailed() throws Exception {
    var source = mock(DataSource.class);
    var connection = mock(Connection.class);
    var acquire = mock(PreparedStatement.class);
    var release = mock(PreparedStatement.class);
    var result = mock(ResultSet.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("SELECT GET_LOCK(?,?)")).thenReturn(acquire);
    when(connection.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(release);
    when(acquire.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getInt(1)).thenReturn(1);
    when(release.executeQuery()).thenThrow(new SQLException("release failed"));
    var failure = new IllegalStateException("HTTP failed");
    assertThatThrownBy(() -> new DrawingVerificationCallGuard(source).execute(ENDPOINT,
        () -> { throw failure; })).isSameAs(failure);
    verify(connection).abort(any());
    verify(connection).close();
  }

  @Test void springConstructsTheClientWithItsSharedGuard() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(DataSource.class, () -> mock(DataSource.class));
      context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
      context.register(DrawingVerificationProperties.class, DrawingVerificationCodec.class,
          DrawingVerificationCallGuard.class, HttpDrawingVerificationClient.class);
      context.refresh();
      assertThat(context.getBean(DrawingVerificationClient.class)).isInstanceOf(HttpDrawingVerificationClient.class);
    }
  }
}
