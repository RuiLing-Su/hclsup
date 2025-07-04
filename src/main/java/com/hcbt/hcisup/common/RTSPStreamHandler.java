package com.hcbt.hcisup.common;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * rtsp 推流处理器
 * 用于将 RTSP 视频流采集后转推到 RTMP（如 SRS）服务器，支持多用户多通道
 *
 * @author llg
 * @slogan 致敬大师，致敬未来的你
 * @create 2025-05-26 10:09
 */
@Slf4j
public class RTSPStreamHandler {


    // 推流会话，封装每个 FFmpeg 子进程及运行状态
    static class StreamSession {
        Process process;             // FFmpeg 进程对象
        ExecutorService executor;    // 执行监控线程
        boolean running = false;    // 是否运行中
        // long lastActiveTime; // 最后活跃时间
        long lastActiveTime = System.currentTimeMillis(); // 用户活跃时间
        String videoCodec; // 新增：记录视频编码
    }

    public static class StartResult {
        public boolean success;
        public String videoCodec;
        public String message;

        public StartResult(boolean success, String videoCodec, String message) {
            this.success = success;
            this.videoCodec = videoCodec;
            this.message = message;
        }
    }

    /**
     * 存储每个用户的所有通道会话： userId -> (channel -> StreamSession)
     */


    // 所有通道的推流会话：channel -> StreamSession
    private static final Map<Integer, StreamSession> channelSessionMap = new ConcurrentHashMap<>();

    // key: channel -> 正在使用该通道的用户ID集合
    private static final Map<Integer, Set<Integer>> channelUserMap = new ConcurrentHashMap<>();
    private static final int MAX_TOTAL_STREAMS = 1;
    private static final AtomicInteger activeStreamCount = new AtomicInteger(0);
    private static final Object streamLock = new Object();

