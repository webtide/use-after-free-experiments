/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.losipiuk.jettyrace;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LargePostTest
{
    private static final Logger LOG = LoggerFactory.getLogger(LargePostTest.class);
    TestServer.StartedServer startedServer;
    HttpClient client;

    @BeforeEach
    public void startServer() throws Exception
    {
        startedServer = TestServer.start(0);
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new HttpClient();
        client.setMaxConnectionsPerDestination(10);
        client.getContentDecoderFactories().clear();
        client.start();
    }

    @AfterEach
    public void stopAll() throws Exception
    {
        LifeCycle.stop(client);
        startedServer.server().stop();
    }

    @Test
    public void testPosts() throws InterruptedException
    {
        final int postDataSize = 10_000_000;
        final int clientTotalRequests = 10000;
        final int clientThreads = 100;
        final int clientRequestsPerThread = clientTotalRequests / clientThreads;

        byte[] postData = new byte[postDataSize];
        ThreadLocalRandom.current().nextBytes(postData);

        URI uri = startedServer.serverURI().resolve("/");

        // ExecutorService executor = Executors.newFixedThreadPool(clientThreads + 1);
        try(ExecutorService executor = Executors.newCachedThreadPool())
        {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i <= clientThreads; i++)
            {
                tasks.add(new ClientPostTask(client, i, "/api/test/readlistener/", clientRequestsPerThread, uri, postData));
            }

            List<Future<Integer>> futures = executor.invokeAll(tasks, 2, TimeUnit.MINUTES);
            int count = 0;
            for (Future<Integer> fut : futures)
            {
                try
                {
                    count += fut.get();
                }
                catch (Throwable t)
                {
                    LOG.warn("Task Failure", t);
                }
            }
            LOG.info("Requests Made: {}", count);
            LifeCycle.stop(client);
            // sleep a bit, let the Exceptions flow
            TimeUnit.SECONDS.sleep(10);
            executor.shutdown();
        }
    }

    private static class ClientPostTask implements Callable<Integer>
    {
        private final HttpClient client;
        private final int clientId;
        private final int requestCount;
        private final URI uri;
        private final String path;
        private final byte[] postData;

        public ClientPostTask(HttpClient client, int id, String path, int requestCount, URI uri, byte[] postData)
        {
            this.client = client;
            this.clientId = id;
            this.path = path;
            this.requestCount = requestCount;
            this.uri = uri;
            this.postData = postData;
        }

        @Override
        public Integer call() throws Exception
        {
            int count = 0;
            for (int i = 0; i <= requestCount; i++)
            {
                ContentResponse response = client.newRequest(uri)
                    .method(HttpMethod.POST)
                    .path(path + "?id=%d&r=%d".formatted(clientId, i))
                    .body(new BytesRequestContent(postData))
                    .send();
                assertEquals(200, response.getStatus());
                count++;
                assertThat(response.getContentAsString(), notNullValue());
            }
            return count;
        }
    }
}
