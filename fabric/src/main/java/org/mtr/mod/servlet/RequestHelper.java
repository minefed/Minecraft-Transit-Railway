package org.mtr.mod.servlet;


import org.mtr.core.Main;
import org.mtr.libraries.okhttp3.*;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class RequestHelper {

	private final OkHttpClient okHttpClient = new OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).writeTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build();

	public void sendRequest(String url, @Nullable String content, @Nullable BiConsumer<String, String> callback) {
		sendRequest(url, content, callback, null);
	}

	public void sendRequest(String url, @Nullable String content, @Nullable BiConsumer<String, String> callback, @Nullable Consumer<IOException> failureCallback) {
		final Request.Builder requestBuilder = new Request.Builder().url(url);
		final Request request;
		if (content == null) {
			request = requestBuilder.get().build();
		} else {
			request = requestBuilder.post(RequestBody.create(content, MediaType.get("application/json"))).build();
		}

		final Call call = okHttpClient.newCall(request);
		call.enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {
				handleFailure(call, e, failureCallback);
			}

			@Override
			public void onResponse(Call call, Response response) {
				try (final ResponseBody responseBody = response.body()) {
					if (responseBody == null) {
						handleFailure(call, new IOException("Received an HTTP response without a body"), failureCallback);
					} else if (callback != null) {
						callback.accept(responseBody.string(), response.request().url().url().getFile());
					}
				} catch (IOException e) {
					handleFailure(call, e, failureCallback);
				}
			}
		});
	}

	private static void handleFailure(Call call, IOException exception, @Nullable Consumer<IOException> failureCallback) {
		if (!(exception instanceof InterruptedIOException)) {
			Main.LOGGER.error(call.request().url(), exception);
		}
		if (failureCallback != null) {
			try {
				failureCallback.accept(exception);
			} catch (Exception callbackException) {
				Main.LOGGER.error("Failed to process an HTTP request failure", callbackException);
			}
		}
	}
}
