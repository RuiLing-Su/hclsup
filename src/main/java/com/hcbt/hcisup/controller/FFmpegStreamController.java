package com.hcbt.hcisup.controller;

import com.hcbt.hcisup.SdkService.CmsService.CMS;
import com.hcbt.hcisup.SdkService.StreamService.SMS;
import com.hcbt.hcisup.common.AjaxResult;
import com.hcbt.hcisup.common.FFmpegStreamHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/stream")
@Tag(name = "FFmpeg推流接口", description = "通过 FFmpeg 进行 RTMP 推流控制")
public class FFmpegStreamController {

    @Resource
    private SMS sms;

    /**
     * Start pushing stream to SRT via FFmpeg
     */
    @PostMapping("/startSrtStream")
    @Operation(summary = "开始 SRT 推流（使用 FFmpeg）")
    public AjaxResult startSrtStream(
            @RequestParam("luserId") Integer luserId,
            @RequestParam("channel") Integer channel,
            @RequestParam("srtUrl") String srtUrl) {

        // Start FFmpeg process first
        if (!srtUrl.startsWith("srt://")) {
            return AjaxResult.error("Invalid SRT URL format");
        }

        boolean ffmpegStarted = FFmpegStreamHandler.startFFmpeg(luserId, srtUrl);
        if (!ffmpegStarted) {
            return AjaxResult.error("Failed to start FFmpeg");
        }

        CompletableFuture<String> completableFuture = new CompletableFuture<>();

        try {
            // Start ISUP stream with FFmpeg flag
            sms.RealPlayWithFFmpeg(luserId, channel, completableFuture);
        } catch (Exception e) {
            FFmpegStreamHandler.stopFFmpeg(luserId);
            return AjaxResult.error("Failed to start streaming: " + e.getMessage());
        }

        try {
            String result = completableFuture.get();
            if (Objects.equals(result, "true")) {
                return AjaxResult.success("Stream started successfully");
            }
            FFmpegStreamHandler.stopFFmpeg(luserId);
            return AjaxResult.error("Failed to start stream");
        } catch (InterruptedException | ExecutionException e) {
            FFmpegStreamHandler.stopFFmpeg(luserId);
            return AjaxResult.error("Stream start interrupted: " + e.getMessage());
        }
    }

    /**
     * Stop RTSP stream
     */
    @PostMapping("/stopRtspStream")
    @Operation(summary = "停止 RTSP 推流")
    public AjaxResult stopRtspStream(@RequestParam("luserId") Integer luserId) {
        Integer sessionId = SMS.LuserIDandSessionMap.get(luserId);
        if (sessionId == null) {
            return AjaxResult.error("No active stream found");
        }

        try {
            sms.StopRealPlay(luserId, sessionId, SMS.SessionIDAndPreviewHandleMap.get(sessionId));
            FFmpegStreamHandler.stopFFmpeg(luserId);
            return AjaxResult.success("Stream stopped successfully");
        } catch (Exception e) {
            return AjaxResult.error("Failed to stop stream: " + e.getMessage());
        }
    }
}