"""
StreamLinkWear TFLite training pipeline.

Runtime feature order:
  0. battery_norm:  battery percentage normalized to 0..1
  1. is_moving:     wrist motion as 0/1
  2. latency_norm:  RTT normalized to 0..1, where 500 ms is 1.0
  3. thermal_norm:  thermal level normalized to 0..1, where 10 is 1.0

Output classes:
  0 = IDLE
  1 = PRELOAD
  2 = STREAM_SMOOTH
  3 = DEGRADED
"""

from __future__ import annotations

import os
from pathlib import Path

import numpy as np
import tensorflow as tf
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report


IDLE         = 0
PRELOAD      = 1
STREAM_SMOOTH = 2
DEGRADED     = 3

# ─── Label noise ──────────────────────────────────────────────────────────────
LABEL_NOISE_RATE = 0.02   # 2 % of samples get a random wrong label


def _apply_label_noise(labels: np.ndarray, num_classes: int = 4, seed: int = 0) -> np.ndarray:
    """Randomly flip a small fraction of labels to prevent overfit on hand-crafted rules."""
    rng = np.random.default_rng(seed)
    noisy = labels.copy()
    n = len(noisy)
    flip_idx = rng.choice(n, size=int(n * LABEL_NOISE_RATE), replace=False)
    for i in flip_idx:
        choices = [c for c in range(num_classes) if c != noisy[i]]
        noisy[i] = rng.choice(choices)
    return noisy


def _assign_label(battery: float, is_moving: float, latency: float,
                  thermal: float, packet_loss: float = 0.0) -> int:
    """
    Graduated label assignment — no duplicate branches, clear class boundaries.

    Priority (highest to lowest):
      1. Critical hardware stress  → DEGRADED
      2. High network impairment   → DEGRADED
      3. Moderate network stress   → PRELOAD
      4. Good conditions + motion  → STREAM_SMOOTH
      5. Good conditions, still    → STREAM_SMOOTH (battery>0.2)
      6. Default                   → IDLE
    """
    # 1. Critical hardware
    if battery < 0.08 or thermal > 0.90:
        return DEGRADED

    # 2. High network impairment  (latency ≥ 0.60  OR  loss ≥ 20 %)
    if latency >= 0.60 or packet_loss >= 0.20:
        return DEGRADED

    # 3. Moderate network stress  (latency ≥ 0.25  OR  loss ≥ 10 %)
    if latency >= 0.25 or packet_loss >= 0.10:
        return PRELOAD

    # 4. Good conditions + wrist in motion
    if is_moving > 0.5 and latency < 0.20:
        return STREAM_SMOOTH

    # 5. Good conditions, battery ok
    if latency < 0.12 and packet_loss < 0.02 and battery > 0.20:
        return STREAM_SMOOTH

    return IDLE


def generate_training_data(n_samples: int = 50_000):
    """
    Generate synthetic dataset from domain rules.

    Increased from 10 K → 50 K for better class coverage.
    Packet-loss dimension added to match the Room-export feature set.
    """
    np.random.seed(42)
    rows   = []
    labels = []

    for _ in range(n_samples):
        battery     = np.random.uniform(0.0, 1.0)
        is_moving   = float(np.random.random() > 0.4)
        latency     = float(np.clip(np.random.exponential(0.15), 0.0, 1.0))
        thermal     = float(np.clip(np.random.beta(1.5, 5.0),    0.0, 1.0))
        packet_loss = float(np.clip(np.random.exponential(0.05),  0.0, 1.0))

        label = _assign_label(battery, is_moving, latency, thermal, packet_loss)
        rows.append([battery, is_moving, latency, thermal])
        labels.append(label)

    x = np.array(rows,   dtype=np.float32)
    y = np.array(labels, dtype=np.int32)
    y = _apply_label_noise(y)
    return x, y


def load_real_data(csv_path: str):
    """
    Load either:
      - ai_training/export_from_room.py output with Room AITrainingEvent fields.
      - legacy ai_events.csv rows:
        timestamp,battery,isMoving,latency,thermal,predicted,outcome
    """
    import pandas as pd

    csv_file = Path(csv_path)
    df = pd.read_csv(csv_file)

    if {"motionIntensity", "rttMs", "packetLossPct", "thermalLevel"}.issubset(df.columns):
        return _load_room_export(df)

    legacy = pd.read_csv(
        csv_file,
        header=None,
        names=["ts", "battery", "moving", "latency", "thermal", "pred", "outcome"],
    )
    legacy  = legacy[legacy["outcome"] == 0]
    features = legacy[["battery", "moving", "latency", "thermal"]].values.astype(np.float32)
    labels   = legacy["pred"].values.astype(np.int32)
    return features, labels


def _load_room_export(df):
    battery = _column_or_default(df, ["battery", "batteryLevel", "battery_norm"], 1.0)
    battery = np.clip(_normalize_percent_like(battery), 0.0, 1.0)

    moving      = (df["motionIntensity"].astype(float).to_numpy() > 0.15).astype(np.float32)
    latency     = np.clip(df["rttMs"].astype(float).to_numpy() / 500.0,      0.0, 1.0)
    thermal     = np.clip(df["thermalLevel"].astype(float).to_numpy() / 10.0, 0.0, 1.0)

    packet_loss = df["packetLossPct"].astype(float).to_numpy()
    packet_loss = np.where(packet_loss > 1.0, packet_loss / 100.0, packet_loss)
    packet_loss = np.clip(packet_loss, 0.0, 1.0)

    recommended = _column_or_default(df, ["recommendedBitrate"], 0.0)
    chosen      = _column_or_default(df, ["chosenBitrate"],      0.0)

    labels = []
    for i in range(len(df)):
        # Detect user override: chosen bitrate significantly below recommendation → DEGRADED
        override = (recommended[i] > 0 and chosen[i] < recommended[i] * 0.8)
        label = _assign_label(
            battery[i], float(moving[i]), latency[i], thermal[i], packet_loss[i]
        )
        if override and label != DEGRADED:
            label = PRELOAD     # Soft override: not fully degraded but preload mode
        labels.append(label)

    features = np.stack([battery, moving, latency, thermal], axis=1).astype(np.float32)
    return features, np.array(labels, dtype=np.int32)


