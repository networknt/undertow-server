package com.networknt.sanitizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.networknt.client.Http2Client;
import com.networknt.config.Config;
import com.networknt.exception.ClientException;
import com.networknt.sanitizer.enconding.EncoderRegistry;
import com.networknt.sanitizer.enconding.Encoding;
import io.undertow.Undertow;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.IoUtils;
import org.xnio.OptionMap;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SanitizerHandlerWithEncodeType {

    private static final Logger LOGGER = LoggerFactory.getLogger(SanitizerHandlerWithEncodeTest.class);

    private static Undertow server = null;

    @BeforeClass
    public static void setUp() {
        EncoderRegistry.registry(new ExampleEncode());

        if(server == null) {
            LOGGER.info("starting server");
            server = ServerBuilder.newServer().withConfigName("sanitizer_with_encode_type").build();
            server.start();
        }
    }

    @AfterClass
    public static void tearDown() {
        if(server != null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {

            }
            server.stop();
            LOGGER.info("The server is stopped.");
        }
    }


    @Test
    public void testPostBody() throws Exception {
        final AtomicReference<ClientResponse> reference = new AtomicReference<>();
        final Http2Client client = Http2Client.getInstance();
        final CountDownLatch latch = new CountDownLatch(1);
        final ClientConnection connection;
        try {
            connection = client.connect(new URI("http://localhost:8080"), Http2Client.WORKER, Http2Client.SSL, Http2Client.BUFFER_POOL, OptionMap.EMPTY).get();
        } catch (Exception e) {
            throw new ClientException(e);
        }

        try {
            String post = "{\"key\":\"<script>alert('test')</script>\"}";
            connection.getIoThread().execute(new Runnable() {
                @Override
                public void run() {
                    final ClientRequest request = new ClientRequest().setMethod(Methods.POST).setPath("/body");
                    request.getRequestHeaders().put(Headers.HOST, "localhost");
                    request.getRequestHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    request.getRequestHeaders().put(Headers.TRANSFER_ENCODING, "chunked");
                    connection.sendRequest(request, client.createClientCallback(reference, latch, post));
                }
            });

            latch.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.error("IOException: ", e);
            throw new ClientException(e);
        } finally {
            IoUtils.safeClose(connection);
        }
        int statusCode = reference.get().getResponseCode();
        Assert.assertEquals(200, statusCode);
        if(statusCode == 200) {
            String body = reference.get().getAttachment(Http2Client.RESPONSE_BODY);
            Assert.assertNotNull(body);
            Map map = Config.getInstance().getMapper().readValue(body, new TypeReference<HashMap<String, Object>>() {});
            Assert.assertEquals("<script>example('test')</script>", map.get("key"));
        }
    }

    static class ExampleEncode implements Encoding {

        @Override
        public String getId() {
            return "example";
        }

        @Override
        public String apply(String data) {
            return data.replace("alert", "example");
        }
    }
}
