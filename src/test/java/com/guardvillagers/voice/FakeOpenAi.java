package com.guardvillagers.voice;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** A local stand-in for the OpenAI API: records requests and replies with a scripted response. */
final class FakeOpenAi implements AutoCloseable {
	record Request(String method, String path, Map<String, List<String>> headers, byte[] body) {
		String header(String name) {
			for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
				if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue().getFirst();
			}
			return null;
		}
	}

	/** {@code chunked} streams the body without Content-Length so the client must cap while reading. */
	record Response(int status, Map<String, String> headers, byte[] body, boolean chunked) {
		static Response json(String json) {
			return new Response(200, Map.of("Content-Type", "application/json"), json.getBytes(), false);
		}

		static Response bytes(byte[] body) {
			return new Response(200, Map.of("Content-Type", "application/octet-stream"), body, false);
		}
	}

	private final HttpServer server;
	final List<Request> requests = new CopyOnWriteArrayList<>();
	volatile Response response = Response.json("{\"text\":\"\"}");

	FakeOpenAi() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/v1/", this::handle);
		server.start();
	}

	String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
	}

	OpenAiHttp http() {
		return OpenAiHttp.create(baseUrl(), "sk-test-key");
	}

	private void handle(HttpExchange exchange) throws IOException {
		byte[] body = exchange.getRequestBody().readAllBytes();
		requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
				Map.copyOf(exchange.getRequestHeaders()), body));
		Response current = response;
		current.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
		exchange.sendResponseHeaders(current.status(), current.chunked() ? 0 : current.body().length == 0 ? -1 : current.body().length);
		try (OutputStream out = exchange.getResponseBody()) {
			if (current.body().length > 0) out.write(current.body());
		} catch (IOException clientAborted) {
			// The client stops reading once a response passes its cap.
		}
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
