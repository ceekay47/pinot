/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.common.http;

import com.google.common.base.Throwables;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;


public class MultiHttpRequestTest {
  class TestResult {
    private final int _success;
    private final int _errors;
    private final int _timeouts;

    public TestResult(int success, int errors, int timeouts) {
      _success = success;
      _errors = errors;
      _timeouts = timeouts;
    }

    public int getSuccess() {
      return _success;
    }

    public int getErrors() {
      return _errors;
    }

    public int getTimeouts() {
      return _timeouts;
    }
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(MultiHttpRequest.class);
  private static final String SUCCESS_MSG = "success";
  private static final String ERROR_MSG = "error";
  private static final String TIMEOUT_MSG = "Timeout";
  private static final int SUCCESS_CODE = 200;
  private static final int ERROR_CODE = 500;
  private static final String URI_PATH = "/foo";
  private static final int TIMEOUT_MS = 5000;

  private final List<HttpServer> _servers = new ArrayList<>();
  private final int _portStart = 14000;

  @BeforeTest
  public void setUpTest()
      throws IOException {
    startServer(_portStart, createHandler(SUCCESS_CODE, SUCCESS_MSG, 0));
    startServer(_portStart + 1, createHandler(ERROR_CODE, ERROR_MSG, 0));
    startServer(_portStart + 2, createHandler(SUCCESS_CODE, TIMEOUT_MSG, TIMEOUT_MS));
    startServer(_portStart + 3, createPostHandler(SUCCESS_CODE, SUCCESS_MSG, 0));
  }

  @AfterTest
  public void tearDownTest() {
    for (HttpServer server : _servers) {
      server.stop(0);
    }
  }

  private HttpHandler createHandler(final int status, final String msg, final int sleepTimeMs) {
    return new HttpHandler() {
      @Override
      public void handle(HttpExchange httpExchange)
          throws IOException {
        if (sleepTimeMs > 0) {
          try {
            Thread.sleep(sleepTimeMs);
          } catch (InterruptedException e) {
            LOGGER.info("Handler interrupted during sleep");
          }
        }
        httpExchange.sendResponseHeaders(status, msg.length());
        OutputStream responseBody = httpExchange.getResponseBody();
        responseBody.write(msg.getBytes());
        responseBody.close();
      }
    };
  }

  private HttpHandler createPostHandler(final int status, final String msg, final int sleepTimeMs) {
    return new HttpHandler() {
      @Override
      public void handle(HttpExchange httpExchange)
          throws IOException {
        if (sleepTimeMs > 0) {
          try {
            Thread.sleep(sleepTimeMs);
          } catch (InterruptedException e) {
            LOGGER.info("Handler interrupted during sleep");
          }
        }
        if (httpExchange.getRequestMethod().equals("POST")) {
          httpExchange.sendResponseHeaders(status, msg.length());
          OutputStream responseBody = httpExchange.getResponseBody();
          responseBody.write(msg.getBytes());
          responseBody.close();
        } else {
          httpExchange.sendResponseHeaders(ERROR_CODE, ERROR_MSG.length());
          OutputStream responseBody = httpExchange.getResponseBody();
          responseBody.write(ERROR_MSG.getBytes());
          responseBody.close();
        }
      }
    };
  }

  private void startServer(int port, HttpHandler handler)
      throws IOException {
    final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext(URI_PATH, handler);
    new Thread(new Runnable() {
      @Override
      public void run() {
        server.start();
      }
    }).start();
    _servers.add(server);
  }

  @Test
  public void testMultiGet() {
    List<String> urls = Arrays.asList("http://localhost:" + String.valueOf(_portStart) + URI_PATH,
        "http://localhost:" + String.valueOf(_portStart + 1) + URI_PATH,
        "http://localhost:" + String.valueOf(_portStart + 2) + URI_PATH,
        // 2nd request to the same server
        "http://localhost:" + String.valueOf(_portStart) + URI_PATH,
        "http://localhost:" + String.valueOf(_portStart + 3) + URI_PATH);

    MultiHttpRequest mget =
        new MultiHttpRequest(Executors.newCachedThreadPool(), new PoolingHttpClientConnectionManager());

    // timeout value needs to be less than 5000ms set above for
    // third server
    final int requestTimeoutMs = 1000;
    CompletionService<MultiHttpRequestResponse> completionService = mget.executeGet(urls, null, requestTimeoutMs);

    TestResult result = collectResult(completionService, urls.size());
    Assert.assertEquals(result.getSuccess(), 2);
    Assert.assertEquals(result.getErrors(), 2);
    Assert.assertEquals(result.getTimeouts(), 1);
  }

  @Test
  public void testMultiPost() {
    List<Pair<String, String>> urlsAndRequestBodies =
        List.of(Pair.of("http://localhost:" + String.valueOf(_portStart) + URI_PATH, "b0"),
            Pair.of("http://localhost:" + String.valueOf(_portStart + 1) + URI_PATH, "b1"),
            Pair.of("http://localhost:" + String.valueOf(_portStart + 2) + URI_PATH, "b2"),
            // 2nd request to the same server
            Pair.of("http://localhost:" + String.valueOf(_portStart) + URI_PATH, "b3"),
            Pair.of("http://localhost:" + String.valueOf(_portStart + 3) + URI_PATH, "b4"));

    MultiHttpRequest mpost =
        new MultiHttpRequest(Executors.newCachedThreadPool(), new PoolingHttpClientConnectionManager());

    // timeout value needs to be less than 5000ms set above for
    // third server
    final int requestTimeoutMs = 1000;
    CompletionService<MultiHttpRequestResponse> completionService =
        mpost.executePost(urlsAndRequestBodies, null, requestTimeoutMs);

    TestResult result = collectResult(completionService, urlsAndRequestBodies.size());
    Assert.assertEquals(result.getSuccess(), 3);
    Assert.assertEquals(result.getErrors(), 1);
    Assert.assertEquals(result.getTimeouts(), 1);
  }

  private TestResult collectResult(CompletionService<MultiHttpRequestResponse> completionService, int size) {
    int success = 0;
    int errors = 0;
    int timeouts = 0;
    for (int i = 0; i < size; i++) {
      try (MultiHttpRequestResponse httpRequestResponse = completionService.take().get()) {
        if (httpRequestResponse.getResponse().getCode() >= 300) {
          errors++;
          Assert.assertEquals(EntityUtils.toString(httpRequestResponse.getResponse().getEntity()), ERROR_MSG);
        } else {
          success++;
          Assert.assertEquals(EntityUtils.toString(httpRequestResponse.getResponse().getEntity()), SUCCESS_MSG);
        }
      } catch (InterruptedException e) {
        LOGGER.error("Interrupted", e);
        errors++;
      } catch (ExecutionException e) {
        if (Throwables.getRootCause(e) instanceof SocketTimeoutException) {
          LOGGER.debug("Timeout");
          timeouts++;
        } else {
          LOGGER.error("Error", e);
          errors++;
        }
      } catch (Exception e) {
        errors++;
      }
    }
    return new TestResult(success, errors, timeouts);
  }
}
