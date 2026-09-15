package io.github.jf3env.architecture.bytecode;

import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.compile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedContextsTest {
  @TempDir Path directory;

  @Test
  void contextsAreTheFirstLevelSegmentsBelowTheBaseExceptThePlatform() throws IOException {
    var classes =
        compile(
            directory,
            Map.of(
                "org.acme.Application", "public class Application {}",
                "org.acme.platform.domain.Money", "public record Money(long cents) {}",
                "org.acme.orders.domain.Order", "public class Order {}",
                "org.acme.orders.package-info", "@Deprecated",
                "org.acme.billing.api.InvoiceRef", "public record InvoiceRef(long id) {}",
                "other.library.Helper", "public class Helper {}"));
    assertEquals(
        List.of("billing", "orders"),
        BoundedContexts.derive(BytecodePolicy.of("org.acme"), classes));
    assertEquals(
        List.of("billing", "orders", "platform"),
        BoundedContexts.derive(
            new BytecodePolicy("org.acme", "shared", null, null, null, null), classes));
  }

  @Test
  void aConsumerWithoutAnyContextIsRejectedRatherThanTriviallyApproved() throws IOException {
    var classes =
        compile(
            directory,
            Map.of(
                "org.acme.Application", "public class Application {}",
                "org.acme.platform.domain.Money", "public record Money(long cents) {}"));
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> BoundedContexts.derive(BytecodePolicy.of("org.acme"), classes));
    assertTrue(failure.getMessage().contains("No bounded context"), failure.getMessage());
  }
}
