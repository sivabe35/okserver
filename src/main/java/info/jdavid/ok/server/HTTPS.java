package info.jdavid.ok.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import static info.jdavid.ok.server.Logger.log;

/**
 * Https is used to access the server certificates required by the server for HTTPS.
 * Certificates should be in pkcs12 format.<br>
 * If necessary, you can convert an openssl certificate with: <br>
 *   <code>openssl pkcs12 -export -in path_to_certificate.crt -inkey path_to_key.key -out path_for_generated.p12 -passout pass:</code>
 */
@SuppressWarnings("WeakerAccess")
public final class Https {

  final SSLContext context;
  final Map<String, SSLContext> additionalContexts;
  final List<String> protocols;
  final List<String> cipherSuites;
  final Object parameters;

  private Https(final byte[] cert, final Map<String, byte[]> additionalCerts,
                final List<String> protocols, final List<String> cipherSuites) {
    context = createSSLContext(cert);
    additionalContexts = new HashMap<String, SSLContext>(additionalCerts.size());
    for (final Map.Entry<String, byte[]> entry: additionalCerts.entrySet()) {
      final SSLContext additionalContext = createSSLContext(entry.getValue());
      if (additionalContext != null) additionalContexts.put(entry.getKey(), additionalContext);
    }
    final Platform platform = Platform.get();
    this.protocols = protocols == null ? platform.defaultProtocols() : protocols;
    this.cipherSuites = cipherSuites == null ? platform.defaultCipherSuites() : cipherSuites;
    this.parameters = this.protocols.isEmpty() ? null : Platform.get().createSSLSocketParameters(this);
  }

  SSLSocket createSSLSocket(final Socket socket) throws IOException {
    return Platform.get().createSSLSocket(socket, this);
  }

  private static SSLContext createSSLContext(final byte[] certificate) {
    if (certificate == null) return null;
    final InputStream cert = new ByteArrayInputStream(certificate);
    try {
      final KeyStore keyStore = KeyStore.getInstance("PKCS12");
      keyStore.load(cert, new char[0]);
      final KeyManagerFactory kmf =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keyStore, new char[0]);
      final KeyStore trustStore = KeyStore.getInstance("JKS");
      trustStore.load(null, null);
      final TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(trustStore);
      final SSLContext context = SSLContext.getInstance("TLS");
      context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
      return context;
    }
    catch (final GeneralSecurityException e) {
      log(e);
      return null;
    }
    catch (final IOException e) {
      log(e);
      return null;
    }
    finally {
      try {
        cert.close();
      }
      catch (final IOException ignore) {}
    }
  }

  /**
   * Instance used for servers that don't use HTTPS.
   */
  public static final Https DISABLED =
    new Https(null, Collections.<String, byte[]>emptyMap(),
              Collections.<String>emptyList(), Collections.<String>emptyList());

  @SuppressWarnings({ "WeakerAccess", "unused" })
  public enum Protocol {
    SSL_3("SSLv3"), TLS_1("TLSv1"), TLS_1_1("TLSv1.1"), TLS_1_2("TLSv1.2");

    final String name;

    Protocol(final String name) {
      this.name = name;
    }

  }

  /**
   * Builder for the Https class.
   */
  @SuppressWarnings("unused")
  public static final class Builder {

    private List<String> protocols = null;
    private List<String> cipherSuites = null;
    private byte[] certificate = null;
    private final Map<String, byte[]> additionalCertificates = new HashMap<String, byte[]>(4);

    public Builder() {}

    /**
     * Sets the primary certificate.
     * @param bytes the certificate (pkcs12).
     * @return this.
     */
    public Builder certificate(final byte[] bytes) {
      if (bytes == null) throw new NullPointerException();
      if (certificate != null) throw new IllegalStateException("Main certificate already set.");
      certificate = bytes;
      return this;
    }

    /**
     * Adds an additional hostname certificate.
     * @param hostname the hostname.
     * @param bytes the certificate (pkcs12).
     * @return this.
     */
    public Builder addCertificate(final String hostname, final byte[] bytes) {
      if (hostname == null) throw new NullPointerException();
      if (bytes == null) throw new NullPointerException();
      if (additionalCertificates.containsKey(hostname)) {
        throw new IllegalStateException("Certificate for host \"" + hostname + "\" has already been set.");
      }
      additionalCertificates.put(hostname, bytes);
      return this;
    }

    /**
     * Sets the only allowed protocol.
     * @param protocol the protocol.
     * @return this.
     */
    public Builder protocol(final Protocol protocol) {
      protocols = Collections.singletonList(protocol.name);
      return this;
    }

    /**
     * Sets the list of allowed protocols.
     * @param protocols the protocols.
     * @return this.
     */
    public Builder protocols(final Protocol[] protocols) {
      final List<String> list = new ArrayList<String>(protocols.length);
      for (final Protocol protocol: protocols) {
        list.add(protocol.name);
      }
      this.protocols = list;
      return this;
    }

    /**
     * Sets the list of allowed cipher suites.
     * @param cipherSuites the cipher suites.
     * @return this.
     */
    public Builder cipherSuites(final String[] cipherSuites) {
      this.cipherSuites = Arrays.asList(cipherSuites);
      return this;
    }

    /**
     * Creates the Https instance.
     * @return the Https instance.
     */
    public Https build() {
      return new Https(certificate, additionalCertificates, protocols, cipherSuites);
    }

  }

}