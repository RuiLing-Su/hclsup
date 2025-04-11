package com.hcbt.hcisup.utils;

import com.alibaba.fastjson.JSONObject;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
@ServerEndpoint("/websocket/{userId}/{channel}")
@Slf4j
public class WebSocket {

    // WebSocket session
    private Session session;
    private Integer userId;
    private Integer channel;

    // Thread-safe collections for managing connections
    private static final CopyOnWriteArraySet<WebSocket> webSocketSet = new CopyOnWriteArraySet<>();
    private static final ConcurrentHashMap<Integer, Integer> LUserIdCount = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Boolean> userIDLock = new ConcurrentHashMap<>();

    // Add synchronization lock
    private void addLock(Integer userID) {
        if (userIDLock.containsKey(userID)) {
            while (userIDLock.get(userID)) Thread.onSpinWait();
        }
        userIDLock.put(userID, true);
    }

    // Release synchronization lock
    private void freedLock(Integer userID) {
        userIDLock.put(userID, false);
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("userId") Integer userId, @PathParam("channel") Integer channel) {
        addLock(userId);
        try {
            this.session = session;
            this.userId = userId;
            this.channel = channel;

            // Configure session for binary messages
            this.session.setMaxBinaryMessageBufferSize(1024 * 1024); // 1MB buffer
            this.session.setMaxTextMessageBufferSize(8192);

            log.info("【WebSocket】New connection: userId={}, channel={}, sessionId={}",
                    userId, channel, session.getId());

            webSocketSet.add(this);
            log.info("【WebSocket】New connection added, total connections: {}", webSocketSet.size());

            // Start streaming after connection is established
            startStream();
        } catch (Exception e) {
            log.error("Error during WebSocket open: {}", e.getMessage(), e);
        } finally {
            freedLock(userId);
        }
    }

    @OnClose
    public void onClose() {
        addLock(userId);
        try {
            webSocketSet.remove(this);
            log.info("【WebSocket】Connection closed: userId={}, channel={}", userId, channel);

            // Decrement user count and stop stream if this is the last connection for this user
            if (LUserIdCount.getOrDefault(userId, 0) <= 1) {
                LUserIdCount.put(userId, 0);
                Map<String, String> map = new HashMap<>();
                map.put("luserId", String.valueOf(userId));
                try {
                    HttpUtil.post("http://localhost:9000/stopPushStream", map);
                    log.info("Stream stopped for userId: {}", userId);
                } catch (Exception e) {
                    log.error("Failed to stop stream: {}", e.getMessage());
                }
            } else {
                LUserIdCount.put(userId, LUserIdCount.get(userId) - 1);
                log.info("User count decremented for userId: {}, new count: {}",
                        userId, LUserIdCount.get(userId));
            }
        } finally {
            log.info("【WebSocket】Total connections after close: {}", webSocketSet.size());
            freedLock(userId);
        }
    }

    @OnMessage
    public void onMessage(String message) {
        try {
            JSONObject messageJson = JSONObject.parseObject(message);
            String commandType = messageJson.getString("t");
            String channelName = messageJson.getString("c");

            log.info("Received message: t={}, c={}", commandType, channelName);

            if ("open".equals(commandType) && channelName != null) {
                try {
                    this.channel = Integer.parseInt(channelName.replace("ch", ""));
                    startStream();
                } catch (NumberFormatException e) {
                    log.error("Invalid channel format: {}", channelName);
                }
            }
        } catch (Exception e) {
            log.error("Error parsing message: {}", e.getMessage());
        }
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        log.error("【WebSocket】Error in session {}: {}", session.getId(), throwable.getMessage(), throwable);
        try {
            for (WebSocket webSocket : webSocketSet) {
                if (Objects.equals(webSocket.session, session)) {
                    webSocket.onClose();
                    break;
                }
            }
        } catch (Exception e) {
            log.error("Error handling WebSocket error: {}", e.getMessage());
        }
    }

    // Send binary data to a specific user
    public void sendMessageForOne(byte[] message, Integer userId) {
        if (message == null || message.length == 0) {
            return;
        }

        for (WebSocket webSocket : webSocketSet) {
            if (Objects.equals(webSocket.userId, userId) && webSocket.session.isOpen()) {
                try {
                    ByteBuffer buffer = ByteBuffer.wrap(message);
                    webSocket.session.getAsyncRemote().sendBinary(buffer, result -> {
                        if (!result.isOK()) {
                            log.error("Failed to send binary data: {}", result.getException().getMessage());
                        }
                    });
                } catch (Exception e) {
                    log.error("Error sending H.265 stream data: {}", e.getMessage());
                }
            }
        }
    }

    private void startStream() {
        if (LUserIdCount.getOrDefault(userId, 0) <= 0) {
            Map<String, String> map = new HashMap<>();
            map.put("luserId", String.valueOf(userId));
            map.put("channel", String.valueOf(channel));

            try {
                String res = HttpUtil.post("http://localhost:9000/startPushStream", map);
                Map<String, Object> responseMap = JSONObject.parseObject(res);
                String code = responseMap.get("code").toString();

                if ("200".equals(code)) {
                    LUserIdCount.put(userId, 1);
                    log.info("Stream started successfully for userId: {}, channel: {}", userId, channel);
                } else {
                    LUserIdCount.put(userId, 0);
                    log.error("Failed to start stream. Response code: {}", code);
                    sendTextMessage("Stream initialization failed. Please try again.");
                }
            } catch (Exception e) {
                log.error("Error starting stream: {}", e.getMessage());
                sendTextMessage("Error starting stream: " + e.getMessage());
            }
        } else {
            LUserIdCount.put(userId, LUserIdCount.get(userId) + 1);
            log.info("User count incremented for userId: {}, new count: {}",
                    userId, LUserIdCount.get(userId));
        }
    }

    // Send text message to this client
    private void sendTextMessage(String message) {
        if (session != null && session.isOpen()) {
            try {
                session.getAsyncRemote().sendText(message);
            } catch (Exception e) {
                log.error("Error sending text message: {}", e.getMessage());
            }
        }
    }

    // Send text message to all clients
    public void broadcastMessage(String message) {
        for (WebSocket webSocket : webSocketSet) {
            try {
                if (webSocket.session.isOpen()) {
                    webSocket.session.getAsyncRemote().sendText(message);
                }
            } catch (Exception e) {
                log.error("Error broadcasting message: {}", e.getMessage());
            }
        }
    }
}