package com.vpn.android.vpn;

import android.net.VpnService;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.SocketFactory;

/**
 * Sockets that bypass this app's own tunnel.
 *
 * Everything the VPN service itself sends while its TUN is up — API calls
 * during a reconnect, relay signaling, the censorship probe — would otherwise
 * be routed into that TUN like any app's traffic, i.e. into the broken tunnel
 * it is trying to diagnose or repair. Go-side sockets already get this through
 * libXray's DialerController; these are the Java-side ones.
 */
public final class ProtectedSocketFactory extends SocketFactory {

    private final VpnService service;

    public ProtectedSocketFactory(VpnService service) {
        this.service = service;
    }

    @Override
    public Socket createSocket() throws IOException {
        Socket socket = new Socket();
        // An unconnected java.net.Socket has no file descriptor until it is
        // bound, and protect() works on the descriptor. Binding to the
        // wildcard address with an ephemeral port changes nothing about where
        // connect() then goes.
        socket.bind(null);
        if (!service.protect(socket)) {
            socket.close();
            throw new IOException("VpnService.protect() refused the socket");
        }
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return createSocket(address, port);
    }
}
