import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import java.net.InetSocketAddress;

/**
 * Half-open link probe. Speaks just enough of the contract to keep the screen
 * happy: it answers the WS handshake and then sends ONLY ack frames, never a
 * state frame. That isolates the starvation hole in screen_link.c's watchdog,
 * which only got to check the gap when a whole 250 ms tick had nothing to read.
 *
 * Expected on a FIXED build: every ~10 s the screen logs
 *   "state ... 10 ... 1 ..." and reconnects, and ">carpet" shows wd incrementing.
 * Expected on the OLD build: silence, linked=1, gap_s climbing without ever
 * reconnecting -- which is the 565-second incident.
 *
 * ASCII only: this file is compiled by javac under a GBK-default console.
 */
public class HalfOpen {
    static volatile int seq = 0;

    public static void main(String[] args) throws Exception {
        WebSocketServer srv = new WebSocketServer(new InetSocketAddress(8443)) {
            @Override public void onOpen(WebSocket w, ClientHandshake h) {
                System.out.println("OPEN  " + w.getRemoteSocketAddress());
            }
            @Override public void onClose(WebSocket w, int code, String reason, boolean remote) {
                System.out.println("CLOSE " + w.getRemoteSocketAddress() + " code=" + code + " remote=" + remote);
            }
            @Override public void onMessage(WebSocket w, String m) {
                System.out.println("RX    " + (m.length() > 150 ? m.substring(0, 150) : m));
            }
            @Override public void onError(WebSocket w, Exception e) {
                System.out.println("ERR   " + e);
            }
            @Override public void onStart() {
                System.out.println("LISTENING 8443, ack-only, never state");
            }
        };
        srv.start();
        while (true) {
            Thread.sleep(500);
            for (WebSocket w : srv.getConnections()) {
                if (w.isOpen()) {
                    w.send("{\"v\":2,\"t\":\"ack\",\"seq\":" + (++seq) + ",\"ack\":{\"for_seq\":1}}");
                }
            }
        }
    }
}
