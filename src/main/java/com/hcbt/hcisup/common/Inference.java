package com.hcbt.hcisup.common;

import com.hcbt.hcisup.model.Detection;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.IntIndexer;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_dnn.*;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_dnn;
import org.bytedeco.opencv.global.opencv_imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Slf4j
public class Inference {
    private String modelPath;
    private Size modelShape;
    private boolean cudaEnabled;
    private Net net;
    private List<String> classes = List.of(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
    );
    private float modelConfidenceThreshold = 0.25f;  // 模型输出置信度的阈值，低于此阈值的检测框将被过滤掉
    private float modelScoreThreshold = 0.45f;  // 模型输出得分的阈值，用于在非极大值抑制 (NMS) 之前过滤掉低得分的检测框
    private float modelNMSThreshold = 0.50f;  // 非极大值抑制 (NMS) 的 IoU 阈值，用于去除重叠度过高的冗余检测框
    private boolean letterBoxForSquare = true;  // 是否对输入图像进行 Letterbox 处理以适应方形模型输入

    public Inference(String onnxModelPath, Size modelInputShape, boolean runWithCuda) {
        this.modelPath = onnxModelPath;
        this.modelShape = modelInputShape;
        this.cudaEnabled = runWithCuda;
        loadOnnxNetwork();
    }

    private void loadOnnxNetwork() {
        net = opencv_dnn.readNetFromONNX(modelPath);
        if (cudaEnabled) {
            log.info("Running on CUDA");
            net.setPreferableBackend(opencv_dnn.DNN_BACKEND_CUDA);
            net.setPreferableTarget(opencv_dnn.DNN_TARGET_CUDA);
        } else {
            log.info("Running on CPU");
            net.setPreferableBackend(opencv_dnn.DNN_BACKEND_OPENCV);
            net.setPreferableTarget(opencv_dnn.DNN_TARGET_CPU);
        }
    }

