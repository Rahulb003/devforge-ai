package com.devforge.ai.deploymentservice.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.devforge.ai.deploymentservice.sandbox.Sandbox.Language;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.Limits;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.Outcome;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.Result;
import com.devforge.ai.deploymentservice.sandbox.Sandbox.SandboxUnavailableException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every guarantee in docs/SANDBOX.md section 2, attacked, against a real container engine.
 *
 * <p>Each test is an escape attempt that has to fail. A guarantee with no such test is an
 * assumption. Skipped where there is no engine, except in CI, where a missing engine is a failure:
 * a sandbox whose tests quietly skipped is not a verified sandbox.
 */
@DisplayName("Docker sandbox escape attempts")
class DockerSandboxEscapeTest {

  private static final Limits LIMITS = new Limits(10, 64, 64 * 1024);
  private static DockerSandbox sandbox;

  @BeforeAll
  static void requireEngine() throws Exception {
    var probe = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
        .redirectErrorStream(true).start();
    var available = probe.waitFor(30, TimeUnit.SECONDS) && probe.exitValue() == 0;
    if (!available && "true".equalsIgnoreCase(System.getenv("CI"))) {
      throw new IllegalStateException("CI must run the sandbox escape tests against a real engine");
    }
    assumeTrue(available, "No container engine here; the escape tests need one");
    sandbox = new DockerSandbox("docker");
  }

  private static Result python(String code) {
    return sandbox.run(Language.PYTHON, code, LIMITS);
  }

  private static Result python(String code, Limits limits) {
    return sandbox.run(Language.PYTHON, code, limits);
  }

  @Test
  @DisplayName("runs a program and returns its output and exit code")
  void runsCode() {
    var result = python("import sys\nprint('hello')\nprint('oops', file=sys.stderr)\nsys.exit(3)");
    assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
    assertThat(result.exitCode()).isEqualTo(3);
    assertThat(result.stdout()).isEqualTo("hello\n");
    assertThat(result.stderr()).isEqualTo("oops\n");

    var node = sandbox.run(Language.JAVASCRIPT, "console.log(6 * 7)", LIMITS);
    assertThat(node.stdout()).isEqualTo("42\n");
  }

  @Test
  @DisplayName("G1: no DNS, no host listener, no metadata endpoint, no internet")
  void noNetwork() throws Exception {
    try (var listener = new ServerSocket(0)) {
      var port = listener.getLocalPort();
      var result = python("""
          import socket
          def attempt(label, fn):
              try:
                  fn()
                  print(label, 'REACHED')
              except Exception as e:
                  print(label, 'blocked')
          attempt('dns', lambda: socket.gethostbyname('example.com'))
          attempt('host', lambda: socket.create_connection(('host.docker.internal', %d), 3))
          attempt('gateway', lambda: socket.create_connection(('172.17.0.1', %d), 3))
          attempt('metadata', lambda: socket.create_connection(('169.254.169.254', 80), 3))
          attempt('internet', lambda: socket.create_connection(('1.1.1.1', 443), 3))
          """.formatted(port, port), new Limits(60, 64, 64 * 1024));
      assertThat(result.stdout()).doesNotContain("REACHED").contains("dns blocked", "host blocked",
          "gateway blocked", "metadata blocked", "internet blocked");
    }
  }

  @Test
  @DisplayName("G2: the host filesystem is not there, and nothing outside the work directory is writable")
  void noHostFilesystem() throws Exception {
    var marker = Files.createTempFile("devforge-sandbox-marker", ".txt");
    var secret = UUID.randomUUID().toString();
    Files.writeString(marker, secret);
    try {
      var result = python("""
          import os
          print('marker', os.path.exists(%s))
          for path in ['/etc/passwd-test', '/usr/evil', '/evil']:
              try:
                  open(path, 'w').write('x')
                  print(path, 'WRITTEN')
              except OSError:
                  print(path, 'refused')
          os.symlink('/etc', '/work/out')
          try:
              open('/work/out/evil', 'w').write('x')
              print('symlink WRITTEN')
          except OSError:
              print('symlink refused')
          open('/work/ok', 'w').write('fine')
          print('work', open('/work/ok').read())
          """.formatted(pythonString(marker.toString())));
      assertThat(result.stdout()).contains("marker False", "symlink refused", "work fine")
          .doesNotContain("WRITTEN").doesNotContain(secret);
    } finally {
      Files.deleteIfExists(marker);
    }
  }

