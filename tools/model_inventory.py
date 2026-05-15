from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def size_mb(path: Path) -> float:
    if path.is_dir():
        total = sum(item.stat().st_size for item in path.rglob("*") if item.is_file())
    else:
        total = path.stat().st_size
    return round(total / (1024 * 1024), 2)


def main() -> None:
    inventory = {
        "gillick": {
            "artifact": str(ROOT / "gillick" / "checkpoints" / "in_use" / "resnet_with_augmentation" / "best.pth.tar"),
        },
        "omine": {
            "artifact": str(ROOT / "omine" / "LaughterSegmentation" / "models" / "model.safetensors"),
        },
        "ideo": {
            "artifact": str(ROOT / "ideo" / "Models" / "LSTM_SingleLayer_100Epochs.h5"),
        },
        "hhoangphuoc_wav2vec2": {
            "artifact": str(ROOT / "hhoangphuoc" / "fine-tuned" / "wav2vec2" / "finetuned-wav2vec2+FT+L"),
        },
    }
    for item in inventory.values():
        item["size_mb"] = size_mb(Path(item["artifact"]))
    out_path = ROOT / "reports" / "model_inventory.json"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(inventory, indent=2), encoding="utf-8")
    print(json.dumps(inventory, indent=2))


if __name__ == "__main__":
    main()
