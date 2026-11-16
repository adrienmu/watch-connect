"""Générateurs de signaux synthétiques (tests et démonstrations)."""

from __future__ import annotations

import numpy as np
import numpy.typing as npt

FloatArray = npt.NDArray[np.float64]

#: Coefficient AR(1) des fluctuations RR ; avec φ = 0,5, RMSSD ≈ σ.
_AR_PHI = 0.5


def generate_rr(hr_bpm: float, rmssd_ms: float, duration_s: float,
                rng: np.random.Generator) -> FloatArray:
    """Génère une suite RR de FC moyenne et de RMSSD approximativement donnés.

    Les fluctuations suivent un AR(1) de coefficient 0,5 et d'écart-type σ ; la
    variance des différences successives vaut alors 2σ²(1 − φ) = σ², d'où RMSSD ≈ σ.

    Args:
        hr_bpm: FC moyenne visée.
        rmssd_ms: RMSSD visé, en ms.
        duration_s: Durée couverte par la suite, en secondes.
        rng: Générateur aléatoire.

    Returns:
        Les intervalles RR en ms, dont la somme couvre ``duration_s``.
    """
    mean_rr = 60_000.0 / hr_bpm
    n = int(duration_s * 1000.0 / mean_rr) + 2
    innov = rng.normal(0.0, rmssd_ms * np.sqrt(1.0 - _AR_PHI**2), n)
    x = np.empty(n)
    x[0] = rng.normal(0.0, rmssd_ms)
    for i in range(1, n):
        x[i] = _AR_PHI * x[i - 1] + innov[i]
    rr = mean_rr + x
    cut = int(np.searchsorted(np.cumsum(rr), duration_s * 1000.0)) + 1
    return rr[:cut]
