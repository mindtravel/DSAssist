"""Rebuild the bundled JVM/Android BPE tables from DeepSeek's published tokenizer."""
import argparse
import gzip
import hashlib
import io
import json
import struct
import urllib.request
import zipfile
from pathlib import Path

URL = "https://cdn.deepseek.com/api-docs/deepseek_v4_tokenizer.zip"
SHA256 = "e7310d1dafe0a86d8a5629fe78a7c763760f651db9b8682718a1781dcd6fe495"
OUTPUT = Path(__file__).resolve().parents[2] / "app/src/main/resources/deepseek/v4.bpe.gz"


def convert(archive):
    if hashlib.sha256(archive).hexdigest() != SHA256:
        raise ValueError("Unexpected DeepSeek tokenizer checksum")
    with zipfile.ZipFile(io.BytesIO(archive)) as package:
        config = json.loads(package.read("deepseek_v4_tokenizer/tokenizer.json"))
    vocab = config["model"]["vocab"]
    # Hugging Face ByteLevel maps each UTF-8 byte to one printable Unicode symbol.
    plain = [*range(33, 127), *range(161, 173), *range(174, 256)]
    alphabet = dict(zip(plain, plain))
    for index, byte in enumerate(byte for byte in range(256) if byte not in plain):
        alphabet[byte] = 256 + index
    merges = []
    for rank, rule in enumerate(config["model"]["merges"]):
        left, right = rule.split(" ")
        merged = vocab[left + right]
        assert merged == rank + 259  # The merged ID also gives the merge priority.
        merges.append((vocab[left], vocab[right], merged))
    data = io.BytesIO()

    def number(value):
        data.write(struct.pack(">i", value))

    def string(value):
        raw = value.encode()
        number(len(raw))
        data.write(raw)

    for byte in range(256):
        number(vocab[chr(alphabet[byte])])
    splits = config["pre_tokenizer"]["pretokenizers"][:-1]
    number(len(splits))
    for split in splits:
        assert split["type"] == "Split" and split["behavior"] == "Isolated"
        string(split["pattern"]["Regex"])
    number(len(config["added_tokens"]))
    for token in config["added_tokens"]:
        assert not any(token[k] for k in ("lstrip", "rstrip", "single_word"))
        string(token["content"])
        number(token["id"])
    number(len(merges))
    for left, right, merged in sorted(merges):
        data.write(struct.pack(">iii", left, right, merged))
    with OUTPUT.open("wb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as result:
        result.write(data.getvalue())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", nargs="?", type=Path, help="Previously downloaded official zip")
    args = parser.parse_args()
    convert(args.archive.read_bytes() if args.archive else urllib.request.urlopen(URL, timeout=30).read())
