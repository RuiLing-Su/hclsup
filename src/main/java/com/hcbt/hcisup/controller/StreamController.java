package com.hcbt.hcisup.controller;

import com.hcbt.hcisup.SdkService.CmsService.CMS;
import com.hcbt.hcisup.SdkService.StreamService.SMS;
import com.hcbt.hcisup.common.AjaxResult;
import com.hcbt.hcisup.service.StreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.PipedOutputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/detection")
@Tag(name = "推流接口")
public class StreamController {
    private final StreamService streamService;
    private final SMS sms;
    private final CMS cms;

    // 存储每个通道对应的SMS会话信息
    private final ConcurrentHashMap<Integer, Integer> channelSessionMap = new ConcurrentHashMap<>();

    public StreamController(StreamService streamService, SMS sms, CMS cms) {
        this.streamService = streamService;
        this.sms = sms;
        this.cms = cms;
    }

    //根据DeviceID获取lUserID
    @RequestMapping(value ="getLUserId/{DeviceID}")
    @Operation(summary = "根据DeviceID获取lUserId")
    public AjaxResult getLUserId(@PathVariable("DeviceID") String DeviceID)
    {
        Map<String,Integer> data = cms.getLUserId(DeviceID);
        if(data != null){
            return AjaxResult.success(data);
        }
        return AjaxResult.error();
    }

    @PostMapping("/startHlsStream")
    @Operation(summary = "开始 HLS 推流")
    public ResponseEntity<?> startHlsStream(
            @RequestParam("channel") int channel,
            @RequestParam("hlsPath") String hlsPath) {
        try {
            // 先检查是否已有流在运行，如有则先停止
            Integer existingSession = channelSessionMap.get(channel);
            if (existingSession != null) {
                log.info("通道 {} 已有会话 {}，先停止现有流", channel, existingSession);
                stopStream(channel);
            }

            // 动态获取用户ID（替换为实际逻辑）
            int userId = 0; // 例如，cms.getLUserId(DeviceID)
            log.info("通过SMS服务开始实时播放，用户ID: {}, 通道: {}", userId, channel);

            // 异步启动实时播放
            CompletableFuture<String> future = new CompletableFuture<>();
            sms.RealPlay(userId, channel, future);
            String result = future.get(10, TimeUnit.SECONDS); // 添加超时
            log.info("SMS流启动结果: {}", result);
            if (!"true".equals(result)) {
                throw new RuntimeException("启动SMS流失败");
            }

            // 检索会话ID
            Integer sessionId = SMS.LuserIDandSessionMap.get(userId);
            if (sessionId == null) {
                throw new RuntimeException("未找到用户ID的会话ID: " + userId);
            }
            channelSessionMap.put(channel, sessionId);
            log.info("会话ID {} 已映射到通道 {}", sessionId, channel);

            // 创建流管道
            PipedOutputStream outputStream = streamService.createStreamPipe(sessionId);
            if (outputStream == null) {
                throw new RuntimeException("创建管道输出流失败");
            }

            // 启动处理线程
            log.info("开始为会话ID {}处理流: {}", sessionId, hlsPath);
            streamService.startProcessingThread(sessionId, hlsPath);

            return ResponseEntity.ok("HLS流成功启动");
        } catch (Exception e) {
            log.error("启动HLS流失败: {}", e.getMessage(), e);
            // 确保在发生错误时进行清理
            try {
                stopStream(channel);
            } catch (Exception cleanupEx) {
                log.warn("清理失败的流时出错: {}", cleanupEx.getMessage());
            }
            return ResponseEntity.status(500).body("启动HLS流失败: " + e.getMessage());
        }
    }
    @PostMapping("/stopStream")
    @Operation(summary = "停止推流")
    public ResponseEntity<?> stopStream(@RequestParam("channel") int channel) {
        try {
            // 1. 先停止SMS实时播放
            Integer sessionId = channelSessionMap.remove(channel);
            if (sessionId != null) {
                int userId = 0; // 默认用户ID
                Integer previewHandle = SMS.SessionIDAndPreviewHandleMap.get(sessionId);
                if (previewHandle != null) {
                    sms.StopRealPlay(userId, sessionId, previewHandle);
                }
            }

            // 2. 停止流处理
            streamService.stopStream(channel);

            return ResponseEntity.ok("流已停止");
        } catch (Exception e) {
            log.error("停止流失败: {}", e.getMessage());
            return ResponseEntity.status(500).body("停止流失败: " + e.getMessage());
        }
    }
}