#!/usr/bin/env python3
"""
يحوّل checkpoint الـ training pipeline إلى .tflite قابل للاستخدام في التطبيق.
شغّله بعد كل تدريب جديد، وحدّث الـ version tag في assets/ai/model_version.txt
"""
import tensorflow as tf
import sys
import hashlib
from pathlib import Path

def export(checkpoint_path: str, output_path: str):
    model = tf.keras.models.load_model(checkpoint_path)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()

    out = Path(output_path)
    out.write_bytes(tflite_model)

    checksum = hashlib.sha256(tflite_model).hexdigest()[:12]
    version_file = out.parent / "model_version.txt"
    version_file.write_text(f"{checksum}\n")

    print(f"✅ Exported {out} ({len(tflite_model)} bytes, checksum={checksum})")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: export_model.py <checkpoint_path> <output.tflite>")
        sys.exit(1)
    export(sys.argv[1], sys.argv[2])
