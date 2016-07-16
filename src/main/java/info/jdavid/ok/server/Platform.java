package info.jdavid.ok.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import static info.jdavid.ok.server.Logger.log;

abstract class Platform {

  private static final String JAVA_SPEC_VERSION = Runtime.class.getPackage().getSpecificationVersion();
  private static final Platform PLATFORM = findPlatform();

  public static Platform get() {
    return PLATFORM;
  }

  public abstract List<String> defaultProtocols();

  public abstract List<String> defaultCipherSuites();

  public abstract Object createSSLSocketParameters(final Https https);

  public abstract SSLSocket createSSLSocket(final Socket socket, final Https https) throws IOException;


  private static Platform findPlatform() {
    final Platform jdk9 = Jdk9Platform.buildIfSupported();
    if (jdk9 != null) return jdk9;
    final Platform jdkJettyBoot = JdkJettyBootPlatform.buildIfSupported();
    if (jdkJettyBoot != null) return jdkJettyBoot;
    final Platform jdk8 = Jdk8Platform.buildIfSupported();
    if (jdk8 != null) return jdk8;
    final Platform android = Android16Platform.buildIfSupported();
    if (android != null) return android;
    throw new RuntimeException("Unsupported platform.");
  }

  private static class Jdk9Platform extends Platform {

    static Platform buildIfSupported() {
      return JAVA_SPEC_VERSION.startsWith("1.9") ? new Jdk9Platform() : null;
    }

    private Jdk9Platform() {
      super();
      log("JDK9 Platform");
    }

    @Override public List<String> defaultProtocols() {
      return Collections.singletonList("TLSv1.2");
    }

    @Override public List<String> defaultCipherSuites() {
      return Arrays.asList(
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"
      );
    }

    @Override public Object createSSLSocketParameters(final Https https) {
      throw new UnsupportedOperationException("Not implemented.");
    }

    @Override public SSLSocket createSSLSocket(final Socket socket, final Https https) {
      throw new UnsupportedOperationException("Not implemented.");
    }

  }

  private static class JdkJettyBootPlatform extends Platform {

    static Platform buildIfSupported() {
      try {
        Class.forName("org.eclipse.jetty.alpn.ALPN");
      }
      catch (final ClassNotFoundException ignore) {
        return null;
      }
      return new JdkJettyBootPlatform();
    }

    private JdkJettyBootPlatform() {
      super();
      log("Jetty Boot Platform");
    }

    @Override public List<String> defaultProtocols() {
      return Collections.singletonList("TLSv1.2");
    }

    @Override public List<String> defaultCipherSuites() {
      return Arrays.asList(
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"
      );
    }

    @Override public Object createSSLSocketParameters(final Https https) {
      throw new UnsupportedOperationException("Not implemented.");
    }

    @Override public SSLSocket createSSLSocket(final Socket socket, final Https https) {
      throw new UnsupportedOperationException("Not implemented.");
    }

  }

  private static class Jdk8Platform extends Platform {

    static Platform buildIfSupported() {
      return JAVA_SPEC_VERSION.startsWith("1.8") ? new Jdk8Platform() : null;
    }

    private Jdk8Platform() {
      super();
      log("JDK8 Platform");
    }

    @Override public List<String> defaultProtocols() {
      return Collections.singletonList("TLSv1.2");
    }

    @Override public List<String> defaultCipherSuites() {
      return Arrays.asList(
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"
      );
    }

    @Override public Object createSSLSocketParameters(final Https https) {
      final SSLParameters parameters = new SSLParameters();
      parameters.setProtocols(https.protocols.toArray(new String[https.protocols.size()]));
      parameters.setCipherSuites(https.cipherSuites.toArray(new String[https.cipherSuites.size()]));
      return parameters;
    }

    @Override public SSLSocket createSSLSocket(final Socket socket, final Https https) throws IOException {
      if (https == null) return null;
      final Handshake handshake = Handshake.read(socket);
      final ByteArrayInputStream consumed = new ByteArrayInputStream(handshake.bytes);
      final SSLSocketFactory sslFactory = https.context.getSocketFactory();
      final SSLSocket sslSocket = (SSLSocket)sslFactory.createSocket(socket, consumed, true);
      sslSocket.setSSLParameters((SSLParameters)https.parameters);
      sslSocket.startHandshake();
      return sslSocket;
    }

  }

  private static class Android16Platform extends Platform {

    static Platform buildIfSupported() {
      try {
        Class.forName("android.os.Build");
      }
      catch (final ClassNotFoundException ignore) {
        return null;
      }
      final int version = android.os.Build.VERSION.SDK_INT;
      //noinspection ConstantConditions
      return version < 16 ? null : new Android16Platform(version);
    }

    private final int version;

    private Android16Platform(final int version) {
      super();
      log("Android Platform");
      this.version = version;
    }

    @Override public List<String> defaultProtocols() {
      return Collections.singletonList("TLSv1.2");
    }

    @Override public List<String> defaultCipherSuites() {
      return version < 20 ?
         Arrays.asList(
           "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
           "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
           "TLS_DHE_RSA_WITH_AES_128_CBC_SHA"
         ) :
         Arrays.asList(
          "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
          "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"
         );
    }

    @Override public Object createSSLSocketParameters(final Https https) {
      throw new UnsupportedOperationException("Not implemented.");
    }

    @Override public SSLSocket createSSLSocket(final Socket socket, final Https https) {
      throw new UnsupportedOperationException("Not implemented.");
    }

  }

}