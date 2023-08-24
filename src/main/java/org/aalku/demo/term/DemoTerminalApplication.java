package org.aalku.demo.term;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class DemoTerminalApplication implements ApplicationRunner {

	@SuppressWarnings("unused")
	private static ConfigurableApplicationContext ctx;

	public static void main(String[] args) {
		ctx = SpringApplication.run(DemoTerminalApplication.class, args);
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
	}
}
