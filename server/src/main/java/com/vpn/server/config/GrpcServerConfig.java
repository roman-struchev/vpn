package com.vpn.server.config;

import com.vpn.server.grpc.AgentRegistrationServiceImpl;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class GrpcServerConfig implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerConfig.class);

    private final int port;
    private final AgentRegistrationServiceImpl registrationService;
    private final AgentStreamServiceImpl streamService;

    private Server server;
    private boolean isRunning = false;

    public GrpcServerConfig(
            @Value("${grpc.server.port:9090}") int port,
            AgentRegistrationServiceImpl registrationService,
            AgentStreamServiceImpl streamService) {
        this.port = port;
        this.registrationService = registrationService;
        this.streamService = streamService;
    }

    @Override
    public void start() {
        if (port <= 0) {
            log.info("gRPC server disabled (port <= 0)");
            return;
        }
        try {
            this.server = ServerBuilder.forPort(port)
                    .addService(registrationService)
                    .addService(streamService)
                    .build()
                    .start();
            this.isRunning = true;
            log.info("gRPC Server started on port {}", port);
        } catch (IOException e) {
            log.error("Failed to start gRPC server on port {}", port, e);
            throw new RuntimeException("Could not start gRPC server", e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            log.info("Shutting down gRPC Server...");
            server.shutdown();
            this.isRunning = false;
        }
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }
}
