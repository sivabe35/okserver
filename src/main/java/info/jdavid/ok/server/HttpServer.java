package info.jdavid.ok.server;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.ResponseBody;
import com.squareup.okhttp.internal.http.HttpMethod;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;


public class HttpServer {

  @SuppressWarnings("unused")
  private static void log(final String...values) {
    switch (values.length) {
      case 0:
        return;
      case 1:
        System.out.println(values[0]);
        return;
      case 2:
        System.out.println(values[0] + ": " + values[1]);
        return;
      default:
        boolean addSeparator = false;
        for (final String value: values) {
          if (addSeparator) {
            System.out.print(' ');
          }
          else {
            addSeparator = true;
          }
          System.out.print(value);
        }
        System.out.println();
    }
  }

  private static void log(final Throwable t) {
    t.printStackTrace();
  }

  private final AtomicBoolean mStarted = new AtomicBoolean();
  private int mPort = 8080;
  private String mHostname = "localhost";
  private long mMaxRequestSize = 65536;
  private Thread mThread = null;
  private Dispatcher mDispatcher = null;

  /**
   * Sets the port number for the server.
   * @param port the port number.
   * @return this.
   */
  public HttpServer port(final int port) {
    if (mStarted.get()) {
      throw new IllegalStateException("The port number cannot be changed while the server is running.");
    }
    this.mPort = port;
    return this;
  }

  /**
   * Sets the host name for the server.
   * @param hostname the host name.
   * @return this.
   */
  public HttpServer hostname(final String hostname) {
    if (mStarted.get()) {
      throw new IllegalStateException("The host name cannot be changed while the server is running.");
    }
    this.mHostname = hostname;
    return this;
  }

  /**
   * Sets the maximum request size allowed by the server.
   * @param size the maximum request size in bytes.
   * @return this.
   */
  public HttpServer maxRequestSize(final long size) {
    if (mStarted.get()) {
      throw new IllegalStateException("The max request size cannot be changed while the server is running.");
    }
    this.mMaxRequestSize = size;
    return this;
  }

  /**
   * Sets a custom dispatcher.
   * @param dispatcher the dispatcher responsible for distributing the connection requests.
   * @return this
   */
  public HttpServer dispatcher(final Dispatcher dispatcher) {
    if (mStarted.get()) {
      throw new IllegalStateException("The dispatcher cannot be changed while the server is running.");
    }
    this.mDispatcher = dispatcher;
    return this;
  }

  /**
   * Shuts the server down.
   */
  public void shutdown() {
    if (!mStarted.get()) return;
    mThread.interrupt();
  }

  /**
   * Starts the server.
   */
  public void start() {
    if (mStarted.getAndSet(true)) {
      throw new IllegalStateException("The server has already been started.");
    }
    try {
      final Dispatcher dispatcher = mDispatcher == null ? createDefaultDispatcher() : mDispatcher;
      dispatcher.start();
      final ServerSocket serverSocket =
        new ServerSocket(mPort, -1, InetAddress.getByName(mHostname));
      serverSocket.setReuseAddress(true);
      (mThread = new Thread(new Runnable() {
        @Override public void run() {
          try {
            while (true) { dispatch(dispatcher, serverSocket.accept()); }
          }
          catch (final IOException e) { log(e); }
          finally {
            try { serverSocket.close(); } catch (final IOException ignore) {}
            try { dispatcher.shutdown(); } catch (final Exception e) { log(e); }
            mStarted.set(false);
          }
        }
      })).start();
    }
    catch (final IOException e) {
      log(e);
    }
  }

  public final class Request {
    private final Socket mSocket;
    private Request(final Socket socket) { mSocket = socket; }
    public void serve() { HttpServer.this.serve(mSocket); }
  }

  protected void dispatch(final Dispatcher dispatcher, final Socket socket) {
    dispatcher.dispatch(new Request(socket));
  }

  protected Dispatcher createDefaultDispatcher() {
    return new Dispatcher.Default();
  }

