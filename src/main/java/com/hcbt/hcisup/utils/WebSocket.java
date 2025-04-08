package com.hcbt.hcisup.utils;

import com.alibaba.fastjson.JSONObject;
import com.hcbt.hcisup.SdkService.StreamService.SMS;
import com.hcbt.hcisup.common.AjaxResult;
import jakarta.annotation.Resource;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

//注册成组件
@Component
@ServerEndpoint("/websocket/{userId}")
@Slf4j
public class WebSocket{

    //实例一个session，这个session是websocket的session
    private Session session;
    private Integer userId;
    private Integer channel;

    private static final CopyOnWriteArraySet<WebSocket> webSocketSet = new CopyOnWriteArraySet<>();
    private static final ConcurrentHashMap<Integer, Integer> LUserIdCount = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Boolean> userIDLock = new ConcurrentHashMap<>();

    //加锁
    private void addLock(Integer userID){
        if (userIDLock.containsKey(userID)) {
            while (userIDLock.get(userID)) Thread.onSpinWait();
        }
        userIDLock.put(userID, true);
    }
    //释放锁
    private void freedLock(Integer userID){
        userIDLock.put(userID, false);
    }

    //前端请求时一个websocket时
    @OnOpen
    public void onOpen(Session session, @PathParam("userId") Integer userId) {
        addLock(userId);
        this.session = session;
        this.userId = userId;
        try {
            webSocketSet.add(this);
            log.info("【websocket消息】有新的连接{}, 总数:{}", userId, webSocketSet.size());
        } finally {
            freedLock(userId);
        }
    }

    //前端关闭时一个websocket时
    @OnClose
    public void onClose() {
        addLock(userId);
        try {
            webSocketSet.remove(this);
            if (LUserIdCount.getOrDefault(userId, 0) <= 1) {
                LUserIdCount.put(userId, 0);
                Map<String, String> map = new HashMap<>();
                map.put("luserId", String.valueOf(userId));
                HttpUtil.post("http://localhost:9090/stopPushStream", map);
            } else {
                LUserIdCount.put(userId, LUserIdCount.get(userId) - 1);
            }
        } finally {
            log.info("【websocket消息】连接断开, 总数:{}", webSocketSet.size());
            freedLock(userId);
        }
    }

    //前端向后端发送消息
    @OnMessage
    public void onMessage(String message) {
        JSONObject messageJson = JSONObject.parseObject(message);
        String commandType = messageJson.getString("t");
        String channelName = messageJson.getString("c");

        if ("open".equals(commandType) && channelName != null) {
            this.channel = Integer.parseInt(channelName.replace("ch", ""));
            startStream();
        }
        log.info("Received message: t={}, c={}", commandType, channelName);
    }

    @OnError
    public void onError(Session session,Throwable throwable) {
        log.info("【websocket消息】未知错误 session:{}, error:{}", session.getId(), throwable.getMessage());
        for (WebSocket webSocket: webSocketSet) {
            if(Objects.equals(webSocket.session, session)){
                //找到本此连接,关闭
                webSocket.onClose();
            }
        }
    }

    //向指定用户发送消息
    public void sendMessageForOne(byte[] message,Integer userId){
        for (WebSocket webSocket: webSocketSet) {
            if(Objects.equals(webSocket.userId, userId)){
                try {
                    ByteBuffer data = ByteBuffer.wrap(message);
                    webSocket.session.getBasicRemote().sendBinary(data);
                } catch (Exception e) {
                    log.error(e.getMessage());
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
                String res = HttpUtil.post("http://localhost:9090/startPushStream", map);
                Map<String, Object> map1 = JSONObject.parseObject(res);
                String code = map1.get("code").toString();
                LUserIdCount.put(userId, "200".equals(code) ? 1 : 0);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        } else {
            LUserIdCount.put(userId, LUserIdCount.get(userId) + 1);
        }
    }

    //新增一个方法用于主动向客户端发送消息
    public void sendMessage(JSONObject message){
        log.info("【websocket消息】广播消息, message={}", message.get("message"));
        for (WebSocket webSocket: webSocketSet) {
            try {
                webSocket.session.getBasicRemote().sendText(String.valueOf(message.get("message")));
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }
    }
}
