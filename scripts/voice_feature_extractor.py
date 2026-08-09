#!/usr/bin/env python3
"""
Phase 13a — VoiceFeatureExtractor (Lusseyran-Pipeline)
========================================================
Extrahiert 25+ paralinguistische Features aus WAV-Dateien.

Features (Lusseyran-Kategorien):
  PITCH     — f0_mean, f0_std, f0_min, f0_max, f0_range, f0_variability
  ENERGY    — rms_energy, rms_std, peak_amplitude, silence_ratio
  RHYTHM    — speech_rate, articulation_rate, pause_count, pause_duration_ratio
  TIMBRE    — spectral_centroid, spectral_bandwidth, spectral_rolloff,
              spectral_flatness, zero_crossing_rate, hnr_approx
  FORMANT   — f1_mean, f2_mean, f3_mean, formant_dispersion
  VOICE     — jitter_approx, shimmer_approx, voiced_fraction

Abhängigkeiten: numpy, scipy, wave (stdlib), subprocess (ffprobe)

Usage:
  python3 voice_feature_extractor.py <input.wav> [--json] [--pretty]
  echo '{"audio_path": "/tmp/recording.wav"}' | python3 voice_feature_extractor.py --stdin
"""

import sys
import json
import wave
import struct
import subprocess
from pathlib import Path
from typing import Optional

import numpy as np
from scipy import signal
from scipy.fft import rfft, rfftfreq
from scipy.signal import lfilter, find_peaks, correlate
from scipy.interpolate import interp1d


