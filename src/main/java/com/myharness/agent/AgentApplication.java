package com.myharness.agent;

import com.myharness.agent.config.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.util.concurrent.CountDownLatch;

@EnableConfigurationProperties(AgentProperties.class)
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(AgentApplication.class, args);
        CountDownLatch shutdown = new CountDownLatch(1);
        ApplicationListener<ContextClosedEvent> closeListener = event -> shutdown.countDown();
        context.addApplicationListener(closeListener);
        if (!context.isActive()) {
            return;
        }
        try {
            shutdown.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            context.close();
        }
    }
}
