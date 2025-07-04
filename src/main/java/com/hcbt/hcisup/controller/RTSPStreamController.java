package com.hcbt.hcisup.controller;

import com.hcbt.hcisup.common.AjaxResult;
import com.hcbt.hcisup.common.RTSPStreamHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;


/**
 * 控制层 - JavaCV 推流控制器
 * 实现多用户多通道 RTSP 转 FLV 推送至 SRS，前端使用 flv.js 播放
 * @author llg
 * @slogan 致敬大师，致敬未来的你
 * @create 2025-05-26 10:28
 */
@RestController
@RequestMapping("/rstp_stream")
@Tag(name = "RTSP推流控制器", description = "实现多用户多通道 RTSP 转 FLV 推送至 SRS，前端使用 flv.js 播放")
public class RTSPStreamController {

    @Value("${ehome.pu-ip}")
    private String publicIp;
    // private String publicIp = "101.132.99.208";
    // private String publicIp = "124.223.42.71";
    // 假设 SRS 默认 RTMP 推流端口为 1935，HTTP 播放端口为 8080，可根据实际 SRS 配置调整
    private final int rtmpPort = 1935;
    private final int httpPort = 18080;


    /**
     * 启动推流接口
     * @param userId 用户 ID（用于区分多用户）
     * @param channel 通道号（一个用户可有多个通道）
     * @param dwStreamType 推流类型,0为主码流,1为子码流
     * @return 返回推流是否成功
     */
    @PostMapping("/start")
    @Operation(summary = "启动推流接口", description = "根据用户ID、通道号、拉流地址启动推流")
    public AjaxResult startStream(
            @RequestParam @Parameter(description = "用户ID")int userId,
            @RequestParam @Parameter(description = "通道号+码流类型，如拉取主码流的1号通道，就是101，拉取子码流就是102")int channel
    ) {
        int dwStreamType = Integer.parseInt(String.valueOf(channel).substring(String.valueOf(channel).length() - 2));
        String key = "rtsp" + "_" + channel;
        String rtspUrl = "rtsp://admin:qaz12345@112.28.137.127:8554/Streaming/Channels/"+channel;
        if(dwStreamType!=1 && dwStreamType!=2){
            return AjaxResult.error("推流类型错误");
        }
        // RTMP 推流地址（SRS 接收）
        String outputUrl = String.format("rtmp://%s:%d/live/%s", publicIp, rtmpPort, key);

        RTSPStreamHandler.StartResult startResult = RTSPStreamHandler.startStream(userId, channel, rtspUrl, outputUrl);
        if (!startResult.success) {
            return AjaxResult.error(startResult.message);
        }

        // FLV 播放地址（供 flv.js 使用）
        String flvUrl = String.format("http://%s:%d/live/%s.flv", publicIp, httpPort, key);
        Map<String, String> data = new HashMap<>();
        data.put("flvUrl", flvUrl);
        data.put("code", startResult.videoCodec);
        data.put("message", startResult.message);
        return AjaxResult.success("推流已启动", data);
    }

    /**
     * 停止推流接口
     * @param userId 用户 ID
     * @param channel 通道号
     * @return 返回停止是否成功
     */
    @PostMapping("/stop")
    @Operation(summary = "停止推流接口", description = "根据用户ID、通道号、拉流地址停止推流")
    public AjaxResult stopStream(
            @RequestParam @Parameter(description = "用户ID")int userId,
            @RequestParam @Parameter(description = "通道号")int channel
    ) {
        if(RTSPStreamHandler.stopStream(userId,channel)){
            //停止检测流程
            // frameDetectionProcessor.stopDetection(luserId);
            return AjaxResult.success("推流已停止");
        }else{
            return AjaxResult.success("还有其他用户在使用通道");
        }


    }

    /**
     * 查看正在播放的通道有哪些
     */
    @GetMapping("/playingChannels")
    @Operation(summary = "查看正在播放的通道有哪些", description = "查看正在播放的通道有哪些")
    public AjaxResult playingChannels() {
        return AjaxResult.success(RTSPStreamHandler.playingChannels());
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
        return AjaxResult.success(RTSPStreamHandler.isRunning( channel));
    }

    // /**
    //  * 获取播放地址（用于前端 flv.js 播放）
    //  * @param userId 用户 ID
    //  * @param channel 通道号
    //  * @return 返回 .flv 播放地址
    //  */
    // @GetMapping("/playUrl")
    // @Operation(summary = "获取播放地址", description = "根据用户ID、通道号获取播放地址")
    // public AjaxResult getPlayUrl(
    //         @RequestParam @Parameter(description = "用户ID")int userId,
    //         @RequestParam @Parameter(description = "通道号")int channel
    // ) {
    //     RSTPtreamHandler.refreshSessionActivity(userId, channel); // 刷新活跃时间
    //     String flvUrl = String.format("http://%s:%d/live/%d_%d.flv", publicIp, httpPort, userId, channel);
    //     return AjaxResult.success(flvUrl);
    // }

}
