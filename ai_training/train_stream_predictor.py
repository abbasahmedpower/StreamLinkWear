"""
StreamLinkWear — TFLite Model Training Pipeline
================================================
Trains a micro neural network to predict streaming action:
  0 = IDLE, 1 = PRELOAD, 2 = STREAM_SMOOTH, 3 = DEGRADED

Input features (4):
  - battery_norm:    battery level 0–1
  - is_moving:       wrist motion binary 0/1
  - latency_norm:    RTT normalized 0–1 (500ms = 1.0)
  - thermal_norm:    thermal level 0–1 (10 = 1.0)

Model target size: < 200KB
Inference time:    < 5ms on WearOS (Snapdragon W5+)

Usage:
  pip install tensorflow numpy pandas scikit-learn
  python train_stream_predictor.py
  --> outputs: stream_predict_model.tflite (~32KB)
"""

import numpy as np
import tensorflow as tf
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import LabelEncoder
import os


# ── Synthetic Training Data Generator ──────────────────────────────────────

def generate_training_data(n_samples: int = 10_000):
    """
    Generates labelled training examples based on domain rules.
    In production: replace with logged AIEventLogger CSV data from devices.
    """
    np.random.seed(42)
    X = []
    y = []

    for _ in range(n_samples):
        battery   = np.random.uniform(0.0, 1.0)
        is_moving = float(np.random.random() > 0.4)
        latency   = np.clip(np.random.exponential(0.15), 0.0, 1.0)
        thermal   = np.clip(np.random.beta(1.5, 5.0), 0.0, 1.0)

        # Ground-truth label from domain rules (mirrors DecisionEngine.kt)
        if battery < 0.08 or thermal > 0.9:
            label = 3  # DEGRADED
        elif latency > 0.45:
            label = 3  # DEGRADED (high latency)
        elif latency > 0.20:
            label = 1  # PRELOAD
        elif is_moving > 0.5 and latency < 0.20:
            label = 2  # STREAM_SMOOTH (moving, low latency)
        elif latency < 0.12 and battery > 0.20:
            label = 2  # STREAM_SMOOTH
        else:
            label = 0  # IDLE

        X.append([battery, is_moving, latency, thermal])
        y.append(label)

    return np.array(X, dtype=np.float32), np.array(y, dtype=np.int32)


# ── Load from real AIEventLogger CSV (optional) ────────────────────────────

def load_real_data(csv_path: str):
    """
    ai_events.csv format (from AIEventLogger.kt):
    timestamp, battery, isMoving, latency, thermal, predicted, outcome
    """
    import pandas as pd
    df = pd.read_csv(csv_path, header=None,
                     names=["ts","battery","moving","latency","thermal","pred","outcome"])
    # Only keep rows where outcome=0 (correct prediction)
    df = df[df["outcome"] == 0]
    X = df[["battery","moving","latency","thermal"]].values.astype(np.float32)
    y = df["pred"].values.astype(np.int32)
    return X, y


# ── Model Architecture ──────────────────────────────────────────────────────

def build_model(num_classes: int = 4) -> tf.keras.Model:
    """
    Ultra-lightweight model optimized for WearOS inference:
    4 inputs → Dense(16, relu) → Dense(8, relu) → Dense(4, softmax)
    Parameters: ~300 floats → ~1.2KB raw → ~32KB TFLite (with metadata)
    """
    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(4,), name="features"),
        tf.keras.layers.Dense(16, activation="relu", name="hidden_1",
                              kernel_regularizer=tf.keras.regularizers.l2(0.001)),
        tf.keras.layers.Dense(8, activation="relu", name="hidden_2"),
        tf.keras.layers.Dense(num_classes, activation="softmax", name="output")
    ], name="stream_predictor")

    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.003),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"]
    )
    return model


# ── TFLite Converter with INT8 Quantization ─────────────────────────────────

