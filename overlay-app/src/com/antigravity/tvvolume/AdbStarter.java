package com.antigravity.tvvolume;

import android.util.Base64;
import android.util.Log;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

public class AdbStarter {
    private static final String TAG = "TvVolumeAdbStarter";

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int A_VERSION = 0x01000000;
    private static final int A_MAXDATA = 1024 * 1024;

    // Embedded authorized private key matching TV's adb_keys
    private static final String PRIVATE_KEY_PEM =
            "MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQCxD5eLD71UftE/" +
            "r50ETLo7Yy3AQR0dzOX0WQNIUcyIWf0t+kSqUpPZ0aXqibswsH8Rup4d53ujW7DQ" +
            "Kzfl9XHSAE306DqQ3k8L487lh23UvkzAmDA7skhHsoRdnLTkqe58n57J3GhUp78X" +
            "G3v240WntHjDKG9CgtCq5tbc3uM8E7/eu9n419bFjBmm7cZzgC3Y18qMpbrUwRBY" +
            "fJT/3InctUjq8lkA0XIXg3yn2Fjjjwwuk5zS1efEo8+H90QyGxTRq65Xp0wfb3Y9" +
            "uNhbk6fJd+hNXRKI8EvKq1j4OGabgCKYyMC5cR9weU1B1wFAEJ8HkK3PnOE+09wq" +
            "3Irl9aJrAgMBAAECggEAArP6u8g/OzF+b0r2ijDDrRFsCuHTmHde0Vakiq+ZAEHj" +
            "cBbAgpslH6+oKUb2AR3IqtQfAtTcWdeWAO+P+GZBWRPhHMqIzlpeeY9+QpnJLvTC" +
            "xjPslJS5rsKGa8lvJ4iCmAt1yJ4hR52R+L/WKiQ5FYDXUR9mZ20I03sXr6KFdI7Q" +
            "HkLthuFF8O8WN9nPNuTsmeeXgzdcn0looDgbIECRB3lOJLWR2/mG+BZHaWlo764c" +
            "l/0jssm+VWUI3+DJN5XzrIYxOMcQRpNHHFUDYdsTTqndj1CtHNdsQ5uXL99Kf1tv" +
            "m4QNpmeZ7A/JH6+u5335LovhgJbbMByBbjNWCVtAPQKBgQDgBnb4+SguVAxWtLTL" +
            "yr4bbply520VjfOcgoPc/+6UUTMxmDYnsZlV1YWfX00nNdhxGgf4goulVnCFDoXb" +
            "8j/bjj5Q1l+fRETkroR0dNcUUlN+zaurw7LhJE+6Sz+zZfkdeXb1kDs0VAYan5qk" +
            "tRhiEum46kjkt/sSnPJ4apJXTwKBgQDKVR9yo5+VdLC42u5gMDT1rFZJnxzMANBK" +
            "GJEhU85qNlhanSOs4l/psZ3I5Isa4gbhKdnkC1jw5l2yYXpWoIhh9ANfDEX/mF64" +
            "Cj9WgGYMsAeRwJ/DxoKaXLQ0Pg57hN1iyKppLDd/eamF7WI1F/yLHpliCGszKepu" +
            "igIQQf+8JQKBgQCOQmywhAAJE2RWdyBMPW3lm6Ej/2QdCOyHGbZE090cIEhDGSZi" +
            "pHv7rsDQyMMEwEO25tHi9HtbPf3r8KH+XuJAOR7HVKqaR8777Pq9vSiLhg/xeQen" +
            "5nkkUVuzsG8+K+Y62lQ5ciK2gxjxNSMNrtZSCTKUM2qgm1h7pGCxKPPPLwKBgQCs" +
            "FH1siJCfEeGDNl/qWtWP5AR3FOXu8vozKnW0PIyfdJzsZB0FWnpsTO1/ADD2qilj" +
            "sq5n7uaz65jgr1rW9i1H8bo0SkH2Qea866o2rXkdbVDiu0qlvN0y34k7rVOv5a5L" +
            "55JXZI3G0vhEuUH/GellgJ6+654Qo6OIY8OhhCA2KQKBgQDL3JTvl5FprP9OKRyF" +
            "xlgE9GDsL+ldT+SIHAYLgGb25ifg5tT9mGy6j67d4WFOqKLnw66RjEWYCngTTbYj" +
            "5KGn5uGArCZbvMyNSoBI/TlXWnMuRmDsW4zxsdMBe1rmcnBG3bR+LIHsN4uuwJ5A" +
            "DViyJWlK76fFKk/34sWVXKTRjw==";

