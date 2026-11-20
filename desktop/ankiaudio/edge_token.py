# Python port of android/app/src/main/java/com/eugen/ankiaudio/EdgeToken.kt,
# itself an adaptation of edge-tts drm.py, commit ef51a4d (Sec-MS-GEC v2).
# Copyright edge-tts contributors. SPDX-License-Identifier: LGPL-3.0-only
# Source: https://github.com/rany2/edge-tts/blob/ef51a4d/src/edge_tts/drm.py
# See THIRD_PARTY_NOTICES.md and licenses/edge-tts-LGPL-3.0.txt.
"""Edge's current Read Aloud request token. Arithmetic wraps at 32 bits."""

from __future__ import annotations

import time
from typing import List

TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
WIN_EPOCH = 11_644_473_600
M = 0xFFFFFFFF

INITIAL = [
    0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
    0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
]
INITIAL_STATE = [
    0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
    0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2,
]
SCHEDULE = [
    0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA,
    0xA622EE8C, 0xD1DD9491, 0xC0DD25A0, 0xF9E70AB6,
    0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33,
    0xD258B060, 0x72766564,
]
ROUND_CONSTANTS = [
    0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
    0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
    0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
    0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
    0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
    0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
    0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
    0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
]
COEFFICIENTS = [
    (0xE82E91B1, 0xB32700D0), (0x44DE24C6, 0x88DFF51D), (0x3BEB65BC, 0x29514038),
    (0xC89C5933, 0xB328CBDF), (0x8D1EC211, 0xD405B4D5), (0x314EF5D5, 0x26E55F3C),
    (0xE92491B1, 0x607801C6), (0xF0A5B9D5, 0x82B2C1FB), (0x06064CF3, 0xFA51A3DA),
    (0xDA79FA60, 0x207CC464), (0xBED20560, 0x3C3C74F3), (0x89F9C9F8, 0xD2D14B61),
    (0x35FA38C6, 0xBB04A2C1), (0xCB49E3C6, 0xC5626167),
]


def generate(unix_seconds: int | None = None) -> str:
    if unix_seconds is None:
        unix_seconds = int(time.time())
    seconds = unix_seconds + WIN_EPOCH
    ticks = (seconds - seconds % 300) * 10_000_000
    data = f"{ticks}{TRUSTED_CLIENT_TOKEN}".encode("ascii")
    d = _digest(data)
    return _cs64(data, d).hex().upper() + "-" + d.hex().upper()


def _rotr(x: int, n: int) -> int:
    return ((x >> n) | (x << (32 - n))) & M


def _sigma0(x: int) -> int:
    return _rotr(x, 7) ^ _rotr(x, 18) ^ (x >> 3)


def _sigma1(x: int) -> int:
    return _rotr(x, 17) ^ _rotr(x, 19) ^ (x >> 10)


def _swap(x: int) -> int:
    return _rotr(x, 16)


def _be32(b: bytes, offset: int) -> int:
    return int.from_bytes(b[offset : offset + 4], "big")


def _le32(b: bytes, offset: int) -> int:
    return int.from_bytes(b[offset : offset + 4], "little")


def _expand(w: List[int], start: int = 16) -> None:
    for i in range(start, 64):
        w[i] = (_sigma1(w[i - 2]) + w[i - 7] + _sigma0(w[i - 15]) + w[i - 16]) & M


def _rounds(state: List[int], words: List[int], first: int = 0) -> List[int]:
    a, b, c, d, e, f, g, h = state
    for i in range(first, 64):
        k = 0xC9DC06A7 if (first == 14 and i == 14) else ROUND_CONSTANTS[i]
        t1 = (h + (_rotr(e, 6) ^ _rotr(e, 11) ^ _rotr(e, 25)) + (g ^ (e & (f ^ g))) + k + words[i]) & M
        t2 = ((_rotr(a, 2) ^ _rotr(a, 13) ^ _rotr(a, 22)) + ((a & b) | (c & (a | b)))) & M
        h, g, f, e, d, c, b, a = g, f, e, (d + t1) & M, c, b, a, (t1 + t2) & M
    return [a, b, c, d, e, f, g, h]


