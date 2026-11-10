package de.metis.mail.module;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClamdClientV2 {
    private static final Logger LOG = Logger.getLogger(ClamdClientV2.class.getName());
    private final String host;
    private final int port;
    private final int timeoutMs;

    public ClamdClientV2(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    public boolean ping() {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            byte[] pingCmd = new byte[] {
                'z', 'P', 'I', 'N', 'G', 0
            };
            out.write(pingCmd);
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            String response = readLine(in);
            return "PONG".equals(response);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Clamd ping failed", e);
            return false;
        }
    }

    public String scan(byte[] data) {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            byte[] header = new byte[] {
                'z', 'I', 'N', 'S', 'T', 'R', 'E', 'A', 'M', 0
            };
            out.write(header);

            int chunkSize = 4096;
            int offset = 0;
            while (offset < data.length) {
                int length = Math.min(chunkSize, data.length - offset);
                out.write(intToBytes(length));
                out.write(data, offset, length);
                offset += length;
            }

            out.write(intToBytes(0));
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            String response = readLine(in);
            
            if (response.startsWith("stream: OK")) {
                return "CLEAN";
            } else if (response.startsWith("stream: ")) {
                int idx = response.indexOf(" FOUND");
                if (idx != -1) {
                    return response.substring("stream: ".length(), idx);
                }
                return response;
            }
            return "ERROR: " + response;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Clamd scan failed", e);
            return "ERROR: " + e.getMessage();
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        return socket;
    }

    private String readLine(DataInputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int read;
        while ((read = in.read()) != -1) {
            if (read == '\r') continue;
            if (read == '\n') break;
            buffer.write(read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.US_ASCII);
    }

    private byte[] intToBytes(int value) {
        return new byte[] {
            (byte) (value >> 24),
            (byte) (value >> 16),
            (byte) (value >> 8),
            (byte) value
        };
    }
}