  @Test
  @DisplayName("G3: no credentials in the environment, no service-account files")
  void noAmbientCredentials() {
    var result = python("""
        import os
        print(sorted(os.environ.keys()))
        print('sa', os.path.exists('/var/run/secrets'))
        print('uid', os.getuid())
        """);
    assertThat(result.stdout()).doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("secret")
        .doesNotContainIgnoringCase("token").doesNotContain("DEVFORGE").doesNotContain("DOCKER")
        .contains("sa False", "uid 65534");
  }

  @Test
  @DisplayName("G4 and G6: a busy loop and a long sleep are both killed at the time limit")
  void boundedTime() {
    var limits = new Limits(3, 64, 64 * 1024);
    var busy = python("while True: pass", limits);
    assertThat(busy.outcome()).isEqualTo(Outcome.TIMED_OUT);
    assertThat(busy.durationMs()).isLessThan(15_000);

    var sleeper = python("import time\nprint('before the limit')\ntime.sleep(60)", limits);
    assertThat(sleeper.outcome()).isEqualTo(Outcome.TIMED_OUT);
    assertThat(sleeper.durationMs()).isLessThan(15_000);
    // What a killed program printed is kept: it is usually the clue to why it ran too long.
    assertThat(sleeper.stdout()).isEqualTo("before the limit\n");
  }

  @Test
  @DisplayName("G5: an allocation loop is killed for memory, and the next run is unaffected")
  void boundedMemory() {
    var result = python("hog = []\nwhile True: hog.append(bytearray(8 * 1024 * 1024))");
    assertThat(result.outcome()).isEqualTo(Outcome.MEMORY_EXCEEDED);
    assertThat(python("print('alive')").stdout()).isEqualTo("alive\n");
  }

  @Test
  @DisplayName("G7: endless output is cut off at the limit and the run is killed")
  void boundedOutput() {
    var limits = new Limits(10, 64, 4096);
    var result = python("while True: print('x' * 100)", limits);
    assertThat(result.outcome()).isEqualTo(Outcome.OUTPUT_EXCEEDED);
    assertThat(result.truncated()).isTrue();
    assertThat(result.stdout().length() + result.stderr().length()).isLessThanOrEqualTo(4096);
  }

  @Test
  @DisplayName("G8: a fork bomb hits the process limit, and the next run is unaffected")
  void boundedProcesses() {
    var result = python("""
        import os
        count = 0
        try:
            while True:
                if os.fork() == 0:
                    import time; time.sleep(30); os._exit(0)
                count += 1
        except OSError:
            print('stopped at', count)
        """, new Limits(5, 64, 64 * 1024));
    assertThat(result.stdout()).contains("stopped at");
    var count = Integer.parseInt(result.stdout().replaceAll("(?s).*stopped at (\\d+).*", "$1"));
    assertThat(count).isLessThan(64);
    assertThat(python("print('alive')").stdout()).isEqualTo("alive\n");
  }

  @Test
  @DisplayName("G9 and G10: a file written in one run is gone in the next")
  void noPersistence() {
    python("open('/work/left-behind', 'w').write('x')\nopen('/tmp/left-behind', 'w').write('x')");
    var result = python("import os\nprint(os.path.exists('/work/left-behind'), os.path.exists('/tmp/left-behind'))");
    assertThat(result.stdout()).isEqualTo("False False\n");
  }

  @Test
  @DisplayName("G11: a program that ignores SIGTERM is still killed")
  void ignoringTermDoesNotHelp() {
    var result = python("""
        import signal, time
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        while True: time.sleep(1)
        """, new Limits(3, 64, 64 * 1024));
    assertThat(result.outcome()).isEqualTo(Outcome.TIMED_OUT);
    assertThat(result.durationMs()).isLessThan(20_000);
  }

  @Test
  @DisplayName("G12: an unreachable engine refuses the run instead of running it some other way")
  void failsClosed() throws Exception {
    var marker = Files.createTempDirectory("devforge-g12").resolve("ran");
    var unreachable = new DockerSandbox("docker-binary-that-does-not-exist");
    assertThatThrownBy(() -> unreachable.run(Language.PYTHON,
        "open(%s, 'w').write('x')".formatted(pythonString(marker.toString())), LIMITS))
        .isInstanceOf(SandboxUnavailableException.class);
    assertThat(marker).doesNotExist();
  }

  private static String pythonString(String value) {
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
  }
}
