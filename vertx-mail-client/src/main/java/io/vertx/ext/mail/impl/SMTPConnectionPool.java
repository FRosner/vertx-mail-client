package io.vertx.ext.mail.impl;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.impl.NoStackTraceThrowable;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.impl.LoggerFactory;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.ext.mail.MailConfig;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

class SMTPConnectionPool implements ConnectionLifeCycleListener {

  private static final Logger log = LoggerFactory.getLogger(SMTPConnectionPool.class);

  private final Vertx vertx;
  private final int maxSockets;
  private final boolean keepAlive;
  private final Queue<Waiter> waiters = new ArrayDeque<>();
  private final Set<SMTPConnection> allConnections = new HashSet<>();
  private final NetClient netClient;
  private final MailConfig config;
  private boolean closed = false;
  private int connCount;

  private Handler<Void> closeFinishedHandler;

  SMTPConnectionPool(Vertx vertx, MailConfig config) {
    this.vertx = vertx;
    this.config = config;
    maxSockets = config.getMaxPoolSize();
    keepAlive = config.isKeepAlive();
    NetClientOptions netClientOptions;
    if (config.getNetClientOptions() == null) {
      netClientOptions = new NetClientOptions().setSsl(config.isSsl()).setTrustAll(config.isTrustAll());
    } else {
      netClientOptions = config.getNetClientOptions();
    }
    netClient = vertx.createNetClient(netClientOptions);
  }

  // FIXME Why not use Handler<AsyncResult<SMTPConnection>> - that's what it's for
  void getConnection(Handler<SMTPConnection> resultHandler, Handler<Throwable> errorHandler) {
    log.debug("getConnection()");
    if (closed) {
      errorHandler.handle(new NoStackTraceThrowable("connection pool is closed"));
    } else {
      getConnection0(resultHandler, errorHandler);
    }
  }

  void close() {
    close(null);
  }

  synchronized void close(Handler<Void> finishedHandler) {
    if (closed) {
      throw new IllegalStateException("pool is already closed");
    } else {
      closed = true;
      closeFinishedHandler = finishedHandler;
      closeAllConnections();
    }
  }

  synchronized int connCount() {
    return connCount;
  }

  // Lifecycle methods

  // Called when the send operation has finished
  // TODO: this method should be called dataFinished,
  // responseEnded is from http
  // TODO: this method may not be called directly since it
  // doesn't set idle state on the connection
  public synchronized void responseEnded(SMTPConnection conn) {
    checkReuseConnection(conn);
  }

  // Called if the connection is actually closed, OR the connection attempt
  // failed - in the latter case conn will be null
  public synchronized void connectionClosed(SMTPConnection conn) {
    log.debug("connection closed, removing from pool");
    connCount--;
    if (conn != null) {
      allConnections.remove(conn);
    }
    Waiter waiter = waiters.poll();
    if (waiter != null) {
      // There's a waiter - so it can have a new connection
      log.debug("creating new connection for waiter");
      createNewConnection(waiter.handler, waiter.connectionExceptionHandler);
    }
    if (closed && connCount == 0) {
      log.debug("all connections closed, closing NetClient");
      netClient.close();
      if (closeFinishedHandler != null) {
        closeFinishedHandler.handle(null);
      }
    }
  }

  // Private methods

  private synchronized void getConnection0(Handler<SMTPConnection> handler,
      Handler<Throwable> connectionExceptionHandler) {
    SMTPConnection idleConn = null;
    for (SMTPConnection conn : allConnections) {
      if (!conn.isBroken() && conn.isIdle()) {
        idleConn = conn;
        break;
      }
    }
    if (idleConn == null && connCount >= maxSockets) {
      // Wait in queue
      log.debug("waiting for a free socket");
      waiters.add(new Waiter(handler, connectionExceptionHandler));
    } else {
      if (idleConn == null) {
        // Create a new connection
        log.debug("create a new connection");
        createNewConnection(handler, connectionExceptionHandler);
      } else {
        // if we have found a connection, run a RSET command, this checks if the connection
        // is really usable. If this fails, we create a new connection. we may run over the connection limit
        // since the close operation is not finished before we open the new connection, however it will be closed
        // shortly after
        log.debug("found idle connection, checking");
        final SMTPConnection conn = idleConn;
        conn.useConnection();
        new SMTPReset(conn, v -> handler.handle(conn), v -> {
          conn.setBroken();
          log.debug("using idle connection failed, create a new connection");
          createNewConnection(handler, connectionExceptionHandler);
        }).start();
      }
    }
  }

  private synchronized void checkReuseConnection(SMTPConnection conn) {
    if (conn.isBroken()) {
      log.debug("connection is broken, closing");
      conn.close();
    } else {
      // if the pool is disabled, just close the connection
      if (!keepAlive) {
        log.debug("connection pool is disabled, immediately doing QUIT");
        conn.close();
      } else {
        log.debug("checking for waiting operations");
        Waiter waiter = waiters.poll();
        if (waiter != null) {
          log.debug("running one waiting operation");
          conn.useConnection();
          waiter.handler.handle(conn);
        } else {
          log.debug("keeping connection idle");
          conn.setIdleTimer();
        }
      }
    }
  }

  private void closeAllConnections() {
    Set<SMTPConnection> copy;
    if (connCount > 0) {
      synchronized (this) {
        copy = new HashSet<>(allConnections);
        allConnections.clear();
      }
      // Close outside sync block to avoid deadlock
      for (SMTPConnection conn : copy) {
        if (conn.isIdle() || conn.isBroken()) {
          conn.close();
        } else {
          log.debug("closing connection after current send operation finishes");
          conn.setDoShutdown();
        }
      }
    } else {
      if (closeFinishedHandler != null) {
        closeFinishedHandler.handle(null);
      }
    }
  }

  private void createNewConnection(Handler<SMTPConnection> handler, Handler<Throwable> connectionExceptionHandler) {
    connCount++;
    createConnection(conn -> {
      allConnections.add(conn);
      handler.handle(conn);
    }, connectionExceptionHandler);
  }

  private void createConnection(Handler<SMTPConnection> resultHandler, Handler<Throwable> errorHandler) {
    SMTPConnection conn = new SMTPConnection(netClient, vertx, this);
    new SMTPStarter(vertx, conn, config, v -> resultHandler.handle(conn), errorHandler).start();
  }

  private static class Waiter {
    final Handler<SMTPConnection> handler;
    final Handler<Throwable> connectionExceptionHandler;

    private Waiter(Handler<SMTPConnection> handler, Handler<Throwable> connectionExceptionHandler) {
      this.handler = handler;
      this.connectionExceptionHandler = connectionExceptionHandler;
    }
  }
}