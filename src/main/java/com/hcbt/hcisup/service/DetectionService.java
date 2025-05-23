package com.hcbt.hcisup.service;

import com.hcbt.hcisup.common.Inference;
import com.hcbt.hcisup.model.Detection;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class DetectionService {
    private final Inference inferenceVest;
    private final Inference inferencePedestrian;
    private final Path resultDir;

    public DetectionService(
            @Value("${app.models.model-path-vest}") String modelPathVest,
            @Value("${app.models.model-path-pedestrian}") String modelPathPedestrian,
            @Value("${app.result-dir}") String resultDirPath) {
        this.resultDir = Paths.get(resultDirPath);
        log.info("Loading vest model from: " + modelPathVest);
        log.info("Loading pedestrian model from: " + modelPathPedestrian);
        List<String> classesVest = loadClasses(modelPathVest.replace(".onnx", ".txt"));
        List<String> classesPedestrian = loadClasses(modelPathPedestrian.replace(".onnx", ".txt"));
        this.inferenceVest = new Inference(modelPathVest, new Size(640, 640), false, classesVest);
        this.inferencePedestrian = new Inference(modelPathPedestrian, new Size(640, 640), false, classesPedestrian);
    }

    private List<String> loadClasses(String filePath) {
        try {
            log.info("Loading classes from: " + filePath);
            return Files.readAllLines(Paths.get(filePath));
        } catch (IOException e) {
            throw new RuntimeException("无法读取类名文件: " + filePath, e);
        }
    }

    /**
     * 检测图像中的对象并返回处理后的图像文件名
     * @param imagePath 图像文件路径
     * @return 处理后的图像文件名
     */
    public String detectImage(String imagePath) {
        // 加载图像
        log.info("Loading image from: " + imagePath);
        Mat image = opencv_imgcodecs.imread(imagePath);
        if (image.empty()) {
            throw new RuntimeException("无法读取图像文件: " + imagePath);
        }

        // 运行对象检测
        List<Detection> detectionsVest = inferenceVest.runInference(image);
        for (Detection d : detectionsVest) {
            d.setColor(new Scalar(0, 255, 0, 0)); // 绿色用于安全背心
        }

        List<Detection> detectionsPedestrian = inferencePedestrian.runInference(image);
        for (Detection d : detectionsPedestrian) {
            d.setColor(new Scalar(255, 0, 0, 0)); // 蓝色用于行人
        }

        // 在图像上绘制检测结果
        List<Detection> allDetections = new ArrayList<>();
        allDetections.addAll(detectionsVest);
        allDetections.addAll(detectionsPedestrian);

        drawDetections(image, allDetections);

        // 保存结果图像
        String originalFilename = Paths.get(imagePath).getFileName().toString();
        String resultFilename = "result_" + originalFilename;
        Path resultPath = resultDir.resolve(resultFilename);
        opencv_imgcodecs.imwrite(resultPath.toString(), image);

        return resultFilename;
    }

    /**
     * 处理视频文件并进行对象检测
     * @param videoPath 视频文件路径
     * @return 处理后的视频文件名
     */
    public String detectVideo(String videoPath) {
        // 生成唯一的输出文件名
        String resultFilename = "result_" + UUID.randomUUID().toString() + ".mp4";
        String outputPath = resultDir.resolve(resultFilename).toString();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath)) {
            grabber.start();

            // 获取视频参数
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            double frameRate = grabber.getVideoFrameRate();

            // 创建视频写入器
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputPath, width, height);
            recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264);
            recorder.setFormat("mp4");
            recorder.setFrameRate(frameRate);
            recorder.setVideoBitrate(grabber.getVideoBitrate());
            recorder.start();

            // 创建OpenCV帧转换器
            OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();

            // 处理每一帧
            Frame frame;
            while ((frame = grabber.grab()) != null) {
                if (frame.image != null) {
                    // 将Java帧转换为OpenCV Mat
                    Mat mat = converter.convert(frame);

                    // 检测对象
                    List<Detection> detectionsVest = inferenceVest.runInference(mat);
                    for (Detection d : detectionsVest) {
                        d.setColor(new Scalar(0, 255, 0, 0)); // 绿色用于安全背心
                    }

                    List<Detection> detectionsPedestrian = inferencePedestrian.runInference(mat);
                    for (Detection d : detectionsPedestrian) {
                        d.setColor(new Scalar(255, 0, 0, 0)); // 蓝色用于行人
                    }

                    List<Detection> allDetections = new ArrayList<>();
                    allDetections.addAll(detectionsVest);
                    allDetections.addAll(detectionsPedestrian);

                    // 在帧上绘制检测结果
                    drawDetections(mat, allDetections);

                    // 将处理后的帧写入输出视频
                    recorder.record(converter.convert(mat));
                }
            }

            // 关闭资源
            recorder.stop();
            recorder.release();
            grabber.stop();

            return resultFilename;
        } catch (Exception e) {
            throw new RuntimeException("视频处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 在图像上绘制检测结果
     * @param image 图像
     * @param detections 检测结果列表
     */
    void drawDetections(Mat image, List<Detection> detections) {
        if (detections == null) {
            log.warn("检测结果为空，跳过绘制");
            return;
        }

        for (Detection detection : detections) {
            // 绘制边界框
            opencv_imgproc.rectangle(
                    image,
                    detection.getBox(),
                    detection.getColor(),
                    2,
                    opencv_imgproc.LINE_8,
                    0);

            // 准备标签文本
            String label = detection.getClassName() + ": " + String.format("%.2f", detection.getConfidence());

            // 获取文本大小
            int[] baseLine = new int[1];
            Size labelSize = opencv_imgproc.getTextSize(
                    label,
                    opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                    0.6,
                    1,
                    baseLine);

            // 计算标签位置，确保不超出图像边界
            int x = Math.max(detection.getBox().x(), 0);
            int y = Math.max(detection.getBox().y() - labelSize.height(), 0);

            // 如果标签太靠近图像顶部，则将其放在框的底部
            if (y < 5) {
                y = detection.getBox().y() + detection.getBox().height();
            }

            // 确保标签宽度不超出图像
            int width = Math.min(labelSize.width(), image.cols() - x);

            // 绘制标签背景
            opencv_imgproc.rectangle(
                    image,
                    new Point(x, y),
                    new Point(x + width, y + labelSize.height() + baseLine[0]),
                    detection.getColor()
            );

            // 绘制标签文本
            opencv_imgproc.putText(
                    image,
                    label,
                    new Point(x, y + labelSize.height()),
                    opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                    0.6,
                    new Scalar(255, 255, 255, 0),
                    1,
                    opencv_imgproc.LINE_AA,
                    false);
        }
    }

    public List<Detection> runInference(Mat image) {
        List<Detection> detectionsVest = inferenceVest.runInference(image);
        List<Detection> detectionsPedestrian = inferencePedestrian.runInference(image);
        detectionsVest.addAll(detectionsPedestrian);
        return detectionsVest;
    }
}