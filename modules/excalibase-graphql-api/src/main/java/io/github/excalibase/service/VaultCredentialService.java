package io.github.excalibase.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

public class VaultCredentialService {

  private static final Logger log = LoggerFactory.getLogger(VaultCredentialService.class);
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  // One or two segments of [A-Za-z0-9_-], each 1-64 chars, separated by '/'.
  // Bounded length, no '.'/'..' — safe to interpolate into the vault path.
  private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}(/[a-zA-Z0-9_-]{1,64})?$");

  private final String provisioningUrl;
  private final String provisioningPat;
  private final HttpClient httpClient;

  public VaultCredentialService(String provisioningUrl, String provisioningPat) {
    this.provisioningUrl = provisioningUrl;
    this.provisioningPat = provisioningPat;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .build();
  }

  /**
   * Fetch tenant DB credentials from the provisioner's vault by {@code projectId}.
   * {@code orgSlug} is accepted for logging only and is not part of the vault path.
   */
  public VaultCredentials fetchCredentials(String orgSlug, String projectId) {
    validateSlug(projectId, "projectId");
    String url = provisioningUrl + "/vault/secrets/projects/"
        + projectId + "/credentials/excalibase_app";
    try {
      HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .GET()
          .timeout(TIMEOUT);

      if (provisioningPat != null && !provisioningPat.isBlank()) {
        reqBuilder.header("Authorization", "Bearer " + provisioningPat);
      }

      HttpResponse<String> resp = httpClient.send(reqBuilder.build(),
          HttpResponse.BodyHandlers.ofString());

      if (resp.statusCode() != 200) {
        log.error("vault_credential_fetch_failed status={} tenant={}/{}", resp.statusCode(), orgSlug, projectId);
        throw new VaultCredentialException("Database not available for the requested project");
      }

      JsonNode json = mapper.readTree(resp.body());
      return new VaultCredentials(
          json.get("host").asText(),
          json.get("port").asText(),
          json.get("database").asText(),
          json.get("username").asText(),
          json.get("password").asText()
      );
    } catch (VaultCredentialException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VaultCredentialException(
          "Interrupted while fetching credentials for " + orgSlug + "/" + projectId, e);
    } catch (Exception e) {
      throw new VaultCredentialException(
          "Failed to fetch credentials for " + orgSlug + "/" + projectId, e);
    }
  }

  private static void validateSlug(String value, String field) {
    if (value == null || !SLUG_PATTERN.matcher(value).matches()) {
      throw new VaultCredentialException("Invalid " + field + ": must match [a-zA-Z0-9_-]{1,64}");
    }
  }
}
