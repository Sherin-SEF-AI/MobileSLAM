package com.mappilot.perception.detection

import com.mappilot.core.model.AssetClass

/**
 * COCO-80 labels (the classes YOLO11n is trained on) and their mapping to
 * road-asset categories.
 *
 * Only classes the model genuinely detects are mapped. Asset categories absent
 * from COCO (potholes, lane/road markings, poles, speed breakers, construction
 * zones) require a purpose-trained model and are produced by the cloud
 * open-vocabulary pipeline — they are NOT fabricated on-device. The full COCO
 * label is preserved on each detection for traceability.
 */
object CocoLabels {
    val NAMES: List<String> = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
        "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush",
    )

    const val COUNT = 80

    /** Maps a COCO class index to a road-asset category, or null if not a road asset. */
    fun toAssetClass(classIndex: Int): AssetClass? = when (NAMES.getOrNull(classIndex)) {
        "traffic light" -> AssetClass.TRAFFIC_LIGHT
        "stop sign" -> AssetClass.TRAFFIC_SIGN
        "fire hydrant", "parking meter" -> AssetClass.POLE // pole-mounted street furniture
        else -> null
    }

    fun name(classIndex: Int): String = NAMES.getOrNull(classIndex) ?: "class_$classIndex"
}
