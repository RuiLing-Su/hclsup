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
        //加锁
        addLock(userId);
        this.session = session;
        this.userId = userId;
        try {
            webSocketSet.add(this);
            log.info("【websocket消息】有新的连接{}, 总数:{}", userId,webSocketSet.size());
            if(LUserIdCount.get(userId) == null || LUserIdCount.get(userId) <= 0){
                //开始取流
                Map<String,String> map = new HashMap<>();
                map.put("luserId", String.valueOf(userId));
                try {
                    String res = HttpUtil.post("http://localhost:9000/startPushStream",map);
                    Map<String,Object> map1 = JSONObject.parseObject(res);
                    String code = map1.get("code").toString();
                    if(code.equals("200")){
                        LUserIdCount.put(userId, 1);
                    }else{
                        LUserIdCount.put(userId, 0);
                    }
                }catch (Exception e){
                    log.error(e.getMessage());
                    //出错先释放锁
                    freedLock(userId);
                }
    //            CompletableFuture<String> completableFuture = new CompletableFuture<>();
    //            sms.RealPlay(userId,completableFuture);
    //            try {
    //                String result = completableFuture.get();
    //                if(Objects.equals(result, "true")){
    //                    LUserIdCount.put(userId, 1);
    //                }
    //            } catch (InterruptedException | ExecutionException e) {
    //                throw new RuntimeException(e);
    //            }
            }else{
                LUserIdCount.put(userId, LUserIdCount.get(userId)+1);
            }
        }finally {
            freedLock(userId);
        }
    }

    //前端关闭时一个websocket时
    @OnClose
    public void onClose(){
        //加锁
        addLock(userId);
        try{
            webSocketSet.remove(this);
        }catch (Exception e){
            log.info("【websocket消息】异常断开");
        }finally {
            log.info("【websocket消息】连接断开, 总数:{}", webSocketSet.size());
            if(LUserIdCount.get(userId) <= 1){
                LUserIdCount.put(userId, 0);
                //关闭取流
                Map<String,String> map = new HashMap<>();
                map.put("luserId", String.valueOf(userId));
                try {
                    HttpUtil.post("http://localhost:9000/stopPushStream",map);
                }catch (Exception e){
                    log.error(e.getMessage());
                    freedLock(userId);
                }
//            Integer i = SMS.LuserIDandSessionMap.get(userId);
//            if (i!=null) {
//                sms.StopRealPlay(userId, i, SMS.SessionIDAndPreviewHandleMap.get(i));
//                LUserIdCount.put(userId, 0);
//            }
            }else{
                LUserIdCount.put(userId, LUserIdCount.get(userId)-1);
            }
            freedLock(userId);
        }
    }

    //前端向后端发送消息
    @OnMessage
    public void onMessage(String message) {
        Integer userId;
        Boolean ToMqtt;
        //log.info("【websocket消息】收到客户端 {} 发来的消息",this.userId);
        JSONObject messageJson = (JSONObject) JSONObject.parse(message);
        userId = (Integer) messageJson.get("userId");

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