  private void serve(final Socket socket) {
    try {
      final BufferedSource in = Okio.buffer(Okio.source(socket));
      final BufferedSink out = Okio.buffer(Okio.sink(socket));
      try {
        final String request = in.readUtf8LineStrict();
        if (request == null || request.length() == 0) return;
        final int index1 = request.indexOf(' ');
        if (index1 == -1) throw new IllegalStateException();
        final String method = request.substring(0, index1);
        final int index2 = request.indexOf(' ', index1 + 1);
        if (index2 == -1) throw new IllegalStateException();
        final String path = request.substring(index1 + 1, index2);
        final Headers.Builder headersBuilder = new Headers.Builder();
        String header;
        while ((header = in.readUtf8LineStrict()).length() != 0) {
          headersBuilder.add(header);
        }
        final boolean useBody = HttpMethod.permitsRequestBody(method);
        final Response response;
        if ("100-continue".equals(headersBuilder.get("Expect"))) {
          response = new Response.Builder().
            statusLine(StatusLines.CONTINUE).noBody().build();
        }
        else {
          final String contentLength = headersBuilder.get("Content-Length");
          final long length = contentLength == null ? -1 : Long.parseLong(contentLength);
          if (length > mMaxRequestSize) {
            response = new Response.Builder().
              statusLine(StatusLines.PAYLOAD_TOO_LARGE).noBody().build();
          }
          else {
            if (length == 0) {
              response = handle(method, path, headersBuilder.build(), null);
            }
            else {
              if (length > 0) {
                if (useBody) {
                  final Buffer body = new Buffer();
                  if (!socket.isClosed()) in.readFully(body, length);
                  body.flush();
                  response = handle(method, path, headersBuilder.build(), body);
                }
                else {
                  response = handle(method, path, headersBuilder.build(), null);
                }
              }
              else if ("chunked".equals(headersBuilder.get("Transfer-encoding"))) {
                if (useBody) {
                  final Buffer body = new Buffer();
                  long total = 0L;
                  while (true) {
                    final long chunkSize = Long.parseLong(in.readUtf8LineStrict().trim(), 16);
                    total += chunkSize;
                    if (chunkSize == 0) {
                      if (in.readUtf8LineStrict().length() != 0) throw new IllegalStateException();
                      break;
                    }
                    if (total > mMaxRequestSize) {
                      break;
                    }
                    if (!socket.isClosed()) in.read(body, chunkSize);
                    body.flush();
                    if (in.readUtf8LineStrict().length() != 0) throw new IllegalStateException();
                  }
                  if (total > mMaxRequestSize) {
                    response = new Response.Builder().
                      statusLine(StatusLines.PAYLOAD_TOO_LARGE).noBody().build();
                  }
                  else {
                    response = handle(method, path, headersBuilder.build(), body);
                  }
                }
                else {
                  response = handle(method, path, headersBuilder.build(), null);
                }
              }
              else {
                if (useBody) {
                  response = new Response.Builder().
                    statusLine(StatusLines.LENGTH_REQUIRED).noBody().build();
                }
                else {
                  response = handle(method, path, headersBuilder.build(), null);
                }
              }
            }
          }
        }
        final Response r =
          response == null ?
          new Response.Builder().statusLine(StatusLines.NOT_FOUND).noBody().build() :
          response;

        out.writeUtf8(r.protocol().toString().toUpperCase(Locale.US));
        out.writeUtf8(" ");
        out.writeUtf8(String.valueOf(r.code()));
        out.writeUtf8(" ");
        out.writeUtf8(r.message());
        out.writeUtf8("\r\n");
        final Headers headers = r.headers();
        final int headersSize = headers.size();
        for (int i=0; i<headersSize; ++i) {
          out.writeUtf8(headers.name(i));
          out.writeUtf8(": ");
          out.writeUtf8(headers.value(i));
          out.writeUtf8("\r\n");
        }
        out.writeUtf8("\r\n");
        out.flush();
        final ResponseBody data = r.body();
        if (data != null ) {
          final long length = data.contentLength();
          if (length > 0) {
            out.write(data.source(), data.contentLength());
            out.flush();
          }
          else if (length < 0) {
            final Buffer buffer = new Buffer();
            final long step = 65536;
            long read;
            while((read = data.source().read(buffer, step)) > 0) {
              buffer.flush();
              out.write(buffer, read);
              out.flush();
            }
          }
        }
      }
      finally {
        try { in.close(); } catch (final IOException ignore) {}
        try { out.close(); } catch (final IOException ignore) {}
      }
    }
    catch (final IOException e) {
      log(e);
    }
    finally {
      try { socket.close(); } catch (final IOException ignore) {}
    }
  }

  protected Response handle(final String method, final String path,
                            final Headers requestHeaders, final Buffer requestBody) {
    final Response.Builder builder = new Response.Builder();
    if ("/test".equals(path)) {
      builder.statusLine(StatusLines.OK);
      builder.headers(requestHeaders);
      if (requestBody != null) {
        final MediaType mediaType = MediaType.parse(requestHeaders.get("Content-Type"));
        builder.body(new Response.BufferResponse(mediaType, requestBody));
      }
      else {
        builder.noBody();
      }
    }
    else if ("GET".equalsIgnoreCase(method)) {
      builder.statusLine(StatusLines.NOT_FOUND).noBody();
    }
    else {
      builder.statusLine(StatusLines.METHOD_NOT_ALLOWED).noBody();
    }
    return builder.build();
  }

}
