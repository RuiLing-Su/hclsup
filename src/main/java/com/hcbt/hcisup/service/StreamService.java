package com.hcbt.hcisup.service;

import com.hcbt.hcisup.SdkService.StreamService.SMS;
import com.hcbt.hcisup.model.Detection;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.springframework.stereotype.Service;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @ClassName: StreamService
 * @Description: 流媒体服务，用于处理视频流的接收、分析和转发。
 * 支持HLS流的输出，并集成目标检测功能。
 * @Author: ruiling
 * @Date: 2025/04/29
 */
@Slf4j
@Service
public class StreamService {
    private final ConcurrentHashMap<Integer, PipedOutputStream> outputStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, FFmpegFrameGrabber> grabbers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, FFmpegFrameRecorder> recorders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Thread> processingThreads = new ConcurrentHashMap<>();
    private final DetectionService detectionService;
    private final SMS sms;
    private final OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();

    public StreamService(DetectionService detectionService, SMS sms) {
        this.detectionService = detectionService;
        this.sms = sms;
    }

    public void startHlsStream(int channel, String hlsPath) throws Exception {
        if (outputStreams.containsKey(channel)) {
            throw new IllegalStateException("通道 " + channel + " 已处于流处理状态");
        }

        // 创建管道输入流和输出流，用于接收ES数据
        PipedInputStream pipedInput = new PipedInputStream();
        PipedOutputStream pipedOutput = new PipedOutputStream(pipedInput);
        outputStreams.put(channel, pipedOutput);

        // 创建FFmpeg帧抓取器，用于读取管道输入流中的H.265格式数据
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(pipedInput);
        grabber.setFormat("hevc");
        grabbers.put(channel, grabber);

        // 调用SMS服务启动实时播放，获取播放结果
        CompletableFuture<String> future = new CompletableFuture<>();
        int userId = 0; // 默认用户ID为0，根据实际情况调整
        sms.RealPlay(userId, channel, future);
        String result = future.get();
        if (!"true".equals(result)) {
            throw new RuntimeException("SMS 流启动失败");
        }

        // 创建并启动处理视频流的线程
        Thread thread = new Thread(() -> processStream(channel, hlsPath));
        thread.start();
        processingThreads.put(channel, thread);
    }

    /**
     * 处理指定通道的视频流，进行目标检测并将结果录制为HLS流。
     *
     * @param channel 通道ID
     * @param hlsPath HLS流的输出路径
     */
    private void processStream(int channel, String hlsPath) {
        FFmpegFrameGrabber grabber = grabbers.get(channel);
        try {
            grabber.start();

            // 创建FFmpeg帧录制器，用于输出HLS流
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(hlsPath, grabber.getImageWidth(), grabber.getImageHeight());
            recorder.setFormat("hls");
            recorder.setOption("hls_time", "2");
            recorder.setOption("hls_list_size", "3");
            recorder.setOption("hls_flags", "delete_segments+append_list");
            recorder.setOption("hls_segment_type", "mpegts");
            recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264);
            recorder.setFrameRate(grabber.getFrameRate());
            recorder.setOption("preset", "veryfast");
            recorder.setOption("tune", "zerolatency");
            recorder.setOption("g", "50");
            recorder.setOption("keyint_min", "25");
            recorder.start();
            recorders.put(channel, recorder);

            Frame frame;
            while ((frame = grabber.grab()) != null && processingThreads.containsKey(channel)) {
                if (frame.image != null) {
                    Mat mat = converter.convert(frame);
                    if (mat != null) {
                        // 调用目标检测服务进行推理
                        List<Detection> detections = detectionService.runInference(mat);
                        // 在图像上绘制检测结果
                        detectionService.drawDetections(mat, detections);
                        // 将处理后的Mat转换回Frame格式
                        Frame processedFrame = converter.convert(mat);
                        // 录制处理后的帧
                        recorder.record(processedFrame);
                        mat.close();
                    }
                }
            }
        } catch (Exception e) {
            log.error("处理视频流失败，通道: {}，错误: {}", channel, e.getMessage(), e);
        } finally {
            // 无论是否发生异常，最终都要停止流处理
            stopStream(channel);
        }
    }

    /**
     * 处理接收到的ES数据，将其写入对应用户的管道输出流。
     *
     * @param userId 用户ID，用于确定写入哪个通道的输出流
     * @param esData 接收到的ES数据字节数组
     */
    public void processStreamData(Integer userId, byte[] esData) {
        if (userId == null || esData == null || esData.length == 0) {
            log.warn("收到无效的ES数据，用户ID: {}", userId);
            return;
        }

        // 根据用户ID获取对应的管道输出流
        PipedOutputStream outputStream = outputStreams.get(userId);
        if (outputStream != null) {
            try {
                outputStream.write(esData);
                outputStream.flush();// 刷新缓冲区，确保数据立即写入
            } catch (Exception e) {
                log.error("写入 ES 数据失败，用户ID: {}", userId, e);
            }
        }
    }

    public void stopStream(int channel) {
        processingThreads.remove(channel);

        PipedOutputStream outputStream = outputStreams.remove(channel);
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (Exception e) {
                log.error("关闭输出流失败，通道: {}", channel, e);
            }
        }

        // 获取并停止帧抓取器
        FFmpegFrameGrabber grabber = grabbers.remove(channel);
        if (grabber != null) {
            try {
                grabber.stop();
                grabber.release();
            } catch (Exception e) {
                log.error("停止抓取器失败，通道: {}", channel, e);
            }
        }

        // 获取并停止帧录制器
        FFmpegFrameRecorder recorder = recorders.remove(channel);
        if (recorder != null) {
            try {
                recorder.stop();
                recorder.release();
            } catch (Exception e) {
                log.error("停止录制器失败，通道: {}", channel, e);
            }
        }

        // 停止SMS实时播放（根据用户ID查找会话信息并停止）
        int userId = 0;
        Integer sessionId = SMS.LuserIDandSessionMap.get(userId);
        if (sessionId != null) {
            sms.StopRealPlay(userId, sessionId, SMS.SessionIDAndPreviewHandleMap.get(sessionId));
        }
    }
}