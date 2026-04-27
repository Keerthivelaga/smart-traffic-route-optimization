from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Iterable

BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"


def encode_geohash(latitude: float, longitude: float, precision: int = 7) -> str:
    lat_interval = [-90.0, 90.0]
    lon_interval = [-180.0, 180.0]
    geohash = []
    bits = [16, 8, 4, 2, 1]
    bit = 0
    ch = 0
    even = True

    while len(geohash) < precision:
        if even:
            mid = (lon_interval[0] + lon_interval[1]) / 2
            if longitude >= mid:
                ch |= bits[bit]
                lon_interval[0] = mid
            else:
                lon_interval[1] = mid
        else:
            mid = (lat_interval[0] + lat_interval[1]) / 2
            if latitude >= mid:
                ch |= bits[bit]
                lat_interval[0] = mid
            else:
                lat_interval[1] = mid
        even = not even

        if bit < 4:
            bit += 1
        else:
            geohash.append(BASE32[ch])
            bit = 0
            ch = 0

    return "".join(geohash)


def haversine_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_000
    d_lat = math.radians(lat2 - lat1)
    d_lon = math.radians(lon2 - lon1)
    a = (
        math.sin(d_lat / 2) ** 2
        + math.cos(math.radians(lat1))
        * math.cos(math.radians(lat2))
        * math.sin(d_lon / 2) ** 2
    )
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return r * c


@dataclass
class RoadSegment:
    segment_id: str
    geometry: list[tuple[float, float]]

    @property
    def centroid(self) -> tuple[float, float]:
        lat = sum(p[0] for p in self.geometry) / len(self.geometry)
        lon = sum(p[1] for p in self.geometry) / len(self.geometry)
        return lat, lon


class SpatialIndex:
    def __init__(self, geohash_precision: int = 6) -> None:
        self.precision = geohash_precision
        self._bucket: dict[str, list[RoadSegment]] = {}

    def build(self, segments: Iterable[RoadSegment]) -> None:
        self._bucket.clear()
        for segment in segments:
            lat, lon = segment.centroid
            gh = encode_geohash(lat, lon, self.precision)
            self._bucket.setdefault(gh, []).append(segment)

    def query(self, latitude: float, longitude: float) -> list[RoadSegment]:
        key = encode_geohash(latitude, longitude, self.precision)
        prefixes = [key, key[:-1], key[:-2]]
        candidates: list[RoadSegment] = []
        for pfx in prefixes:
            if pfx:
                candidates.extend(self._bucket.get(pfx, []))
        return candidates

    def match_segment(self, latitude: float, longitude: float) -> tuple[str | None, float]:
        candidates = self.query(latitude, longitude)
        best_segment = None
        best_distance = float("inf")
        for segment in candidates:
            c_lat, c_lon = segment.centroid
            dist = haversine_meters(latitude, longitude, c_lat, c_lon)
            if dist < best_distance:
                best_distance = dist
                best_segment = segment.segment_id
        return best_segment, best_distance
