package dev.langchain4j.http.client.jdk;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import okhttp3.*;
import okhttp3.internal.http2.StreamResetException;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static dev.langchain4j.http.client.sse.ServerSentEventListenerUtils.ignoringExceptions;
import static dev.langchain4j.internal.Utils.getOrDefault;

public class JdkHttpClient implements HttpClient {

    private final OkHttpClient delegate;
    private final Duration readTimeout;

    public JdkHttpClient(JdkHttpClientBuilder builder) {
        OkHttpClient.Builder okHttpClientBuilder = getOrDefault(builder.httpClientBuilder(), new OkHttpClient.Builder());
        if (builder.connectTimeout() != null) {
            okHttpClientBuilder.connectTimeout(builder.connectTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
        this.delegate = okHttpClientBuilder.build();
        this.readTimeout = builder.readTimeout();
    }

    public static JdkHttpClientBuilder builder() {
        return new JdkHttpClientBuilder();
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException {
        try {
            Request okHttpRequest = toOkHttpRequest(request);

            try (Response okHttpResponse = delegate.newCall(okHttpRequest).execute()) {
                if (!okHttpResponse.isSuccessful()) {
                    throw new HttpException(okHttpResponse.code(), okHttpResponse.body() != null ? okHttpResponse.body().string() : null);
                }

                String responseBody = okHttpResponse.body() != null ? okHttpResponse.body().string() : null;
                return fromOkHttpResponse(okHttpResponse, responseBody);
            }
        } catch (IOException e) {
            if (e instanceof StreamResetException || e.getMessage().contains("timeout")) {
                throw new TimeoutException(e);
            }
            throw new RuntimeException(e);
        }
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        Request okHttpRequest = toOkHttpRequest(request);

        delegate.newCall(okHttpRequest).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (e instanceof StreamResetException || e.getMessage().contains("timeout")) {
                    ignoringExceptions(() -> listener.onError(new TimeoutException(e)));
                } else {
                    ignoringExceptions(() -> listener.onError(e));
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    HttpException exception = new HttpException(response.code(), readBody(response));
                    ignoringExceptions(() -> listener.onError(exception));
                    return;
                }

                SuccessfulHttpResponse successfulResponse = fromOkHttpResponse(response, null);
                ignoringExceptions(() -> listener.onOpen(successfulResponse));

                try (ResponseBody responseBody = response.body()) {
                    if (responseBody != null) {
                        parser.parse(responseBody.byteStream(), listener);
                    }
                    ignoringExceptions(listener::onClose);
                }
            }
        });
    }

    private Request toOkHttpRequest(HttpRequest request) {
        Request.Builder builder = new Request.Builder()
                .url(request.url());

        request.headers().forEach((name, values) -> {
            if (values != null) {
                values.forEach(value -> builder.addHeader(name, value));
            }
        });

        if (request.body() != null) {
            builder.method(request.method().name(), RequestBody.create(request.body(), MediaType.parse("application/json")));
        } else {
            builder.method(request.method().name(), null);
        }

        return builder.build();
    }

    private static SuccessfulHttpResponse fromOkHttpResponse(Response response, String body) {
        return SuccessfulHttpResponse.builder()
                .statusCode(response.code())
                .headers(response.headers().toMultimap())
                .body(body)
                .build();
    }

    private static String readBody(Response response) {
        try (ResponseBody responseBody = response.body()) {
            return responseBody != null ? responseBody.string() : "Cannot read error response body";
        } catch (IOException e) {
            return "Cannot read error response body: " + e.getMessage();
        }
    }
}
