#!/usr/bin/env python3
"""Verify pinned model, tensor contract, metadata normalization and label ordering.

Uses a minimal read-only FlatBuffer reader to avoid a TensorFlow/Python dependency.
Field numbers follow TensorFlow Lite schema.fbs and metadata_schema.fbs.
"""
import hashlib
from pathlib import Path
import struct
import zipfile

ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets'
MODEL = ASSETS / 'efficientnet-lite0-fp32.tflite'
SHA256 = '6c7ab0a6e5dcbf38a8c33b960996a55a3b4300b36a018c4545801de3a3c8bde0'
URL = ('https://storage.googleapis.com/mediapipe-models/image_classifier/'
       'efficientnet_lite0/float32/1/efficientnet_lite0.tflite')


class FlatBuffer:
    def __init__(self, data):
        self.data = data
        self.root = self.u32(0)

    def u32(self, offset):
        return struct.unpack_from('<I', self.data, offset)[0]

    def indirect(self, offset):
        return offset + self.u32(offset)

    def field(self, table, index):
        vtable = table - struct.unpack_from('<i', self.data, table)[0]
        position = 4 + 2 * index
        if position >= struct.unpack_from('<H', self.data, vtable)[0]:
            return None
        offset = struct.unpack_from('<H', self.data, vtable + position)[0]
        return table + offset if offset else None

    def vector(self, offset, tables=True):
        vector = self.indirect(offset)
        positions = [vector + 4 + i * 4 for i in range(self.u32(vector))]
        return [self.indirect(p) for p in positions] if tables else positions

    def string(self, offset):
        start = self.indirect(offset)
        return self.data[start + 4:start + 4 + self.u32(start)].decode()

    def floats(self, offset):
        return [struct.unpack_from('<f', self.data, p)[0] for p in self.vector(offset, False)]


def verify():
    data = MODEL.read_bytes()
    assert hashlib.sha256(data).hexdigest() == SHA256, 'Model checksum mismatch'
    assert data[4:8] == b'TFL3', 'Not a LiteRT FlatBuffer'
    model = FlatBuffer(data)
    subgraph = model.vector(model.field(model.root, 2))[0]
    tensors = model.vector(model.field(subgraph, 0))
    for field, expected in [(1, [1, 224, 224, 3]), (2, [1, 1000])]:
        tensor_index = model.u32(model.vector(model.field(subgraph, field), False)[0])
        tensor = tensors[tensor_index]
        shape = [model.u32(p) for p in model.vector(model.field(tensor, 0), False)]
        assert shape == expected, f'Unexpected shape: {shape}'
        tensor_type = model.field(tensor, 1)
        assert tensor_type is None or data[tensor_type] == 0, 'Expected float32'
    buffers = model.vector(model.field(model.root, 4))
    for item in model.vector(model.field(model.root, 6)):
        if model.string(model.field(item, 0)) != 'TFLITE_METADATA':
            continue
        buffer = buffers[model.u32(model.field(item, 1))]
        start = model.indirect(model.field(buffer, 0))
        metadata = FlatBuffer(data[start + 4:start + 4 + model.u32(start)])
        license_text = metadata.string(metadata.field(metadata.root, 5))
        assert 'Apache' in license_text
        subgraph_metadata = metadata.vector(metadata.field(metadata.root, 3))[0]
        input_metadata = metadata.vector(metadata.field(subgraph_metadata, 2))[0]
        process_unit = metadata.vector(metadata.field(input_metadata, 4))[0]
        options = metadata.indirect(metadata.field(process_unit, 1))
        assert metadata.floats(metadata.field(options, 0)) == [127.0]
        assert metadata.floats(metadata.field(options, 1)) == [128.0]
        break
    else:
        raise AssertionError('Model metadata missing')
    with zipfile.ZipFile(MODEL) as archive:
        labels = archive.read('labels_without_background.txt')
    assert labels == (ASSETS / 'labels.txt').read_bytes(), 'Label ordering mismatch'
    assert len(labels.decode().splitlines()) == 1000
    print(f'OK: {len(data):,} bytes; SHA-256 {SHA256}')
    print('float32 [1,224,224,3] → [1,1000]; mean 127 / std 128; 1,000 labels; Apache-2.0')


if __name__ == '__main__':
    verify()