def _digest(data: bytes) -> bytes:
    words = [0] * 64
    words[14] = (data[0] << 16) | (data[1] << 8) | data[2]
    words[15] = _be32(data, 3)
    for i in range(16, 30):
        value = SCHEDULE[i - 16]
        if i - 2 >= 14:
            value += (_sigma1(words[i - 2]) ^ 0x000B92C0) if i == 16 else _sigma1(words[i - 2])
        if i - 7 >= 14:
            value += words[i - 7]
        if i - 15 >= 14:
            value += (_sigma0(words[i - 15]) ^ 0x059C0B80) if i == 29 else _sigma0(words[i - 15])
        words[i] = value & M
    words[30] = (
        _sigma1(words[28]) + words[23] + _sigma0(words[15]) + words[14] + 0xC9DC06A7 - ROUND_CONSTANTS[14]
    ) & M
    _expand(words, 31)
    state = [(INITIAL[i] + x) & M for i, x in enumerate(_rounds(INITIAL_STATE, words, 14))]
    length = 57 + len(data)
    zeros = (55 - length) % 64
    padded = data[7:] + b"\x80" + bytes(zeros) + (length * 8).to_bytes(8, "big")
    for offset in range(0, len(padded), 64):
        for i in range(16):
            words[i] = _be32(padded, offset + 4 * i)
        _expand(words)
        working = _rounds(state, words)
        state = [(state[i] + working[i]) & M for i in range(8)]
    return b"".join(s.to_bytes(4, "big") for s in state)


def _cs64(data: bytes, digest: bytes) -> bytes:
    a = _le32(digest, 0) | 1
    b = _le32(digest, 4) | 1
    factors = [(a * 0x82893933) & M, (b * 0x82893933) & M]
    terms = [(row[0] * factors[i % 2]) & M for i, row in enumerate(COEFFICIENTS)]
    offsets = [row[1] for row in COEFFICIENTS]
    inp = b"\x00" + data
    padded = inp + bytes(((len(inp) + 7) // 8) * 8 - len(inp))
    for i, p in enumerate(range(0, len(padded), 4)):
        word = _le32(padded, p)
        term = (word * (a if i % 2 == 0 else b)) & M
        offset = (_swap(word) * (0xEF0569FB if i % 2 == 0 else 0xC31713DB)) & M
        if i == 0:
            term = (term + 0xF2F90B1A * factors[0]) & M
            offset = (offset + 0x0B1A0000) & M
        terms.append(term)
        offsets.append(offset)
    swap_acc = swap_last = state = acc = 0
    for j in range(0, len(terms), 2):
        x, y = terms[j], terms[j + 1]
        q = (_swap((offsets[j] + x) & M) * 0x76F50F34) & M
        swap_acc = (swap_acc + _swap(q) * 0xC3EFEA97 + q) & M
        q = (_swap((offsets[j + 1] + y) & M) * 0x1881CE3C) & M
        swap_last = (_swap(q) * 0x35BD1EC9 + q) & M
        swap_acc = (swap_acc + swap_last) & M
        state = (state * a + x) & M
        state = (_swap(state) * 0xCF98B111) & M
        state = (_swap(state) * 0x87085B9F) & M
        state = (_swap(state) * 0x12CEB96D) & M
        state = (_swap(state) * 0x257E1D83) & M
        acc = (acc + state) & M
        t = (state * b + y) & M
        t = (_swap(t) * 0xA27416F5) & M
        t = (_swap(t) * 0xD38396FF) & M
        state = (_swap((_swap(t) * 0x7C932B89) & M) * 0xBFA49F69) & M
        acc = (acc + state) & M
    return (swap_last ^ state).to_bytes(4, "little") + (swap_acc ^ acc).to_bytes(4, "little")
