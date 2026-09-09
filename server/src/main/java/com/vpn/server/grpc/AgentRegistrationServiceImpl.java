package com.vpn.server.grpc;

import com.vpn.server.grpc.agent.v1.AgentRegistrationServiceGrpc;
import com.vpn.server.grpc.agent.v1.RegisterNodeRequest;
import com.vpn.server.grpc.agent.v1.RegisterNodeResponse;
import com.vpn.server.service.NodeManagementService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AgentRegistrationServiceImpl extends AgentRegistrationServiceGrpc.AgentRegistrationServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistrationServiceImpl.class);
    private final NodeManagementService nodeManagementService;

    public AgentRegistrationServiceImpl(NodeManagementService nodeManagementService) {
        this.nodeManagementService = nodeManagementService;
    }

    @Override
    public void registerNode(RegisterNodeRequest request, StreamObserver<RegisterNodeResponse> responseObserver) {
        try {
            RegisterNodeResponse response = nodeManagementService.registerNode(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            log.warn("Node registration rejected: {}", e.getMessage());
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            log.error("Error during node registration", e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal registration error").asRuntimeException());
        }
    }
}