    /**
     * 启动 RTSP → FLV(RTMP) 的推流任务
     *
     * @param userId    用户 ID
     * @param channel   通道号
     * @param inputUrl  输入流地址（RTSP）
     * @param outputUrl 输出地址（RTMP，通常为 SRS 的地址）
     * @return 启动是否成功
     */
    public static StartResult startStream(int userId, int channel, String inputUrl, String outputUrl) {
        // 码流类型为最后两位
        int dwStreamType = Integer.parseInt(String.valueOf(channel).substring(String.valueOf(channel).length() - 2));
        // 注册用户使用该通道
        channelUserMap.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(userId);
        StreamSession userSession = channelSessionMap.get(channel);

        if (userSession != null && userSession.running) {
            userSession.lastActiveTime = System.currentTimeMillis();
            log.info("复用通道推流，channel: {}, inputUrl: {}, outputUrl: {}", channel, inputUrl, outputUrl);
            return new StartResult(true, userSession.videoCodec, "通道正在推流，复用当前会话");
        }
        synchronized (streamLock) {
            // 在启动前检查总数限制
            if (activeStreamCount.get() >= MAX_TOTAL_STREAMS) {
                log.info("当前推流数量:{}", activeStreamCount.get());
                log.warn("会话map的数量：{}", channelSessionMap.size());
                log.warn("推流数量已满，准备挤出最早的通道，当前准备播放通道{}", channel);
                // 找到最早启动的通道
                if (channelSessionMap.size() > 0) {
                    Integer oldestChannel = null;
                    for (Map.Entry<Integer, StreamSession> entry : channelSessionMap.entrySet()) {
                        oldestChannel = entry.getKey();
                    }
                    log.warn("挤出通道{}", oldestChannel);
                    if (oldestChannel != null) {
                        log.warn("关闭通道 channel {}，为新通道腾位", oldestChannel);
                        stopStream2(oldestChannel);
                        // 清除该通道的用户
                        channelUserMap.remove(oldestChannel);
                        // 停止推流成功后，重新启动推流
                        return startStream(userId, channel, inputUrl, outputUrl);
                    }
                } else {
                    // 停止推流成功后，重新启动推流
                    return new StartResult(false, null, "会话map为空，无法挤出最早的通道");
                }
            }
            String codecName = extractVideoCodec(inputUrl);
            // String codecName = "H.264";
            // if (codecName == null) return new StartResult(false, null, "无法提取编解码器");
            if (codecName == null) codecName = "h264";
            // 构造 FFmpeg 命令（无需转码）
            String[] command;
            if(dwStreamType==1){
                codecName = "h264";
                command = new String[]{
                        "ffmpeg",
                        "-rtsp_transport", "tcp",      // 使用 TCP 获取更稳定的数据
                        "-i", inputUrl,                // RTSP 输入
                        "-vf", "scale=854:480",       // 降低分辨率
                        "-c:v", "libx264",            // 转码
                        // "-c:v", "copy",                // 拷贝视频流
                        "-an",                         // 去除音频
                        "-f", "flv",                   // 封装为 FLV 格式
                        outputUrl                      // 推流到 SRS/RTMP 服务器
                };
            }else{
                codecName = "h264";
                command = new String[]{
                        "ffmpeg",
                        "-rtsp_transport", "tcp",      // 使用 TCP 获取更稳定的数据
                        "-i", inputUrl,                // RTSP 输入
                        // "-vf", "scale=854:480",       // 降低分辨率
                        // "-c:v", "libx264",            // 转码
                        "-c:v", "copy",                // 拷贝视频流
                        "-an",                         // 去除音频
                        "-f", "flv",                   // 封装为 FLV 格式
                        outputUrl                      // 推流到 SRS/RTMP 服务器
                };
            }

            try {
                log.info("开启新通道，channel: {}, inputUrl: {}, outputUrl: {}", channel, inputUrl, outputUrl);
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true); // 合并标准错误和标准输出，便于读取日志

                Process process = builder.start();
                // 打印ffmpeg日志
                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            // log.info("[FFmpeg] {}", line);
                        }
                    } catch (IOException e) {
                        log.error("读取 FFmpeg 输出流失败");
                    }
                }).start();
                StreamSession session = new StreamSession();
                session.process = process;
                session.executor = Executors.newSingleThreadExecutor();
                session.running = true;
                session.lastActiveTime = System.currentTimeMillis();
                session.videoCodec = codecName;
                // 会话必须先注册进 map 再启动监控线程
                channelSessionMap.put(channel, session);
                activeStreamCount.incrementAndGet(); // 成功启动后 +1
                // 监控进程状态（避免僵尸进程）
                session.executor.submit(() -> {
                    try {
                        log.warn("等待 FFmpeg 结束中...");
                        int exitCode = process.waitFor();
                        log.warn("FFmpeg进程退出，用户{}通道{}，退出码{}", userId, channel, exitCode);
                    } catch (Exception e) {
                        log.error("等待 FFmpeg 结束时被中断,通道{}", channel);
                    } finally {
                        stopStream(userId,channel);
                        log.warn("FFmpeg 推流结束，调用 stopStream()");
                    }
                });
                return new StartResult(true, session.videoCodec, "新通道推流已启动");
            } catch (IOException e) {
                e.printStackTrace();
                return new StartResult(false, null, "启动失败: " + e.getMessage());
            }
        }

    }


    /**
     * 停止某个通道的推流任务（所有用户退出时调用）
     */
    public static boolean stopStream(Integer userId, Integer channel) {
        synchronized(streamLock) {
            Set<Integer> users = channelUserMap.get(channel);
            if (users != null) {
                users.remove(userId);
                if (!users.isEmpty()) {
                    log.info("还有其他用户在使用通道 {}，不停止推流,用户{}", channel, users);
                    return false;
                }
            }
            return stopStream2(channel);
        }
    }

    public static boolean stopStream2(Integer channel) {
        StreamSession session = channelSessionMap.remove(channel);
        if (session != null) {
            session.running = false;
            if (session.process != null) session.process.destroy();
            if (session.executor != null) session.executor.shutdownNow();
            activeStreamCount.decrementAndGet(); // 推流结束后 -1
            log.info("已停止通道推流：{}", channel);
        }
        return true;
    }

    /**
     * 查询通道哪些用户正在使用
     *
     * @param channel 通道号
     * @return true 表示正在推流
     */
    public static Set<Integer> isRunning(int channel) {
        return channelUserMap.get(channel);
    }


    // 用于每分钟检查是否有会话空闲超过 15 分钟
    private static final ScheduledExecutorService cleaner = Executors.newScheduledThreadPool(1);
    private static final long TIMEOUT_MS = 15 * 60 * 1000; // 15 分钟

    static {
        cleaner.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (Map.Entry<Integer, StreamSession> entry : channelSessionMap.entrySet()) {
                int channel = entry.getKey();
                StreamSession session = entry.getValue();
                if (now - session.lastActiveTime > TIMEOUT_MS) {
                    log.warn("rtsp通道 {} 空闲超时，自动停止推流", channel);
                    // 清除通道用户
                    channelUserMap.remove(channel);
                    stopStream2(channel);
                }
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 查看哪些通道正在播放
     */
    public static Set<Integer> playingChannels() {
        return channelSessionMap.keySet();
    }

    /**
     * 使用 FFprobe 提取视频编码格式
     */
    public static String extractVideoCodec(String inputUrl) {
        // 定义命令行参数
        String[] command = {
                "ffprobe",
                "-v", "quiet",
                "-print_format", "json",
                "-show_streams",
                inputUrl
        };

        try {
            // 创建进程构建器
            ProcessBuilder builder = new ProcessBuilder(command);
            // 启动进程
            Process process = builder.start();

            // 从进程输入流中读取数据
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            // 创建字符串构建器
            StringBuilder jsonBuilder = new StringBuilder();
            // 逐行读取数据
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBuilder.append(line);
            }
            // 等待进程结束
            process.waitFor();

            // 创建 JSON 解析器
            ObjectMapper mapper = new ObjectMapper();
            // 解析 JSON 数据
            JsonNode root = mapper.readTree(jsonBuilder.toString());
            // 遍历流
            JsonNode streams = root.get("streams");
            if (streams != null && streams.isArray()) {
                for (JsonNode stream : streams) {
                    if ("video".equals(stream.path("codec_type").asText())) {
                        return stream.path("codec_name").asText(); // 更安全的访问方式
                    }
                }
            } else {
                log.warn("警告: 未找到 'streams' 字段或字段格式不是数组！");
                log.warn("原始返回 JSON: " + jsonBuilder);
            }

        } catch (Exception e) {
            // 打印异常信息
            e.printStackTrace();
        }

        // 返回 null
        return null;
    }


}
