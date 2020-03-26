package websocketTest;

import websockets.WebSocketServerEntity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class StartServer {
    public static void main(String[] args) throws IOException {
        int pylonPort = 8885;
        WebSocketServerEntity pylonServer = new WebSocketServerEntity(pylonPort);
        pylonServer.runServer();

        BufferedReader system_in = new BufferedReader(new InputStreamReader(System.in));
        while(true) {
            String input;
            input = system_in.readLine();
            if(input.equals(("exit"))) {
                pylonServer.stopServer(10);
                break;
            }
        }
    }
}
