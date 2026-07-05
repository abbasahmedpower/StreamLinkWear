"""
Export Wear OS Room AITrainingEvent rows to ai_events.csv for training.

Typical usage after pulling the DB from a device:
  adb shell run-as com.streamlink.wear cp databases/streamlink_wear_db /sdcard/streamlink_wear_db
  adb pull /sdcard/streamlink_wear_db ai_training/streamlink_wear_db
  python ai_training/export_from_room.py ai_training/streamlink_wear_db
"""

from __future__ import annotations

import argparse
import csv
import sqlite3
from pathlib import Path


FIELDS = [
    "timestamp",
    "motionIntensity",
    "rttMs",
    "packetLossPct",
    "thermalLevel",
    "recommendedBitrate",
    "chosenBitrate",
]


def export_events(db_path: Path, output_path: Path) -> int:
    query = """
        SELECT timestamp,
               motionIntensity,
               rttMs,
               packetLossPct,
               thermalLevel,
               recommendedBitrate,
               chosenBitrate
          FROM ai_training_events
      ORDER BY timestamp ASC
    """

    conn = sqlite3.connect(db_path)
    try:
        rows = conn.execute(query).fetchall()
    finally:
        conn.close()

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(FIELDS)
        writer.writerows(rows)

    return len(rows)


def main():
    parser = argparse.ArgumentParser(description="Export StreamLinkWear AI Room events to CSV")
    parser.add_argument("db", type=Path, help="Path to the pulled streamlink_wear_db SQLite file")
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent / "ai_events.csv",
        help="Output CSV path",
    )
    args = parser.parse_args()

    count = export_events(args.db, args.output)
    print(f"Exported {count} AI training rows to {args.output}")


if __name__ == "__main__":
    main()
