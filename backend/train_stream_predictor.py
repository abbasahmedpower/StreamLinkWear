import tensorflow as tf
import numpy as np
import pathlib
import pandas as pd
import os

# ✅ §3.3: Use absolute path and read real telemetry
REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
TELEMETRY_CSV = REPO_ROOT / "ai_training" / "real_session_telemetry.csv"
MODEL_OUTPUT = REPO_ROOT / "wear" / "src" / "main" / "assets" / "stream_predictor.tflite"

if not TELEMETRY_CSV.exists():
    # Provide a stub so the script runs and loudly complains instead of crashing on missing file,
    # or just assert immediately if it doesn't exist.
    print(f"[AI TRAIN] ERROR: {TELEMETRY_CSV} not found.")
    print("           Cannot train without real session telemetry.")
    print("           Please collect real data first using the watch logging hook.")
    import sys; sys.exit(1)

df = pd.read_csv(TELEMETRY_CSV)
FEATURES = ["frame_minus4","frame_minus3","frame_minus2","frame_minus1","frame_minus0","jitter_ms","imu_variance"]
LABELS   = ["label_next_frame_size", "label_congestion_risk"]

X_train = df[FEATURES].to_numpy(dtype="float32")
Y_train = df[LABELS].to_numpy(dtype="float32")

assert len(df) >= 1000, (
    f"Only {len(df)} labeled samples found — collect real session telemetry "
    f"(see ai_training/README.md for the logging hook) before training a model that ships to users."
)

# 2. Build the neural architecture (Keras Sequential Model)
model = tf.keras.Sequential([
    tf.keras.layers.Input(shape=(7,)),
    tf.keras.layers.Dense(32, activation='relu'),
    tf.keras.layers.Dense(16, activation='relu'),
    tf.keras.layers.Dense(2, activation='linear') # 2 continuous outputs
])

model.compile(optimizer='adam', loss='mse')
print("[AI TRAIN] Training NASA-Grade Edge Model on Backend...")
history = model.fit(X_train, Y_train, epochs=30, batch_size=64, validation_split=0.15)

# Fail loudly if validation loss is basically noise-level (sanity check, not just "it ran")
final_val_loss = history.history["val_loss"][-1]
NOISE_BASELINE = Y_train.var()   # a model that learned nothing scores ~= variance of the labels
assert final_val_loss < 0.5 * NOISE_BASELINE, (
    f"val_loss={final_val_loss:.4f} is not meaningfully better than the noise baseline "
    f"({NOISE_BASELINE:.4f}) — the model likely learned nothing. Do not ship this .tflite."
)

# 3. Export with high-efficiency Quantization (Float16 Quantization) to reduce model size and speed up Inference
converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.target_spec.supported_types = [tf.float16] # Perfect compatibility with embedded ARM processors

tflite_quantized_model = converter.convert()

# 4. Save the ready model to be injected into the watch's Assets folder
MODEL_OUTPUT.parent.mkdir(parents=True, exist_ok=True)
MODEL_OUTPUT.write_bytes(tflite_quantized_model)

print(f"[AI TRAIN] Success! Quantized model exported to: {MODEL_OUTPUT} (val_loss={final_val_loss:.4f}, baseline={NOISE_BASELINE:.4f})")