    private static byte[] makeMsg(int cmd, int arg0, int arg1, byte[] data) {
        if (data == null) data = new byte[0];
        int length = data.length;
        int checksum = 0;
        for (byte b : data) {
            checksum += (b & 0xff);
        }
        int magic = cmd ^ 0xffffffff;

        ByteBuffer buf = ByteBuffer.allocate(24 + length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(cmd);
        buf.putInt(arg0);
        buf.putInt(arg1);
        buf.putInt(length);
        buf.putInt(checksum);
        buf.putInt(magic);
        buf.put(data);
        return buf.array();
    }

    private static PrivateKey getPrivateKey() throws Exception {
        byte[] keyBytes = Base64.decode(PRIVATE_KEY_PEM, Base64.DEFAULT);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    public static void ensureBridgeRunningAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int attempt = 1; attempt <= 10; attempt++) {
                    try {
                        Log.i(TAG, "Attempting loopback ADB connection to ensure volume_bridge is running (attempt " + attempt + "/10)...");
                        if (runAdbCommand("ps -ef | grep volume_bridge | grep -v grep || nohup /data/local/tmp/volume_bridge > /data/local/tmp/volume_bridge.log 2>&1 &")) {
                            Log.i(TAG, "SUCCESS! volume_bridge daemon verified/started via local ADB on boot!");
                            return;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Loopback connection attempt " + attempt + " failed: " + e.getMessage());
                    }

                    try {
                        Thread.sleep(3000); // Wait 3s before retry (allows adbd to start on cold boot)
                    } catch (InterruptedException ignored) {}
                }
                Log.e(TAG, "Failed to start volume_bridge via local ADB after 10 attempts.");
            }
        }, "AdbStarterThread").start();
    }

    private static boolean runAdbCommand(String cmdToRun) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            socket.setSoTimeout(5000);

            OutputStream out = socket.getOutputStream();
            DataInputStream din = new DataInputStream(socket.getInputStream());

            // 1. Send CNXN
            out.write(makeMsg(A_CNXN, A_VERSION, A_MAXDATA, "host::\0".getBytes()));
            out.flush();

            byte[] header = new byte[24];
            din.readFully(header);
            ByteBuffer hBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int cmd = hBuf.getInt();
            int arg0 = hBuf.getInt();
            int arg1 = hBuf.getInt();
            int len = hBuf.getInt();

            byte[] token = new byte[len];
            din.readFully(token);

            if (cmd == A_AUTH && arg0 == 1) { // AUTH_TOKEN
                PrivateKey privKey = getPrivateKey();

                byte[] prefix = new byte[] {
                    0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
                };
                byte[] digestInfo = new byte[prefix.length + token.length];
                System.arraycopy(prefix, 0, digestInfo, 0, prefix.length);
                System.arraycopy(token, 0, digestInfo, prefix.length, token.length);

                Signature sig = Signature.getInstance("NONEwithRSA");
                sig.initSign(privKey);
                sig.update(digestInfo);
                byte[] signature = sig.sign();

                // Send AUTH_SIGNATURE
                out.write(makeMsg(A_AUTH, 2, 0, signature));
                out.flush();

                din.readFully(header);
                hBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                cmd = hBuf.getInt();
                arg0 = hBuf.getInt();
                arg1 = hBuf.getInt();
                len = hBuf.getInt();

                byte[] payload = new byte[len];
                din.readFully(payload);

                if (cmd == A_CNXN) {
                    // Authenticated! Send command
                    String fullShellCmd = "shell,v2,raw:" + cmdToRun + "\0";
                    out.write(makeMsg(A_OPEN, 1, 0, fullShellCmd.getBytes()));
                    out.flush();

                    // Read until adbd finishes spawning and closes the channel cleanly
                    while (true) {
                        try {
                            din.readFully(header);
                            ByteBuffer respBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                            int respCmd = respBuf.getInt();
                            int respA0 = respBuf.getInt();
                            int respA1 = respBuf.getInt();
                            int respLen = respBuf.getInt();
                            byte[] respData = new byte[respLen];
                            din.readFully(respData);

                            if (respCmd == A_CLSE) {
                                break;
                            } else if (respCmd == A_WRTE) {
                                out.write(makeMsg(A_OKAY, 1, respA0, null));
                                out.flush();
                            }
                        } catch (Exception e) {
                            break;
                        }
                    }
                    return true;
                }
            } else if (cmd == A_CNXN) {
                // Connected without auth
                String fullShellCmd = "shell,v2,raw:" + cmdToRun + "\0";
                out.write(makeMsg(A_OPEN, 1, 0, fullShellCmd.getBytes()));
                out.flush();
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "ADB loopback error: " + e.getMessage());
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
        return false;
    }
}
