package io.github.excalibase.config.datasource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Throwaway CA, server and client certificates (EC P-256), shaped like the CNPG
 * cluster CA and the client certificate provisioning issues to a platform role.
 */
final class TestPki {

  final String caCertificate;
  final String serverCertificate;
  final String serverKey;
  final String clientCertificate;
  final String clientKey;

  private TestPki(Path dir) throws IOException {
    this.caCertificate = Files.readString(dir.resolve("ca.crt"));
    this.serverCertificate = Files.readString(dir.resolve("server.crt"));
    this.serverKey = Files.readString(dir.resolve("server.key"));
    this.clientCertificate = Files.readString(dir.resolve("client.crt"));
    this.clientKey = Files.readString(dir.resolve("client.key"));
  }

  static TestPki generate(Path dir, String clientRole) throws IOException, InterruptedException {
    Files.writeString(dir.resolve("server.ext"),
        "subjectAltName=DNS:localhost,IP:127.0.0.1\n", StandardCharsets.UTF_8);
    run(dir, "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
        "-keyout", "ca.key", "-out", "ca.crt", "-days", "2", "-subj", "/CN=test-cluster-ca");
    issue(dir, "server", "/CN=localhost", "server.ext");
    issue(dir, "client", "/CN=" + clientRole, null);
    return new TestPki(dir);
  }

  private static void issue(Path dir, String name, String subject, String extFile)
      throws IOException, InterruptedException {
    run(dir, "req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
        "-keyout", name + ".key", "-out", name + ".csr", "-subj", subject);
    List<String> sign = new ArrayList<>(List.of("x509", "-req", "-in", name + ".csr",
        "-CA", "ca.crt", "-CAkey", "ca.key", "-CAcreateserial", "-days", "2", "-out", name + ".crt"));
    if (extFile != null) {
      sign.addAll(List.of("-extfile", extFile));
    }
    run(dir, sign.toArray(String[]::new));
  }

  private static void run(Path dir, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add("openssl");
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).directory(dir.toFile())
        .redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw new IllegalStateException("openssl " + String.join(" ", args) + " failed: " + output);
    }
  }
}
