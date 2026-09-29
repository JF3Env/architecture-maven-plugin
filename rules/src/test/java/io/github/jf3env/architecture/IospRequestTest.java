package io.github.jf3env.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IospRequestTest {
  @Test
  void keepsWellFormedPlumbingCallsAndDefaultsToNone() {
    var configured = request(Set.of("java.util.Objects#nonNull", "java.util.Map$Entry#getKey"));
    assertEquals(
        Set.of("java.util.Objects#nonNull", "java.util.Map$Entry#getKey"),
        configured.plumbingCalls());
    assertEquals(
        Set.of(),
        new IospRequest("com.ai.label", Path.of("src"), List.of(), Path.of("classes"), List.of())
            .plumbingCalls());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "java.util.Objects.nonNull",
        "#nonNull",
        "java.util.Objects#",
        "java.util.Objects#a#b",
        " "
      })
  void rejectsMalformedPlumbingCalls(String call) {
    assertThrows(IllegalArgumentException.class, () -> request(Set.of(call)));
  }

  private static IospRequest request(Set<String> plumbingCalls) {
    return new IospRequest(
        "com.ai.label", Path.of("src"), List.of(), Path.of("classes"), List.of(), plumbingCalls);
  }
}
