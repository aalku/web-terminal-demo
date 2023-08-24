package org.aalku.demo.term;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	@Autowired
	private TermController termController;

	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(termController.getWsHandler(), "/ws-terminal").withSockJS();
	}

}