class VoiceFeatureExtractor:
    """Extrahiert Lusseyran-Sprecherprofil-Features aus WAV."""

    def __init__(self, wav_path: str):
        self.path = Path(wav_path)
        if not self.path.exists():
            raise FileNotFoundError(f"WAV not found: {wav_path}")
        self.samples, self.sample_rate = self._read_wav()
        self.duration = len(self.samples) / self.sample_rate
        self._precompute()

    def _read_wav(self):
        """Liest WAV via wave-Modul (stdlib, kein soundfile nötig)."""
        with wave.open(str(self.path), 'rb') as wf:
            n_channels = wf.getnchannels()
            sr = wf.getframerate()
            n_frames = wf.getnframes()
            raw = wf.readframes(n_frames)
        fmt = {1: 'b', 2: 'h', 4: 'i'}[wf.getsampwidth()]
        data = struct.unpack(f'<{n_frames * n_channels}{fmt}', raw)
        if n_channels > 1:
            data = np.array(data).reshape(-1, n_channels).mean(axis=1)
        else:
            data = np.array(data, dtype=np.float64)
        return data.astype(np.float64), sr

    def _precompute(self):
        """Pre-compute commonly used values."""
        self._duration_s = len(self.samples) / self.sample_rate
        # Framing for short-time features
        self._frame_len = int(0.025 * self.sample_rate)  # 25ms
        self._hop_len = int(0.010 * self.sample_rate)    # 10ms
        n_frames = 1 + (len(self.samples) - self._frame_len) // self._hop_len
        self._frames = np.array([
            self.samples[i * self._hop_len : i * self._hop_len + self._frame_len]
            for i in range(min(n_frames, 5000))  # max 50s
        ])
        # Windowed frames
        window = np.hanning(self._frame_len)
        self._frames_w = self._frames * window

    # ── PITCH (Grundfrequenz f0) ──────────────────────────

    def extract_pitch(self) -> dict:
        """f0 via Autokorrelation + parabolische Interpolation."""
        f0s = []
        for frame in self._frames_w:
            ac = correlate(frame, frame, mode='full')
            ac = ac[len(ac) // 2:]
            if ac[0] == 0:
                continue
            ac = ac / ac[0]
            # Suche peaks zwischen 50-400 Hz
            min_lag = self.sample_rate // 400
            max_lag = self.sample_rate // 50
            if max_lag >= len(ac):
                max_lag = len(ac) - 1
            if min_lag >= max_lag:
                continue
            search = ac[min_lag:max_lag]
            if len(search) == 0:
                continue
            peak_idx = np.argmax(search) + min_lag
            peak_val = ac[peak_idx]
            if peak_val < 0.3:  # Stimmhaft-Schwelle
                continue
            # Parabolische Interpolation für Sub-Sample-Genauigkeit
            if 0 < peak_idx < len(ac) - 1:
                alpha = ac[peak_idx - 1]
                beta = ac[peak_idx]
                gamma_val = ac[peak_idx + 1]
                denom = alpha - 2 * beta + gamma_val
                if denom != 0:
                    delta = (alpha - gamma_val) / (2 * denom)
                    lag = peak_idx + delta
                else:
                    lag = peak_idx
            else:
                lag = peak_idx
            f0 = self.sample_rate / lag if lag > 0 else 0
            if 50 < f0 < 400:
                f0s.append(f0)

        if not f0s:
            return {"f0_mean": 0, "f0_std": 0, "f0_min": 0, "f0_max": 0,
                    "f0_range": 0, "f0_variability": 0, "voiced_fraction": 0.0}

        arr = np.array(f0s)
        return {
            "f0_mean": round(float(np.mean(arr)), 1),
            "f0_std": round(float(np.std(arr)), 1),
            "f0_min": round(float(np.min(arr)), 1),
            "f0_max": round(float(np.max(arr)), 1),
            "f0_range": round(float(np.max(arr) - np.min(arr)), 1),
            "f0_variability": round(float(np.std(arr) / (np.mean(arr) + 1e-10)), 4),
            "voiced_fraction": round(len(f0s) / len(self._frames), 4)
        }

    # ── ENERGY ────────────────────────────────────────────

    def extract_energy(self) -> dict:
        rms = np.sqrt(np.mean(self._frames_w ** 2, axis=1))
        peak = np.max(np.abs(self.samples))
        silence_threshold = 0.01 * np.mean(rms) if np.mean(rms) > 0 else 0.001
        silence_ratio = np.sum(rms < silence_threshold) / len(rms)
        return {
            "rms_energy": round(float(np.mean(rms)), 6),
            "rms_std": round(float(np.std(rms)), 6),
            "peak_amplitude": round(float(peak), 6),
            "silence_ratio": round(float(silence_ratio), 4),
            "dynamic_range_db": round(20 * np.log10((peak + 1e-10) / (np.mean(rms[rms > 0]) + 1e-10)), 1)
        }

    # ── RHYTHM ────────────────────────────────────────────

    def extract_rhythm(self) -> dict:
        """Speech rate via energy envelope onset detection."""
        rms = np.sqrt(np.mean(self._frames_w ** 2, axis=1))
        # Onset detection: delta energy
        delta = np.diff(np.concatenate([[rms[0]], rms]))
        threshold = 0.15 * np.mean(rms) if np.mean(rms) > 0 else 0.001
        onsets = np.where(delta > threshold)[0]
        # Merge close onsets (< 100ms apart)
        merged = []
        min_gap_samples = int(0.1 * self.sample_rate / self._hop_len)
        for o in onsets:
            if not merged or (o - merged[-1]) > min_gap_samples:
                merged.append(o)
        syllable_estimate = len(merged)
        # Pauses: RMS < silence_threshold for > 200ms
        silence_threshold = 0.01 * np.mean(rms)
        is_silent = rms < silence_threshold
        # Count pause segments
        pause_segments = []
        in_pause = False
        pause_start = 0
        for i, s in enumerate(is_silent):
            if s and not in_pause:
                in_pause = True
                pause_start = i
            elif not s and in_pause:
                in_pause = False
                duration = (i - pause_start) * self._hop_len / self.sample_rate
                if duration > 0.2:  # 200ms minimum pause
                    pause_segments.append(duration)
        if in_pause:
            duration = (len(is_silent) - pause_start) * self._hop_len / self.sample_rate
            if duration > 0.2:
                pause_segments.append(duration)

        if self._duration_s > 0:
            speech_rate = syllable_estimate / self._duration_s
            # Articulation rate: syllables / (duration - pause_time)
            total_pause = sum(pause_segments)
            articulation_rate = syllable_estimate / max(0.1, self._duration_s - total_pause)
        else:
            speech_rate = 0
            articulation_rate = 0

        return {
            "speech_rate_syl_per_sec": round(float(speech_rate), 2),
            "articulation_rate_syl_per_sec": round(float(articulation_rate), 2),
            "syllable_count_estimate": syllable_estimate,
            "pause_count": len(pause_segments),
            "pause_duration_ratio": round(
                sum(pause_segments) / max(0.001, self._duration_s), 4),
            "mean_pause_duration_s": round(
                float(np.mean(pause_segments)) if pause_segments else 0, 3)
        }

    # ── TIMBRE (Spektrale Features) ───────────────────────

    def extract_timbre(self) -> dict:
        """Spectral features via FFT per frame."""
        centroids, bandwidths, rolloffs, flatnesses = [], [], [], []
        zcrs = []
        n_fft = max(512, 2 ** int(np.ceil(np.log2(self._frame_len))))

        for frame in self._frames_w:
            spec = np.abs(rfft(frame, n=n_fft))
            freqs = rfftfreq(n_fft, 1 / self.sample_rate)
            power = spec ** 2
            total_power = np.sum(power)

            if total_power > 0:
                # Centroid
                centroid = np.sum(freqs * power) / total_power
                centroids.append(centroid)
                # Bandwidth
                bandwidth = np.sqrt(np.sum(((freqs - centroid) ** 2) * power) / total_power)
                bandwidths.append(bandwidth)
                # Rolloff (85% energy)
                cumsum = np.cumsum(power)
                rolloff_idx = np.searchsorted(cumsum, 0.85 * cumsum[-1])
                rolloffs.append(freqs[min(rolloff_idx, len(freqs) - 1)])
                # Flatness
                geometric = np.exp(np.mean(np.log(spec + 1e-10)))
                arithmetic = np.mean(spec)
                flatnesses.append(geometric / (arithmetic + 1e-10))
            else:
                centroids.append(0)
                bandwidths.append(0)
                rolloffs.append(0)
                flatnesses.append(1.0)

            # Zero-crossing rate
            zcr = np.sum(np.abs(np.diff(np.sign(frame)))) / (2 * len(frame))
            zcrs.append(zcr)

        # HNR approximation: autocorrelation peak ratio
        hnrs = []
        for frame in self._frames_w[:500]:  # sample für performance
            ac = correlate(frame, frame, mode='full')
            ac = ac[len(ac) // 2:]
            if ac[0] == 0:
                hnrs.append(0)
                continue
            ac_norm = ac / ac[0]
            if len(ac_norm) > 2:
                peak = np.max(ac_norm[1:])
                hnrs.append(-10 * np.log10(1 - peak + 1e-10) if peak < 1 else 20)
            else:
                hnrs.append(0)

        def _safe(arr, fn, d=2):
            a = np.array(arr)
            return round(float(fn(a)), d) if len(a) > 0 else 0

        return {
            "spectral_centroid_hz": _safe(centroids, np.mean, 1),
            "spectral_centroid_std": _safe(centroids, np.std, 1),
            "spectral_bandwidth_hz": _safe(bandwidths, np.mean, 1),
            "spectral_rolloff_hz": _safe(rolloffs, np.mean, 1),
            "spectral_flatness": _safe(flatnesses, np.mean, 4),
            "zero_crossing_rate": _safe(zcrs, np.mean, 6),
            "hnr_approx_db": _safe(hnrs, np.mean, 1)
        }

    # ── FORMANT (F1–F3 via LPC) ───────────────────────────

    def extract_formants(self) -> dict:
        """Formant-Frequenzen via LPC + Root-Finding (ersetzt parselmouth).
           Nur auf stimmhaften Frames."""
        lpc_order = 2 + self.sample_rate // 1000  # ~14 bei 16kHz

        formant_bundles = []
        for frame in self._frames_w[::3]:  # every 3rd frame for speed
            if len(frame) < lpc_order + 1:
                continue
            # Pre-emphasis
            preemph = frame.copy()
            preemph[1:] = preemph[1:] - 0.97 * preemph[:-1]
            # LPC via autocorrelation
            ac = correlate(preemph, preemph, mode='full')
            ac = ac[len(ac) // 2:len(ac) // 2 + lpc_order + 1]
            try:
                R = np.array([ac[i] for i in range(lpc_order)])
                r0 = ac[0]
                if r0 < 1e-10:
                    continue
                # Levinson-Durbin
                a, e = _levinson_durbin(R, lpc_order)
                # Find roots
                roots = np.roots(np.concatenate([[1], a]))
                # Nur roots innerhalb Einheitskreis mit positivem Imaginärteil
                formants = []
                for r in roots:
                    if 0 < np.abs(r) < 1 and np.imag(r) > 0:
                        freq = np.abs(np.angle(r)) * self.sample_rate / (2 * np.pi)
                        bw = -np.log(np.abs(r)) * self.sample_rate / np.pi
                        if 50 < freq < 5000 and bw < 500:
                            formants.append(freq)
                formants.sort()
                if len(formants) >= 3:
                    formant_bundles.append(formants[:3])
                elif len(formants) > 0:
                    formant_bundles.append(formants)
            except Exception:
                continue

        if not formant_bundles:
            return {"f1_mean": 0, "f2_mean": 0, "f3_mean": 0, "formant_dispersion": 0}

        f1s = [f[0] for f in formant_bundles if len(f) >= 1]
        f2s = [f[1] for f in formant_bundles if len(f) >= 2]
        f3s = [f[2] for f in formant_bundles if len(f) >= 3]

        def _m(arr):
            return round(float(np.mean(arr)), 1) if arr else 0

        return {
            "f1_mean_hz": _m(f1s),
            "f2_mean_hz": _m(f2s),
            "f3_mean_hz": _m(f3s),
            "formant_dispersion": round(
                float(np.std(f2s) / (np.mean(f2s) + 1e-10)) if f2s else 0, 4)
        }

    # ── VOICE QUALITY ─────────────────────────────────────

    def extract_voice_quality(self) -> dict:
        """Jitter + Shimmer Approximation (ohne parselmouth)."""
        # Jitter: Period-to-period f0 variation
        pitch = self.extract_pitch()
        f0s = []  # Re-extract for sample-level
        frame_step = self._hop_len
        for i in range(0, len(self.samples) - self._frame_len, frame_step):
            frame = self.samples[i:i + self._frame_len]
            ac = correlate(frame, frame, mode='full')
            ac = ac[len(ac) // 2:]
            if ac[0] == 0:
                continue
            ac_norm = ac / ac[0]
            min_lag = self.sample_rate // 400
            max_lag = min(self.sample_rate // 50, len(ac) - 1)
            if min_lag >= max_lag:
                continue
            peak_idx = np.argmax(ac_norm[min_lag:max_lag]) + min_lag
            if ac_norm[peak_idx] > 0.3:
                f0 = self.sample_rate / peak_idx
                if 50 < f0 < 400:
                    f0s.append((i, f0))

        # Jitter: mittlere absolute Periodendifferenz
        jitter = 0
        if len(f0s) >= 2:
            diffs = [abs(f0s[i][1] - f0s[i - 1][1]) for i in range(1, len(f0s))]
            mean_f0 = np.mean([f[1] for f in f0s])
            if mean_f0 > 0:
                jitter = float(np.mean(diffs) / mean_f0)

        # Shimmer: Amplitude variation
        shimmer = 0
        rms_frames = np.sqrt(np.mean(self._frames_w ** 2, axis=1))
        if len(rms_frames) >= 2:
            amp_diffs = [abs(rms_frames[i] - rms_frames[i - 1])
                         for i in range(1, len(rms_frames))]
            mean_amp = np.mean(rms_frames)
            if mean_amp > 0:
                shimmer = float(np.mean(amp_diffs) / mean_amp)

        return {
            "jitter_approx": round(jitter, 6),
            "shimmer_approx": round(shimmer, 6),
            "voiced_fraction": round(pitch.get("voiced_fraction", 0), 4)
        }

    # ── FULL EXTRACTION ───────────────────────────────────

    def extract_all(self) -> dict:
        """Extrahiert alle 25+ Features und gibt sie als Dict zurück."""
        meta = {}
        try:
            probe = subprocess.run(
                ['ffprobe', '-v', 'quiet', '-print_format', 'json',
                 '-show_format', '-show_streams', str(self.path)],
                capture_output=True, text=True, timeout=5)
            if probe.returncode == 0:
                meta = json.loads(probe.stdout)
        except Exception:
            pass

        features = {
            "meta": {
                "file": str(self.path),
                "duration_s": round(self._duration_s, 2),
                "sample_rate": self.sample_rate,
                "channels": meta.get("streams", [{}])[0].get("channels", 1) if meta else 1,
                "codec": meta.get("streams", [{}])[0].get("codec_name", "unknown") if meta else "unknown"
            },
            "pitch": self.extract_pitch(),
            "energy": self.extract_energy(),
            "rhythm": self.extract_rhythm(),
            "timbre": self.extract_timbre(),
            "formant": self.extract_formants(),
            "voice_quality": self.extract_voice_quality(),
        }

        # Zusammenfassung für Lusseyran-Evaluator
        features["lusseyran_profile"] = {
            "stimmhoehe": _classify_pitch(features["pitch"]["f0_mean"]),
            "energie": _classify_energy(features["energy"]["dynamic_range_db"]),
            "sprechgeschwindigkeit": _classify_rate(features["rhythm"]["speech_rate_syl_per_sec"]),
            "stimmhaftigkeit": _classify_voiced(features["pitch"]["voiced_fraction"]),
            "variabilitaet": _classify_variability(features["pitch"]["f0_variability"]),
            "pausen": _classify_pauses(features["rhythm"]["pause_count"],
                                        features["rhythm"]["mean_pause_duration_s"])
        }

        return features


# ── Lusseyran Klassifikatoren ──────────────────────────

def _classify_pitch(f0_mean):
    if f0_mean < 100: return "sehr_tief"
    if f0_mean < 140: return "tief"
    if f0_mean < 190: return "mittel"
    if f0_mean < 250: return "hoch"
    return "sehr_hoch"

def _classify_energy(dr):
    if dr < 20: return "monoton"
    if dr < 35: return "moderat"
    if dr < 50: return "dynamisch"
    return "sehr_dynamisch"

def _classify_rate(sr):
    if sr < 3: return "langsam"
    if sr < 5: return "normal"
    if sr < 7: return "schnell"
    return "sehr_schnell"

def _classify_voiced(vf):
    if vf < 0.3: return "behaucht"
    if vf < 0.6: return "moderat"
    if vf < 0.8: return "voll"
    return "sehr_voll"

def _classify_variability(fv):
    if fv < 0.02: return "monoton"
    if fv < 0.05: return "moderat"
    if fv < 0.10: return "variabel"
    return "sehr_variabel"

def _classify_pauses(count, mean_dur):
    if count < 3: return "fließend"
    if count < 10: return "strukturiert"
    return "viele_pausen"


def _levinson_durbin(r, order):
    """Levinson-Durbin Rekursion für LPC-Koeffizienten."""
    a = np.zeros(order)
    e = r[0] if r[0] > 1e-10 else 1e-10
    for i in range(order):
        k_sum = 0
        for j in range(i):
            k_sum += a[j] * r[i - j]
        k = -(r[i + 1] + k_sum) / e
        a[i] = k
        for j in range(i):
            a[j] = a[j] + k * a[i - 1 - j] if (i - 1 - j) < len(a) else a[j]
        e *= (1 - k * k)
    return a, e


# ── CLI ─────────────────────────────────────────────────

def main():
    if '--stdin' in sys.argv:
        data = json.load(sys.stdin)
        wav_path = data.get('audio_path', data.get('file', data.get('path')))
        if not wav_path:
            print(json.dumps({"error": "no audio_path in stdin JSON"}))
            sys.exit(1)
    elif len(sys.argv) >= 2 and sys.argv[1] in ('--help', '-h'):
        print(__doc__)
        return
    elif len(sys.argv) >= 2:
        wav_path = sys.argv[1]
    else:
        print(json.dumps({"error": "usage: voice_feature_extractor.py <wav> [--json] [--pretty]"}))
        sys.exit(1)

    try:
        extractor = VoiceFeatureExtractor(wav_path)
        features = extractor.extract_all()
        indent = 2 if '--pretty' in sys.argv else None
        print(json.dumps(features, indent=indent, ensure_ascii=False))
    except Exception as e:
        print(json.dumps({"error": str(e), "file": wav_path}))
        sys.exit(1)


if __name__ == '__main__':
    main()