    /**
     * 对输入的图像进行推理
     * @param input 待进行目标检测的输入图像 (OpenCV Mat 对象)
     * @return 包含检测结果的 Detection 对象列表
     */
    public List<Detection> runInference(Mat input) {
        Mat modelInput = input.clone();
        int[] padXY = new int[2];
        float[] scale = new float[1];

        // 如果需要将图像调整为正方形
        if (letterBoxForSquare && modelShape.width() == modelShape.height()) {
            modelInput = formatToSquare(modelInput, padXY, scale);
        }

        Mat blob = opencv_dnn.blobFromImage(modelInput, 1.0 / 255.0, modelShape, new Scalar(), true, false, opencv_core.CV_32F);
        net.setInput(blob);

        MatVector outputs = new MatVector();
        StringVector outNames = net.getUnconnectedOutLayersNames();
        net.forward(outputs, outNames);

        Mat output = outputs.get(0);
        int rows = output.size(1);
        int dimensions = output.size(2);

        boolean yolov8 = false;
        if (dimensions > rows) { // 检查shape[2]是否大于shape[1]（YOLOv8）
            yolov8 = true;
            rows = output.size(2);
            dimensions = output.size(1);

            output = output.reshape(1, dimensions);
            Mat transposed = new Mat();
            opencv_core.transpose(output, transposed);
            output = transposed;
        }

        FloatIndexer data = output.createIndexer();
        List<Integer> classIds = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Rect> boxes = new ArrayList<>();

        for (int i = 0; i < rows; i++) {
            if (yolov8) {

                Mat scores = new Mat(1, classes.size(), opencv_core.CV_32FC1);
                FloatIndexer scoresIdx = scores.createIndexer();

                // 复制类别得分
                for (int j = 0; j < classes.size(); j++) {
                    scoresIdx.put(0, j, data.get(i, j + 4));
                }

                // 查找最大得分及其索引
                Point maxLoc = new Point();
                DoublePointer maxVal = new DoublePointer(1);
                opencv_core.minMaxLoc(scores, null, maxVal, null, maxLoc, null);
                double maxScore = maxVal.get();

                if (maxScore > modelScoreThreshold) {
                    confidences.add((float) maxScore);
                    classIds.add(maxLoc.x());

                    float x = data.get(i, 0);
                    float y = data.get(i, 1);
                    float w = data.get(i, 2);
                    float h = data.get(i, 3);

                    int left = (int) ((x - 0.5 * w - padXY[0]) / scale[0]);
                    int top = (int) ((y - 0.5 * h - padXY[1]) / scale[0]);
                    int width = (int) (w / scale[0]);
                    int height = (int) (h / scale[0]);

                    boxes.add(new Rect(left, top, width, height));
                }
            } else {
                // YOLOv5
                float confidence = data.get(i, 4);

                if (confidence >= modelConfidenceThreshold) {
                    Mat scores = new Mat(1, classes.size(), opencv_core.CV_32FC1);
                    FloatIndexer scoresIdx = scores.createIndexer();

                    // 复制类别得分
                    for (int j = 0; j < classes.size(); j++) {
                        scoresIdx.put(0, j, data.get(i, j + 5));
                    }

                    // 查找最大得分及其索引
                    Point maxLoc = new Point();
                    DoublePointer maxVal = new DoublePointer(1);
                    opencv_core.minMaxLoc(scores, null, maxVal, null, maxLoc, null);
                    double maxClassScore = maxVal.get();

                    // 如果得分大于阈值，保存检测结果
                    if (maxClassScore > modelScoreThreshold) {
                        confidences.add(confidence);
                        classIds.add(maxLoc.x());

                        float x = data.get(i, 0);
                        float y = data.get(i, 1);
                        float w = data.get(i, 2);
                        float h = data.get(i, 3);

                        int left = (int) ((x - 0.5 * w - padXY[0]) / scale[0]);
                        int top = (int) ((y - 0.5 * h - padXY[1]) / scale[0]);
                        int width = (int) (w / scale[0]);
                        int height = (int) (h / scale[0]);
                        boxes.add(new Rect(left, top, width, height));
                    }
                }
            }
        }

        Mat bboxesMat = new Mat(boxes.size(), 4, opencv_core.CV_32S);
        IntIndexer bboxIndexer = bboxesMat.createIndexer();
        for (int i = 0; i < boxes.size(); i++) {
            Rect r = boxes.get(i);
            bboxIndexer.put(i, 0, r.x());
            bboxIndexer.put(i, 1, r.y());
            bboxIndexer.put(i, 2, r.width());
            bboxIndexer.put(i, 3, r.height());
        }

        RectVector bboxesVec = new RectVector();
        FloatPointer scoresPtr = new FloatPointer(confidences.size());
        for (int i = 0; i < boxes.size(); i++) {
            Rect r = boxes.get(i);
            bboxesVec.push_back(new Rect(r.x(), r.y(), r.width(), r.height()));
            scoresPtr.put(i, confidences.get(i));
        }
        IntPointer indicesPtr = new IntPointer();
        opencv_dnn.NMSBoxes(bboxesVec, scoresPtr, modelScoreThreshold, modelNMSThreshold, indicesPtr);

        int[] indicesArray = new int[(int) indicesPtr.limit()];
        indicesPtr.get(indicesArray);

        List<Detection> detections = new ArrayList<>();
        Random rand = new Random();
        for (int idx : indicesArray) {
            Detection detection = new Detection();
            detection.setClassId(classIds.get(idx));
            detection.setConfidence(confidences.get(idx));
            detection.setColor(new Scalar(
                    rand.nextInt(156) + 100,
                    rand.nextInt(156) + 100,
                    rand.nextInt(156) + 100,
                    0
            ));
            detection.setClassName(classes.get(detection.getClassId()));
            detection.setBox(boxes.get(idx));
            detections.add(detection);
        }

        return detections;
    }

    /**
     * 将图像转换为正方形，保持纵横比
     * @param source 原始图像
     * @param padXY 填充的X和Y值
     * @param scale 缩放因子
     * @return 处理后的图像
     */
    private Mat formatToSquare(Mat source, int[] padXY, float[] scale) {
        int col = source.cols();
        int row = source.rows();
        int m_inputWidth = modelShape.width();
        int m_inputHeight = modelShape.height();

        scale[0] = Math.min((float) m_inputWidth / col, (float) m_inputHeight / row);
        int resizedW = (int) (col * scale[0]);
        int resizedH = (int) (row * scale[0]);

        padXY[0] = (m_inputWidth - resizedW) / 2;
        padXY[1] = (m_inputHeight - resizedH) / 2;

        Mat resized = new Mat();
        opencv_imgproc.resize(source, resized, new Size(resizedW, resizedH));

        Mat result = new Mat(m_inputHeight, m_inputWidth, source.type(), new Scalar(0, 0, 0, 0));
        Mat roi = result.apply(new Rect(padXY[0], padXY[1], resizedW, resizedH));
        resized.copyTo(roi);

        return result;
    }

}