def convert_to_tflite(model, X_train, output_path: str = "stream_predict_model.tflite"):
    """
    INT8 quantization reduces model size by 4× and inference time by 2–3×.
    Compatible with TensorFlow Lite 2.14+ on Android.
    """
    def representative_dataset():
        for i in range(min(500, len(X_train))):
            yield [X_train[i:i+1]]

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_dataset
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type  = tf.uint8
    converter.inference_output_type = tf.uint8

    tflite_model = converter.convert()

    with open(output_path, "wb") as f:
        f.write(tflite_model)

    size_kb = len(tflite_model) / 1024
    print(f"✅ TFLite model saved: {output_path} ({size_kb:.1f} KB)")
    return output_path


# ── Encrypt for SecureModelLoader ───────────────────────────────────────────

def encrypt_model(tflite_path: str, key: bytes, output_path: str = "stream_predict_model_enc.tflite"):
    """
    AES-256-GCM encryption matching SecureModelLoader.kt.
    key: 32 bytes (same as aesKey in SecureModelLoader)
    """
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    import os

    with open(tflite_path, "rb") as f:
        plaintext = f.read()

    iv = os.urandom(12)  # 96-bit GCM nonce
    aesgcm = AESGCM(key)
    ciphertext = aesgcm.encrypt(iv, plaintext, None)

    with open(output_path, "wb") as f:
        f.write(iv + ciphertext)

    print(f"🔒 Encrypted model: {output_path}")


# ── Main Training Script ────────────────────────────────────────────────────

def main():
    print("StreamLinkWear — TFLite Training Pipeline")
    print("=" * 45)

    # 1. Data
    real_csv = "ai_events.csv"
    if os.path.exists(real_csv):
        print(f"📊 Loading real device data from {real_csv}")
        X, y = load_real_data(real_csv)
        print(f"   Samples loaded: {len(X)}")
    else:
        print("📊 Generating synthetic training data (10,000 samples)…")
        X, y = generate_training_data(n_samples=10_000)

    X_train, X_val, y_train, y_val = train_test_split(
        X, y, test_size=0.15, random_state=42, stratify=y
    )
    print(f"   Train: {len(X_train)} | Val: {len(X_val)}")

    # 2. Train
    print("\n🏋️  Training model…")
    model = build_model()
    model.summary()

    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor="val_accuracy", patience=10,
            restore_best_weights=True, min_delta=0.001
        ),
        tf.keras.callbacks.ReduceLROnPlateau(
            monitor="val_loss", factor=0.5, patience=5
        )
    ]

    history = model.fit(
        X_train, y_train,
        validation_data=(X_val, y_val),
        epochs=100,
        batch_size=256,
        callbacks=callbacks,
        verbose=1
    )

    # 3. Evaluate
    loss, acc = model.evaluate(X_val, y_val, verbose=0)
    print(f"\n📈 Validation — loss: {loss:.4f}  accuracy: {acc:.4f}")

    if acc < 0.88:
        print("⚠️  Accuracy below 88% — consider more training data")

    # 4. Convert → TFLite
    print("\n🔄 Converting to TFLite (INT8)…")
    tflite_path = convert_to_tflite(model, X_train)

    # 5. Encrypt (optional — requires: pip install cryptography)
    try:
        # Must match SecureModelLoader.kt aesKey byte-for-byte
        aes_key = bytes([
            0x42, 0x12, 0x78, 0x34, 0x56, 0x11, 0x9A, 0xBC,
            0xDE, 0xF0, 0x12, 0x34, 0x56, 0x78, 0x90, 0xAB,
            0x12, 0x34, 0x56, 0x78, 0x90, 0xAB, 0xCD, 0xEF,
            0x12, 0x34, 0x56, 0x78, 0x90, 0xAB, 0xCD, 0x11
        ])
        encrypt_model(tflite_path, aes_key)
    except ImportError:
        print("ℹ️  cryptography not installed — skipping encryption")
        print("    pip install cryptography")

    print("\n✅ Done!")
    print("   Copy stream_predict_model.tflite to:")
    print("   wear/src/main/assets/stream_predict_model.tflite")
    print("   (or the _enc.tflite version for production)")


if __name__ == "__main__":
    main()
