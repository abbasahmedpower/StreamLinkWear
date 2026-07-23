import tensorflow as tf
import numpy as np
import pathlib
import sys

# =============================================================================
# ✅ §3.3 FIX 1: Absolute output path — CWD-independent
# Was: "wear/src/main/assets/..." (relative — silently wrong when run from backend/)
# Now: always resolves relative to this script's location → project root
# =============================================================================
REPO_ROOT    = pathlib.Path(__file__).resolve().parent.parent
TELEMETRY_CSV = REPO_ROOT / "ai_training" / "real_session_telemetry.csv"
MODEL_OUTPUT  = REPO_ROOT / "wear" / "src" / "main" / "assets" / "stream_predictor.tflite"

FEATURES = ["frame_minus4", "frame_minus3", "frame_minus2", "frame_minus1",
            "frame_minus0", "jitter_ms", "imu_variance"]
LABELS   = ["label_next_frame_size", "label_congestion_risk"]

# =============================================================================
# ✅ §3.3 FIX 2: Real training data — refuse to run on random noise
# Was: X_train = np.random.rand(...), Y_train = np.random.rand(...)
# A model trained on pure noise cannot learn anything. val_loss will not
# be meaningfully better than predicting the mean. Don't ship that .tflite.
# =============================================================================
if not TELEMETRY_CSV.exists():
    print(
        f"\n[AI TRAIN] ❌ Training data not found: {TELEMETRY_CSV}\n"
        f"  Collect real session telemetry first (see ai_training/README.md).\n"
        f"  Do NOT train on synthetic noise — it produces a model indistinguishable\n"
        f"  from random guessing that LOOKS trained. Ship nothing instead.\n"
        f"  Until real data exists, the app's heuristic fallback is more trustworthy.\n"
    )
    sys.exit(1)

import pandas as pd
df = pd.read_csv(TELEMETRY_CSV)
missing = [c for c in FEATURES + LABELS if c not in df.columns]
if missing:
    print(f"[AI TRAIN] ❌ CSV is missing expected columns: {missing}")
    sys.exit(1)

if len(df) < 1000:
    print(
        f"[AI TRAIN] ❌ Only {len(df)} labeled samples found.\n"
        f"  Minimum 1000 required for a model that generalises.\n"
        f"  Collect more real telemetry before training."
    )
    sys.exit(1)

X_train = df[FEATURES].to_numpy(dtype="float32")
Y_train = df[LABELS].to_numpy(dtype="float32")
print(f"[AI TRAIN] Loaded {len(df)} real telemetry samples from {TELEMETRY_CSV}")

# =============================================================================
# Model architecture — unchanged from original
# =============================================================================
model = tf.keras.Sequential([
    tf.keras.layers.Input(shape=(7,)),
    tf.keras.layers.Dense(32, activation="relu"),
    tf.keras.layers.Dense(16, activation="relu"),
    tf.keras.layers.Dense(2, activation="linear"),
])
model.compile(optimizer="adam", loss="mse")

print("[AI TRAIN] Training model on real session telemetry...")
history = model.fit(X_train, Y_train, epochs=30, batch_size=64, validation_split=0.15)

# =============================================================================
# ✅ §3.3 FIX 3: Sanity check — fail loudly if model learned nothing
# A val_loss ≥ 50% of label variance means the model is noise-level.
# =============================================================================
final_val_loss  = history.history["val_loss"][-1]
noise_baseline  = float(Y_train.var())
if final_val_loss >= 0.5 * noise_baseline:
    print(
        f"\n[AI TRAIN] ❌ val_loss={final_val_loss:.4f} is NOT better than noise baseline "
        f"({noise_baseline:.4f}).\n"
        f"  The model did not learn. Check data quality and label correlation.\n"
        f"  NOT exporting .tflite — do not ship this."
    )
    sys.exit(1)

# =============================================================================
# Export with Float16 quantization (same as before)
# =============================================================================
converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.target_spec.supported_types = [tf.float16]
tflite_model = converter.convert()

MODEL_OUTPUT.parent.mkdir(parents=True, exist_ok=True)
MODEL_OUTPUT.write_bytes(tflite_model)

print(
    f"\n[AI TRAIN] ✅ Model exported to: {MODEL_OUTPUT}\n"
    f"  val_loss={final_val_loss:.4f}  noise_baseline={noise_baseline:.4f}\n"
    f"  Trained on {len(df)} real samples."
)
