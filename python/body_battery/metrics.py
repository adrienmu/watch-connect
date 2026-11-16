"""Métriques VFC par fenêtre, ligne de base personnelle et score de stress."""

from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass
from typing import TypedDict

import numpy as np
import numpy.typing as npt
import pandas as pd
from scipy.special import expit

from body_battery.config import BodyBatteryConfig

#: Plancher du RMSSD avant le logarithme (un RR parfaitement constant est un artefact).
RMSSD_FLOOR_MS = 1.0


class WindowMetrics(TypedDict):
    """Métriques physiologiques d'une fenêtre RR nettoyée."""

    rmssd: float
    ln_rmssd: float
    hr_mean: float


def compute_window_metrics(rr_clean: npt.ArrayLike) -> WindowMetrics:
    """Calcule RMSSD, ln(RMSSD) et FC moyenne d'une fenêtre nettoyée.

    Les NaN marquent des battements retirés (voir ``CleaningResult.rr_with_gaps``) : les
    différences successives qui les touchent sont ignorées, la suite n'est pas recollée.

    Args:
        rr_clean: Intervalles RR nettoyés, en millisecondes.

    Returns:
        ``rmssd`` (ms), ``ln_rmssd`` (ln de ms, RMSSD plancher à 1 ms) et
        ``hr_mean`` (bpm, 60000 / RR moyen).

    Raises:
        ValueError: S'il n'y a aucune paire de battements consécutifs valides.
    """
    rr = np.asarray(rr_clean, dtype=np.float64).ravel()
    diffs = np.diff(rr)
    diffs = diffs[np.isfinite(diffs)]
    if diffs.size == 0:
        raise ValueError("au moins 2 intervalles RR consécutifs sont nécessaires")
    rmssd = float(np.sqrt(np.mean(diffs**2)))
    return WindowMetrics(
        rmssd=rmssd,
        ln_rmssd=math.log(max(rmssd, RMSSD_FLOOR_MS)),
        hr_mean=float(60_000.0 / np.nanmean(rr)),
    )


def compute_stress_score(ln_rmssd: float, baseline_mean: float, baseline_std: float,
                         slope: float = 1.5, offset: float = 0.0) -> float:
    """Projette le Z-score de VFC sur un stress [0, 100] par sigmoïde inversée.

    ``S = 100 / (1 + exp(slope · (Z + offset)))`` avec ``Z = (ln_rmssd − μ) / σ`` : une VFC
    au-dessus de la ligne de base donne un stress faible, en dessous un stress élevé.
    Sans décalage, Z = 0 donne 50 ; le moteur utilise ``config.stress_sigmoid_offset``.

    Args:
        ln_rmssd: ln(RMSSD) de la fenêtre.
        baseline_mean: Moyenne μ de la ligne de base de ln(RMSSD).
        baseline_std: Écart-type σ de la ligne de base (> 0).
        slope: Pente k de la sigmoïde.
        offset: Décalage z₀ ajouté au Z-score.

    Returns:
        Le score de stress dans [0, 100].

    Raises:
        ValueError: Si ``baseline_std`` n'est pas strictement positif.
    """
    if not baseline_std > 0:
        raise ValueError("baseline_std doit être > 0")
    z = (ln_rmssd - baseline_mean) / baseline_std
    # expit(-k·z) = 1 / (1 + e^(k·z)), numériquement stable pour |z| grand.
    return float(100.0 * expit(-slope * (z + offset)))


def ans_quality(ans_charge: float | None, config: BodyBatteryConfig | None = None) -> float | None:
    """Projette l'ANS charge du Nightly Recharge Polar (−10 à +10) sur une qualité de nuit [0, 1].

    0 (niveau habituel) donne ``ans_usual_quality`` (0,6), +5 donne 1, −7,5 donne 0.

    Args:
        ans_charge: ANS charge de la nuit, ou None.
        config: Paramètres (qualité habituelle, pente).

    Returns:
        La qualité, ou None si l'ANS charge est absente ou hors de [−10, 10].
    """
    if ans_charge is None or not math.isfinite(ans_charge) or not -10.0 <= ans_charge <= 10.0:
        return None
    cfg = config or BodyBatteryConfig()
    return float(np.clip(cfg.ans_usual_quality + cfg.ans_quality_per_point * ans_charge, 0.0, 1.0))


