package info.jdavid.ok.server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static info.jdavid.ok.server.Logger.log;

/**
 * The dispatcher is responsible for dispatching requests to workers.
 */
@SuppressWarnings({ "WeakerAccess", "Convert2Lambda", "Anonymous2MethodRef" })
public interface Dispatcher {

  /**
   * Starts the dispatcher.
   */
  public void start();

  /**
   * Dispatches a request.
   * @param request the request.
   */
  public void dispatch(final HttpServer.Request request);

  /**
   * Shuts down the dispatcher.
   */
  public void shutdown();

  /**
   * Default dispatcher. Requests are handled by a set of threads from a CachedThreadPool.
   */
  public static class Default implements Dispatcher {
    private ExecutorService mExecutors = null;
    @Override public void start() { mExecutors = Executors.newCachedThreadPool(); }
    @Override public void dispatch(final HttpServer.Request request) {
      mExecutors.execute(
        new Runnable() {
          @Override public void run() {
            request.serve();
          }
        }
      );
    }
    @Override public void shutdown() {
      mExecutors.shutdownNow();
      try {
        mExecutors.awaitTermination(5, TimeUnit.SECONDS);
      }
      catch (final InterruptedException ignore) {}
      mExecutors = null;
    }
  }

  /**
   * Variation on the default dispatcher that keeps track of the number of active connections.
   */
  @SuppressWarnings("unused")
  public static class Logged implements Dispatcher {
    private ExecutorService mExecutors = null;
    private final AtomicInteger mConnections = new AtomicInteger();
    @Override public void start() { mExecutors = Executors.newCachedThreadPool(); }
    @Override public void dispatch(final HttpServer.Request request) {
      mExecutors.execute(
        new Runnable() {
          @Override public void run() {
            log("Connections: " + mConnections.incrementAndGet());
            try {
              request.serve();
            }
            finally {
              log("Connections: " + mConnections.decrementAndGet());
            }
          }
        }
      );
    }
    @Override public void shutdown() {
      mExecutors.shutdownNow();
      try {
        mExecutors.awaitTermination(5, TimeUnit.SECONDS);
      }
      catch (final InterruptedException ignore) {}
      mExecutors = null;
    }
  }

  /**
   * Dispatcher implementation that simply runs the dispatch job synchronously on the current thread.
   */
  @SuppressWarnings("unused")
  public static class SameThreadDispatcher implements Dispatcher {
    @Override public void start() {}
    @Override public void dispatch(final HttpServer.Request request) {
      request.serve();
    }
    @Override public void shutdown() {}
  }

}
