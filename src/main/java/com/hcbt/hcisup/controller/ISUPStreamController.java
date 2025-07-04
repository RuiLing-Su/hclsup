package com.hcbt.hcisup.controller;

import com.hcbt.hcisup.SdkService.StreamService.SMS;
import com.hcbt.hcisup.common.AjaxResult;
import com.hcbt.hcisup.common.ISUPStreamHandler;
import com.hcbt.hcisup.service.FrameDetectionProcessor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * FFmpeg 推流控制器
 * 提供通过 FFmpeg 进行 RTMP/SRT 推流控制的接口
 */
@RestController
@RequestMapping("/isup_stream")
@Tag(name = "ISUP推流接口", description = "通过 FFmpeg  进行 ISUP 推流控制")
public class ISUPStreamController {

    @Value("${ehome.pu-ip}")
    private String publicIp;
    private final int rtmpPort = 1935;
    private final int httpPort = 18080;
    @Resource
    private SMS sms;

    @Autowired
    private FrameDetectionProcessor frameDetectionProcessor;


    @PostMapping("/startStream")
    @Operation(summary = "isup推流", description = "根据隧道号推流")
    public AjaxResult smartStream(@RequestParam @Parameter(description = "用户ID") Integer userId, @RequestParam("channel") @Parameter(description = "通道号+码流类型，如拉取主码流的1号通道，就是101，拉取子码流就是102") Integer channel) {
        Integer luserId = 0; // 默认用户ID
        int dwStreamType = Integer.parseInt(String.valueOf(channel).substring(String.valueOf(channel).length() - 2));
        String key = "isup" + "_" + channel;
        int streamType = 0;
        if(dwStreamType!=1 && dwStreamType!=2){
            return AjaxResult.error("推流类型错误");
        }
        if (dwStreamType == 2) {
            streamType = 1;
        }
        String outputUrl = String.format("rtmp://%s:%d/live/%s", publicIp, rtmpPort, key);
        String flvUrl = String.format("http://%s:%d/live/%s.flv", publicIp, httpPort, key);
        Map<String, String> data = new HashMap<>();

        // 启动 ISUP 流并处理结果
        CompletableFuture<String> future = new CompletableFuture<>();
        sms.RealPlayWithFFmpeg(luserId, channel,streamType,future);
        try {
            String result = future.get();
            if ("true".equals(result)) {
                ISUPStreamHandler.StartResult startResult = ISUPStreamHandler.startStream(userId, channel, outputUrl);
                data.put("code", startResult.videoCodec);
                data.put("msg", startResult.message);
                data.put("flvUrl", flvUrl);
                return AjaxResult.success(data);
                // return AjaxResult.success("流启动成功", data);
            } else {
                return AjaxResult.error("设备异常，请联系管理员");
            }
        } catch (InterruptedException | ExecutionException e) {
            return AjaxResult.error("设备异常，请联系管理员: " + e.getMessage());
        }
    }

    /**
     * 查询某用户某通道是否正在推流
     *
     * @param channel 通道号
     * @return 返回是否在推流
     */
    @GetMapping("/status")
    @Operation(summary = "查询通道哪些用户正在使用", description = "根据通道号查询")
    public AjaxResult isRunning(
            @RequestParam @Parameter(description = "通道号") int channel
    ) {
        return AjaxResult.success(ISUPStreamHandler.isRunning(channel));
    }

    /**
     * 停止 推流
     *
     * @return AjaxResult 停止结果
     */
    @PostMapping("/stopStream")
    @Operation(summary = "停止推流", description = "根据隧道号推流")
    public AjaxResult stopRtspStream(@RequestParam @Parameter(description = "用户ID") int userId,
                                     @RequestParam("channel") @Parameter(description = "通道号") Integer channel) {
        ISUPStreamHandler.stopStream(userId, channel);
        return AjaxResult.success("流已成功停止");
    }

    /**
     * 查看正在播放的通道有哪些
     */
    @GetMapping("/playingChannels")
    @Operation(summary = "查看正在播放的通道有哪些", description = "查看正在播放的通道有哪些")
    public AjaxResult playingChannels() {
        return AjaxResult.success(ISUPStreamHandler.playingChannels());
    }

    /**
     * 内部方法：停止指定用户ID的流
     */
    private boolean stopStreamInternal(Integer userId, Integer channel) {
        // 从映射中获取会话 ID
        Integer sessionId = SMS.LuserIDandSessionMap.get(channel);
        if (sessionId == null) {
            return false;
        }
        try {
            int luserId = 0;
            // 停止 ISUP 流并清理 FFmpeg 进程
            sms.StopRealPlay(luserId, channel, sessionId, SMS.SessionIDAndPreviewHandleMap.get(sessionId));
            // 停止检测流程
            frameDetectionProcessor.stopDetection(luserId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }


}