def _column_or_default(df, names, default):
    for name in names:
        if name in df.columns:
            return df[name].astype(float).to_numpy()
    return np.full(len(df), default, dtype=np.float32)


def _normalize_percent_like(values):
    values = np.asarray(values, dtype=np.float32)
    return np.where(values > 1.0, values / 100.0, values)


def build_model(num_classes: int = 4) -> tf.keras.Model:
    """
    Improved architecture: 32 → BatchNorm → Dropout(0.2) → 16 → 4
    Balances capacity vs. TFLite binary size (~8 KB on device).
    """
    model = tf.keras.Sequential(
        [
            tf.keras.layers.Input(shape=(4,), name="features"),
            tf.keras.layers.Dense(
                32,
                activation="relu",
                name="hidden_1",
                kernel_regularizer=tf.keras.regularizers.l2(0.001),
            ),
            tf.keras.layers.BatchNormalization(name="bn_1"),
            tf.keras.layers.Dropout(0.2, name="dropout_1"),
            tf.keras.layers.Dense(
                16,
                activation="relu",
                name="hidden_2",
                kernel_regularizer=tf.keras.regularizers.l2(0.001),
            ),
            tf.keras.layers.Dense(num_classes, activation="softmax", name="output"),
        ],
        name="stream_predictor",
    )

    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.003),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    return model


def convert_to_tflite(model, x_train, output_path: str = "stream_predictor.tflite"):
    """
    Optimize the model while keeping float32 input/output.

    The Android runtime feeds FloatArray features. Keeping float I/O avoids the
    previous mismatch where the converter produced uint8 tensors but Kotlin sent
    float tensors.
    """

    def representative_dataset():
        for i in range(min(500, len(x_train))):
            yield [x_train[i : i + 1]]

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_dataset

    tflite_model = converter.convert()
    with open(output_path, "wb") as f:
        f.write(tflite_model)

    size_kb = len(tflite_model) / 1024
    print(f"TFLite model saved: {output_path} ({size_kb:.1f} KB)")
    return output_path


def _print_per_class_report(model, x_val, y_val):
    """Print per-class precision/recall/F1 for quick accuracy diagnostics."""
    y_pred = model.predict(x_val, verbose=0).argmax(axis=1)
    class_names = ["IDLE", "PRELOAD", "STREAM_SMOOTH", "DEGRADED"]
    print("\nPer-class report:")
    print(classification_report(y_val, y_pred, target_names=class_names, zero_division=0))


def main():
    print("StreamLinkWear TFLite Training Pipeline")
    print("=" * 44)

    script_dir = Path(__file__).resolve().parent
    csv_path   = Path(os.environ.get("STREAMLINK_AI_CSV", script_dir / "ai_events.csv"))

    if csv_path.exists():
        print(f"Loading real device data from {csv_path}")
        x, y = load_real_data(str(csv_path))
        print(f"Samples loaded: {len(x)}")
    else:
        print("Generating synthetic training data (50,000 samples)")
        x, y = generate_training_data(n_samples=50_000)

    if len(x) < 4:
        raise ValueError("Need at least 4 training rows")

    # Print class distribution
    unique, counts = np.unique(y, return_counts=True)
    class_names = {0: "IDLE", 1: "PRELOAD", 2: "STREAM_SMOOTH", 3: "DEGRADED"}
    print("Class distribution:")
    for cls, cnt in zip(unique, counts):
        print(f"  {class_names.get(cls, cls)}: {cnt} ({100*cnt/len(y):.1f}%)")

    x_train, x_val, y_train, y_val = train_test_split(
        x,
        y,
        test_size=0.15,
        random_state=42,
        stratify=y if len(set(y.tolist())) > 1 else None,
    )
    print(f"Train: {len(x_train)} | Val: {len(x_val)}")

    model = build_model()
    model.summary()

    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor="val_accuracy",
            patience=15,
            restore_best_weights=True,
            min_delta=0.001,
        ),
        tf.keras.callbacks.ReduceLROnPlateau(
            monitor="val_loss",
            factor=0.5,
            patience=5,
            min_lr=1e-5,
        ),
    ]

    model.fit(
        x_train,
        y_train,
        validation_data=(x_val, y_val),
        epochs=200,
        batch_size=256,
        callbacks=callbacks,
        verbose=1,
    )

    loss, acc = model.evaluate(x_val, y_val, verbose=0)
    print(f"\nValidation: loss={loss:.4f} accuracy={acc:.4f}")
    _print_per_class_report(model, x_val, y_val)

    output_path = script_dir / "stream_predictor.tflite"
    convert_to_tflite(model, x_train, str(output_path))

    print("\nCopy the model to:")
    print("  wear/src/main/assets/stream_predictor.tflite")
    print("  app/src/main/assets/stream_predictor.tflite")


if __name__ == "__main__":
    main()
