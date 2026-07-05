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


IDLE = 0
PRELOAD = 1
STREAM_SMOOTH = 2
DEGRADED = 3


def generate_training_data(n_samples: int = 10_000):
    """Generate a fallback dataset from the same domain rules used in the app."""
    np.random.seed(42)
    rows = []
    labels = []

    for _ in range(n_samples):
        battery = np.random.uniform(0.0, 1.0)
        is_moving = float(np.random.random() > 0.4)
        latency = np.clip(np.random.exponential(0.15), 0.0, 1.0)
        thermal = np.clip(np.random.beta(1.5, 5.0), 0.0, 1.0)

        if battery < 0.08 or thermal > 0.9:
            label = DEGRADED
        elif latency > 0.45:
            label = DEGRADED
        elif latency > 0.20:
            label = PRELOAD
        elif is_moving > 0.5 and latency < 0.20:
            label = STREAM_SMOOTH
        elif latency < 0.12 and battery > 0.20:
            label = STREAM_SMOOTH
        else:
            label = IDLE

        rows.append([battery, is_moving, latency, thermal])
        labels.append(label)

    return np.array(rows, dtype=np.float32), np.array(labels, dtype=np.int32)


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
    legacy = legacy[legacy["outcome"] == 0]
    features = legacy[["battery", "moving", "latency", "thermal"]].values.astype(np.float32)
    labels = legacy["pred"].values.astype(np.int32)
    return features, labels


def _load_room_export(df):
    battery = _column_or_default(df, ["battery", "batteryLevel", "battery_norm"], 1.0)
    battery = np.clip(_normalize_percent_like(battery), 0.0, 1.0)

    moving = (df["motionIntensity"].astype(float).to_numpy() > 0.15).astype(np.float32)
    latency = np.clip(df["rttMs"].astype(float).to_numpy() / 500.0, 0.0, 1.0)
    thermal = np.clip(df["thermalLevel"].astype(float).to_numpy() / 10.0, 0.0, 1.0)

    packet_loss = df["packetLossPct"].astype(float).to_numpy()
    packet_loss = np.where(packet_loss > 1.0, packet_loss / 100.0, packet_loss)
    packet_loss = np.clip(packet_loss, 0.0, 1.0)

    recommended = _column_or_default(df, ["recommendedBitrate"], 0.0)
    chosen = _column_or_default(df, ["chosenBitrate"], 0.0)

    labels = []
    for i in range(len(df)):
        if battery[i] < 0.08 or thermal[i] > 0.9:
            label = DEGRADED
        elif packet_loss[i] >= 0.20 or latency[i] > 0.80:
            label = DEGRADED
        elif packet_loss[i] > 0.10 or latency[i] > 0.40:
            label = DEGRADED
        elif recommended[i] > 0 and chosen[i] < recommended[i] * 0.8:
            label = DEGRADED
        elif latency[i] > 0.20:
            label = PRELOAD
        elif moving[i] > 0.5 and latency[i] < 0.20:
            label = STREAM_SMOOTH
        elif latency[i] < 0.12 and packet_loss[i] < 0.02 and battery[i] > 0.20:
            label = STREAM_SMOOTH
        else:
            label = IDLE
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
    model = tf.keras.Sequential(
        [
            tf.keras.layers.Input(shape=(4,), name="features"),
            tf.keras.layers.Dense(
                16,
                activation="relu",
                name="hidden_1",
                kernel_regularizer=tf.keras.regularizers.l2(0.001),
            ),
            tf.keras.layers.Dense(8, activation="relu", name="hidden_2"),
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


def main():
    print("StreamLinkWear TFLite Training Pipeline")
    print("=" * 44)

    script_dir = Path(__file__).resolve().parent
    csv_path = Path(os.environ.get("STREAMLINK_AI_CSV", script_dir / "ai_events.csv"))

    if csv_path.exists():
        print(f"Loading real device data from {csv_path}")
        x, y = load_real_data(str(csv_path))
        print(f"Samples loaded: {len(x)}")
    else:
        print("Generating synthetic training data (10,000 samples)")
        x, y = generate_training_data(n_samples=10_000)

    if len(x) < 4:
        raise ValueError("Need at least 4 training rows")

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
            patience=10,
            restore_best_weights=True,
            min_delta=0.001,
        ),
        tf.keras.callbacks.ReduceLROnPlateau(
            monitor="val_loss",
            factor=0.5,
            patience=5,
        ),
    ]

    model.fit(
        x_train,
        y_train,
        validation_data=(x_val, y_val),
        epochs=100,
        batch_size=256,
        callbacks=callbacks,
        verbose=1,
    )

    loss, acc = model.evaluate(x_val, y_val, verbose=0)
    print(f"Validation: loss={loss:.4f} accuracy={acc:.4f}")

    output_path = script_dir / "stream_predictor.tflite"
    convert_to_tflite(model, x_train, str(output_path))

    print("Copy the model to:")
    print("  app/src/main/assets/stream_predictor.tflite")


if __name__ == "__main__":
    main()
