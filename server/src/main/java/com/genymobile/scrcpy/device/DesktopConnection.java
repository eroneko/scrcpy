package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.StringUtils;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class DesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;

    private static final String SOCKET_NAME_PREFIX = "scrcpy";

    private interface Endpoint extends Closeable {
        FileDescriptor getFileDescriptor();

        InputStream getInputStream() throws IOException;

        OutputStream getOutputStream() throws IOException;

        void shutdown() throws IOException;
    }

    private static final class LocalSocketEndpoint implements Endpoint {

        private final LocalSocket socket;

        LocalSocketEndpoint(LocalSocket socket) {
            this.socket = socket;
        }

        @Override
        public FileDescriptor getFileDescriptor() {
            return socket.getFileDescriptor();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public void shutdown() throws IOException {
            socket.shutdownInput();
            socket.shutdownOutput();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class TcpSocketEndpoint implements Endpoint {

        private final Socket socket;
        private final ParcelFileDescriptor parcelFileDescriptor;
        private final FileDescriptor fileDescriptor;

        TcpSocketEndpoint(Socket socket) throws IOException {
            this.socket = socket;
            parcelFileDescriptor = ParcelFileDescriptor.fromSocket(socket);
            fileDescriptor = parcelFileDescriptor.getFileDescriptor();
        }

        @Override
        public FileDescriptor getFileDescriptor() {
            return fileDescriptor;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public void shutdown() throws IOException {
            socket.shutdownInput();
            socket.shutdownOutput();
        }

        @Override
        public void close() throws IOException {
            IOException error = null;
            try {
                parcelFileDescriptor.close();
            } catch (IOException e) {
                error = e;
            }
            try {
                socket.close();
            } catch (IOException e) {
                if (error == null) {
                    error = e;
                }
            }
            if (error != null) {
                throw error;
            }
        }
    }

    private final Endpoint videoEndpoint;
    private final FileDescriptor videoFd;

    private final Endpoint audioEndpoint;
    private final FileDescriptor audioFd;

    private final Endpoint controlEndpoint;
    private final ControlChannel controlChannel;

    private DesktopConnection(Endpoint videoEndpoint, Endpoint audioEndpoint, Endpoint controlEndpoint) throws IOException {
        this.videoEndpoint = videoEndpoint;
        this.audioEndpoint = audioEndpoint;
        this.controlEndpoint = controlEndpoint;

        videoFd = videoEndpoint != null ? videoEndpoint.getFileDescriptor() : null;
        audioFd = audioEndpoint != null ? audioEndpoint.getFileDescriptor() : null;
        controlChannel = controlEndpoint != null
                ? new ControlChannel(controlEndpoint.getInputStream(), controlEndpoint.getOutputStream())
                : null;
    }

    private static LocalSocket connect(String abstractName) throws IOException {
        LocalSocket localSocket = new LocalSocket();
        localSocket.connect(new LocalSocketAddress(abstractName));
        return localSocket;
    }

    private static String getSocketName(int scid) {
        if (scid == -1) {
            // If no SCID is set, use "scrcpy" to simplify using scrcpy-server alone
            return SOCKET_NAME_PREFIX;
        }

        return SOCKET_NAME_PREFIX + String.format("_%08x", scid);
    }

    private static void closeSilently(Endpoint endpoint) {
        if (endpoint != null) {
            try {
                endpoint.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    public static DesktopConnection open(int scid, boolean tunnelForward, boolean video, boolean audio, boolean control,
            boolean sendDummyByte, String listenAddress, int listenPort) throws IOException {
        if (listenAddress != null || listenPort != -1) {
            return openTcp(listenAddress, listenPort, video, audio, control, sendDummyByte);
        }

        return openLocal(scid, tunnelForward, video, audio, control, sendDummyByte);
    }

    private static DesktopConnection openLocal(int scid, boolean tunnelForward, boolean video, boolean audio, boolean control,
            boolean sendDummyByte) throws IOException {
        String socketName = getSocketName(scid);

        Endpoint videoEndpoint = null;
        Endpoint audioEndpoint = null;
        Endpoint controlEndpoint = null;
        try {
            if (tunnelForward) {
                try (LocalServerSocket localServerSocket = new LocalServerSocket(socketName)) {
                    if (video) {
                        videoEndpoint = new LocalSocketEndpoint(localServerSocket.accept());
                        if (sendDummyByte) {
                            videoEndpoint.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (audio) {
                        audioEndpoint = new LocalSocketEndpoint(localServerSocket.accept());
                        if (sendDummyByte) {
                            audioEndpoint.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (control) {
                        controlEndpoint = new LocalSocketEndpoint(localServerSocket.accept());
                        if (sendDummyByte) {
                            controlEndpoint.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                }
            } else {
                if (video) {
                    videoEndpoint = new LocalSocketEndpoint(connect(socketName));
                }
                if (audio) {
                    audioEndpoint = new LocalSocketEndpoint(connect(socketName));
                }
                if (control) {
                    controlEndpoint = new LocalSocketEndpoint(connect(socketName));
                }
            }

            DesktopConnection connection = new DesktopConnection(videoEndpoint, audioEndpoint, controlEndpoint);
            videoEndpoint = null;
            audioEndpoint = null;
            controlEndpoint = null;
            return connection;
        } finally {
            closeSilently(videoEndpoint);
            closeSilently(audioEndpoint);
            closeSilently(controlEndpoint);
        }
    }

    private static DesktopConnection openTcp(String listenAddress, int listenPort, boolean video, boolean audio, boolean control,
            boolean sendDummyByte) throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        Endpoint videoEndpoint = null;
        Endpoint audioEndpoint = null;
        Endpoint controlEndpoint = null;
        try {
            serverSocket.setReuseAddress(true);
            int port = listenPort >= 0 ? listenPort : 0;
            InetSocketAddress socketAddress;
            if (listenAddress != null && !listenAddress.isEmpty()) {
                InetAddress inetAddress = InetAddress.getByName(listenAddress);
                socketAddress = new InetSocketAddress(inetAddress, port);
            } else {
                socketAddress = new InetSocketAddress(port);
            }
            serverSocket.bind(socketAddress);

            if (video) {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                videoEndpoint = new TcpSocketEndpoint(socket);
                if (sendDummyByte) {
                    videoEndpoint.getOutputStream().write(0);
                    sendDummyByte = false;
                }
            }
            if (audio) {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                audioEndpoint = new TcpSocketEndpoint(socket);
                if (sendDummyByte) {
                    audioEndpoint.getOutputStream().write(0);
                    sendDummyByte = false;
                }
            }
            if (control) {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                controlEndpoint = new TcpSocketEndpoint(socket);
                if (sendDummyByte) {
                    controlEndpoint.getOutputStream().write(0);
                    sendDummyByte = false;
                }
            }

            DesktopConnection connection = new DesktopConnection(videoEndpoint, audioEndpoint, controlEndpoint);
            videoEndpoint = null;
            audioEndpoint = null;
            controlEndpoint = null;
            return connection;
        } finally {
            closeSilently(videoEndpoint);
            closeSilently(audioEndpoint);
            closeSilently(controlEndpoint);
            try {
                serverSocket.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private Endpoint getFirstEndpoint() {
        if (videoEndpoint != null) {
            return videoEndpoint;
        }
        if (audioEndpoint != null) {
            return audioEndpoint;
        }
        return controlEndpoint;
    }

    public void shutdown() throws IOException {
        if (videoEndpoint != null) {
            videoEndpoint.shutdown();
        }
        if (audioEndpoint != null) {
            audioEndpoint.shutdown();
        }
        if (controlEndpoint != null) {
            controlEndpoint.shutdown();
        }
    }

    public void close() throws IOException {
        if (videoEndpoint != null) {
            videoEndpoint.close();
        }
        if (audioEndpoint != null) {
            audioEndpoint.close();
        }
        if (controlEndpoint != null) {
            controlEndpoint.close();
        }
    }

    public void sendDeviceMeta(String deviceName) throws IOException {
        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH];

        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);
        // byte[] are always 0-initialized in java, no need to set '\0' explicitly

        FileDescriptor fd = getFirstEndpoint().getFileDescriptor();
        IO.writeFully(fd, buffer, 0, buffer.length);
    }

    public FileDescriptor getVideoFd() {
        return videoFd;
    }

    public FileDescriptor getAudioFd() {
        return audioFd;
    }

    public ControlChannel getControlChannel() {
        return controlChannel;
    }
}
