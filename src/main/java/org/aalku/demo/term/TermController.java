package org.aalku.demo.term;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.aalku.demo.term.TermController.UpdateListener.BytesEvent;
import org.aalku.demo.term.TermController.UpdateListener.EofEvent;
import org.aalku.demo.term.TermController.UpdateListener.Stream;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

@RestController
public class TermController implements DisposableBean {
	
	private static final List<String> COMMAND = Arrays.asList("C:\\Windows\\System32\\cmd.exe");
	// private static final List<String> COMMAND = Arrays.asList("/bin/bash");
	
	private static final String SESSION_KEY_TERM_UUID = "TERM-UUID";

	private Logger log = LoggerFactory.getLogger(TermController.class);
	
	public final WebSocketHandler wsHandler = new AbstractWebSocketHandler() {
		
		@Override
		public boolean supportsPartialMessages() {
			return false;
		}
		
		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
			try {
				String s = message.getPayload();
				log.info("handleTextMessage: {}", s);
				if (s.startsWith("{")) {
					JSONObject o = new JSONObject(s);
					String to = o.getString("to");
					if (to.equals("tm")) {
						TermController.this.handleMessage(session, message, o.get("d"));
						return;
					}
				}
				sendMessage(session, "{ \"error\": \"No handler for message\"}");
			} catch (Exception e) {
				log.error(e.toString(), e);
				sendMessage(session, "{ \"error\": \"Internal error\"}");
			}
		}
	};
	
	public interface UpdateListener {
		public enum Stream { STDOUT, STDERR };
		public static abstract class Event {
			final Stream stream;	
			public Event(Stream stream) {
				this.stream = stream;
			}
		};
		public static class BytesEvent extends Event {
			public final byte[] bytes;
			public BytesEvent(Stream stream, byte[] bytes) {
				super(stream);
				this.bytes = bytes;
			}
		}
		public static class EofEvent extends Event {
			public EofEvent(Stream stream) {
				super(stream);
			}
		}
		public void update(Event bytesEvent);
	}

	public class TermSession {
		private final UUID uuid;
		private final PtyProcess process;
		private final UpdateListener updateListener;
		private final WebSocketSession wss;

		public TermSession(UUID uuid, Map<String, String> env, List<String> cmd, UpdateListener updateConsumer, WebSocketSession wss) throws IOException {
			this.uuid = uuid;
			
			Map<String, String> sessionEnv = new HashMap<>(System.getenv());
			sessionEnv.putAll(env);
			sessionEnv.put("TERM", "xterm");
			String[] commandArray = cmd.toArray(new String[0]);

			this.process = new PtyProcessBuilder().setCommand(commandArray).setRedirectErrorStream(false)
					.setInitialColumns(80).setInitialRows(30)
					.setEnvironment(sessionEnv)
					.start();
			this.updateListener = updateConsumer;
			this.wss = wss;
			stdOutThread(process.getInputStream(), UpdateListener.Stream.STDOUT).start();
			stdOutThread(process.getErrorStream(), UpdateListener.Stream.STDERR).start();
		}

		private Thread stdOutThread(InputStream out, Stream stream) {
			return new Thread(stream.toString() + "_Handler") {
				public void run() {
					byte[] buff = new byte[1024];
					try {
						try {
							while (true) {
								int n = out.read(buff);
								if (n < 0) {
									break;
								} else if (n > 0){
									synchronized (this) {
										updateListener.update(new UpdateListener.BytesEvent(stream, copyBuff(buff, n)));
									}
								}
							}
						} finally {
							updateListener.update(new UpdateListener.EofEvent(stream));
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				}

				private byte[] copyBuff(byte[] buff, int n) {
					byte[] b = new byte[n];
					System.arraycopy(buff, 0, b, 0, n);
					return b;
				};
			};
		}

		public UUID getUUID() {
			return uuid;
		}

		public void write(String string) throws IOException {
			byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
			// log.info("Bytes to pty ({}): {}", debugBytes(bytes), string);
			process.getOutputStream().write(bytes);
			process.getOutputStream().flush();
		}

		public void resized(int cols, int rows) {
			process.setWinSize(new WinSize(cols - 1, rows));
		}

		public boolean isClosed() {
			return !process.isAlive();
		}

		public WebSocketSession getWss() {
			return wss;
		}
		
	}
	
	private RemovalListener<UUID, TermSession> removalListener() {
		return new RemovalListener<UUID, TermController.TermSession>() {
			
			@Override
			public void onRemoval(@Nullable UUID key, @Nullable TermSession value, RemovalCause cause) {
				if (value.process.isAlive()) {
					value.process.destroyForcibly();
				}
			}
		};
	}
	
	private Cache<UUID, TermSession> sessions = (Cache<UUID, TermSession>) Caffeine.newBuilder()
			.expireAfterAccess(60, TimeUnit.MINUTES).removalListener(removalListener()).build();
	
	@PostMapping(path = "/session/{id}/resized")
	public @ResponseBody String resized(@PathVariable("id") String id, @RequestBody String payload) throws IOException, InterruptedException {
		TermSession s = sessions.getIfPresent(UUID.fromString(id));
		if (s == null) {
			JSONObject res = new JSONObject();
			res.put("error", "Session not found: " + id);
			return res.toString(2);
		} else {
			JSONObject req = new JSONObject(payload);
			JSONObject res = new JSONObject();
			res.put("req", req);
			s.resized(req.getInt("cols"), req.getInt("rows"));
			return res.toString(2);
		}
		
	}
	
	@Override
	public void destroy() throws Exception {
		sessions.invalidateAll();
		sessions.cleanUp();
	}

	private TermSession newSession(UpdateListener updateListener, WebSocketSession wss, List<String> command) throws IOException {
		TermSession session = new TermSession(UUID.randomUUID(), Collections.emptyMap(), command, updateListener, wss);
		sessions.put(session.getUUID(), session);
		return session;
	}

	public void handleMessage(WebSocketSession wss, TextMessage message, Object data) throws IOException {
		UUID uuid = (UUID) wss.getAttributes().get(SESSION_KEY_TERM_UUID);
		TermSession ts = uuid == null ? null : sessions.getIfPresent(uuid);

		if (data instanceof String) {
			if (data.equals("new-session")) {
				Encoder encoder = Base64.getEncoder();
				TermSession s = newSession(event->{
					JSONObject o = new JSONObject();
					if (event instanceof BytesEvent) {
						o.put("cause", "update");
						o.put("b64", encoder.encodeToString(((BytesEvent)event).bytes));
					} else if (event instanceof EofEvent) {
						o.put("cause", "EOF");
					}
					o.put("stream", event.stream);
					try {
						sendMessage(wss, o.toString());
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}, wss, COMMAND);
				wss.getAttributes().put(SESSION_KEY_TERM_UUID, s.getUUID());
				sendMessage(wss, "{ \"cause\": \"new-session\", \"sessionId\": \"" + s.getUUID().toString() + "\"}");
			}
		} else if (data instanceof JSONObject) {
			JSONObject o = (JSONObject) data;
			String event = o.optString("event");
			if (event != null) {
				if (event.equals("type")) {
					if (ts == null) {
						synchronized (wss) {
							sendMessage(wss, "{ \"error\": \"Session needed to type\"}");
						}
					} else {
						if (ts.isClosed()) {
							synchronized (wss) {
								sendMessage(wss, "{ \"error\": \"Session closed\"}");
							}
						} else {
							String text = o.getString("text");
							ts.write(text);
						}
					}
				}
			}
		}
	}

	@SuppressWarnings("unused")
	private String debugBytes(byte[] d) {
		StringBuilder sb = new StringBuilder(d.length * 3);
		for (byte b: d) {
			sb.append(String.format("%02x ", b & 0xFF));
		}
		return sb.toString();
	}

	public WebSocketHandler getWsHandler() {
		return wsHandler;
	}

	public void sendMessage(WebSocketSession wss, CharSequence msg) throws IOException {
		synchronized (wss) {
			wss.sendMessage(new TextMessage(msg));
		}
	}

}