@dataclass(frozen=True, slots=True)
class Baseline:
    """Instantané de la ligne de base.

    Attributes:
        mean: μ de ln(RMSSD).
        std: σ de ln(RMSSD).
        weight: Poids des données personnelles face au prior (0 = prior pur, 1 = données).
        n_samples: Nombre d'observations dans la fenêtre glissante.
    """

    mean: float
    std: float
    weight: float
    n_samples: int


class BaselineTracker:
    """Moyenne et écart-type glissants de ln(RMSSD) sur ``baseline_days`` jours.

    Les sommes Σx et Σx² sont maintenues incrémentalement (O(1) amorti par mise à
    jour), sur des valeurs centrées sur le prior pour limiter l'annulation
    numérique. Pendant le démarrage à froid, les statistiques empiriques sont
    mélangées au prior (μ₀, σ₀) avec un poids ``w`` qui croît linéairement de 0 à 1
    sur ``baseline_cold_start_hours`` d'historique :

    ``μ = (1 − w)·μ₀ + w·μ̂`` et ``σ² = (1 − w)·σ₀² + w·σ̂²``.

    Args:
        config: Paramètres (profondeur, prior, plancher de σ).
    """

    def __init__(self, config: BodyBatteryConfig | None = None) -> None:
        self._config = config or BodyBatteryConfig()
        self._horizon = pd.Timedelta(days=self._config.baseline_days)
        self._shift = self._config.baseline_prior_mean
        self._samples: deque[tuple[pd.Timestamp, float]] = deque()
        self._sum = 0.0
        self._sum_sq = 0.0
        self._first_timestamp: pd.Timestamp | None = None
        self._last_timestamp: pd.Timestamp | None = None

    def __len__(self) -> int:
        return len(self._samples)

    def update(self, timestamp: pd.Timestamp, ln_rmssd: float) -> None:
        """Ajoute une observation et évince celles plus vieilles que ``baseline_days``.

        Args:
            timestamp: Horodatage de l'observation (croissant).
            ln_rmssd: Valeur observée ; ignorée si non finie.

        Raises:
            ValueError: Si ``timestamp`` est antérieur à la dernière observation.
        """
        if not math.isfinite(ln_rmssd):
            return
        if self._last_timestamp is not None and timestamp < self._last_timestamp:
            raise ValueError("les observations doivent être ajoutées dans l'ordre")
        if self._first_timestamp is None:
            self._first_timestamp = timestamp
        self._last_timestamp = timestamp
        x = ln_rmssd - self._shift
        self._samples.append((timestamp, x))
        self._sum += x
        self._sum_sq += x * x
        cutoff = timestamp - self._horizon
        while self._samples and self._samples[0][0] < cutoff:
            _, old = self._samples.popleft()
            self._sum -= old
            self._sum_sq -= old * old

    def cold_start_weight(self, now: pd.Timestamp | None = None) -> float:
        """Poids ``w`` des données personnelles, dans [0, 1].

        Args:
            now: Instant d'évaluation ; par défaut la dernière observation.

        Returns:
            0 sans historique, 1 après ``baseline_cold_start_hours`` d'historique.
        """
        if self._first_timestamp is None or not self._samples:
            return 0.0
        now = now if now is not None else self._last_timestamp
        assert now is not None
        hours = (now - self._first_timestamp).total_seconds() / 3600.0
        w = hours / self._config.baseline_cold_start_hours
        # Une seule observation ne dit rien de la dispersion.
        return float(np.clip(w, 0.0, 1.0)) if len(self._samples) >= 2 else 0.0

    def get(self, now: pd.Timestamp | None = None) -> Baseline:
        """Renvoie la ligne de base courante, prior inclus.

        Args:
            now: Instant d'évaluation (pour le poids de démarrage à froid).

        Returns:
            L'instantané (μ, σ, w, n).
        """
        cfg = self._config
        n = len(self._samples)
        w = self.cold_start_weight(now)
        if n == 0 or w == 0.0:
            return Baseline(cfg.baseline_prior_mean, cfg.baseline_prior_std, 0.0, n)
        mean_c = self._sum / n
        var = max(self._sum_sq / n - mean_c * mean_c, 0.0) * n / max(n - 1, 1)
        mean = self._shift + mean_c
        blended_mean = (1.0 - w) * cfg.baseline_prior_mean + w * mean
        blended_var = (1.0 - w) * cfg.baseline_prior_std**2 + w * var
        std = max(math.sqrt(blended_var), cfg.baseline_min_std)
        return Baseline(blended_mean, std, w, n)
