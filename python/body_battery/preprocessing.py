"""Nettoyage des séries d'intervalles RR (artefacts et battements ectopiques)."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import numpy.typing as npt
from scipy.ndimage import median_filter

from body_battery.config import BodyBatteryConfig

FloatArray = npt.NDArray[np.float64]


class InsufficientDataError(ValueError):
    """La fenêtre ne contient pas assez de battements exploitables."""


@dataclass(frozen=True, slots=True)
class CleaningResult:
    """Résultat détaillé du nettoyage d'une fenêtre RR.

    Attributes:
        rr_clean: Intervalles RR conservés (hors bornes retirés, ectopiques interpolés).
        n_total: Nombre de battements bruts reçus.
        n_out_of_range: Battements retirés car hors de [rr_min_ms, rr_max_ms] ou non finis.
        n_ectopic: Battements ectopiques remplacés par interpolation.
        valid_ratio: Part des battements bruts ni aberrants ni ectopiques.
        is_valid: Vrai si la fenêtre respecte les critères de qualité.
    """

    rr_clean: FloatArray
    n_total: int
    n_out_of_range: int
    n_ectopic: int
    valid_ratio: float
    is_valid: bool


def assess_rr_intervals(rr_ms: npt.ArrayLike, config: BodyBatteryConfig) -> CleaningResult:
    """Nettoie une fenêtre RR et renvoie un diagnostic de qualité, sans lever d'exception.

    Étape A : retire les valeurs non finies ou hors de [rr_min_ms, rr_max_ms].
    Étape B : compare chaque battement restant à la médiane glissante sur
    ``ectopic_median_window`` battements ; un écart relatif supérieur à
    ``ectopic_threshold_pct`` le marque ectopique, et il est remplacé par
    interpolation linéaire entre ses voisins valides.

    Args:
        rr_ms: Intervalles RR bruts, en millisecondes.
        config: Paramètres du nettoyage.

    Returns:
        Le résultat détaillé ; ``is_valid`` vaut False si moins de ``min_beats``
        battements bruts ou moins de ``min_valid_ratio`` de battements valides.
    """
    rr = np.asarray(rr_ms, dtype=np.float64).ravel()
    n_total = int(rr.size)
    empty = np.empty(0, dtype=np.float64)
    if n_total == 0:
        return CleaningResult(empty, 0, 0, 0, 0.0, False)

    # Étape A : bornes physiologiques.
    in_range = np.isfinite(rr) & (rr >= config.rr_min_ms) & (rr <= config.rr_max_ms)
    kept = rr[in_range]
    n_out = n_total - int(kept.size)
    if kept.size < 3:
        return CleaningResult(kept, n_total, n_out, 0, 0.0, False)

    # Étape B : battements ectopiques vs médiane locale (robuste à un pic isolé
    # et au couple prématuré + compensateur).
    local_median = median_filter(kept, size=config.ectopic_median_window, mode="nearest")
    ectopic = np.abs(kept - local_median) > config.ectopic_threshold_pct * local_median
    n_ectopic = int(ectopic.sum())
    n_valid = int(kept.size) - n_ectopic
    valid_ratio = n_valid / n_total

    if n_valid >= 2 and n_ectopic > 0:
        idx = np.arange(kept.size)
        kept = kept.copy()
        kept[ectopic] = np.interp(idx[ectopic], idx[~ectopic], kept[~ectopic])

    is_valid = n_total >= config.min_beats and valid_ratio >= config.min_valid_ratio
    return CleaningResult(kept, n_total, n_out, n_ectopic, valid_ratio, is_valid)


def clean_rr_intervals(rr_ms: npt.ArrayLike, config: BodyBatteryConfig) -> FloatArray:
    """Nettoie une fenêtre RR (voir :func:`assess_rr_intervals`).

    Args:
        rr_ms: Intervalles RR bruts, en millisecondes.
        config: Paramètres du nettoyage.

    Returns:
        Les intervalles nettoyés, en millisecondes.

    Raises:
        InsufficientDataError: Si ``len(rr) < min_beats`` ou si moins de
            ``min_valid_ratio`` des battements sont valides.
    """
    result = assess_rr_intervals(rr_ms, config)
    if result.n_total < config.min_beats:
        raise InsufficientDataError(
            f"{result.n_total} battements reçus, {config.min_beats} requis"
        )
    if not result.is_valid:
        raise InsufficientDataError(
            f"seulement {result.valid_ratio:.0%} de battements valides "
            f"(minimum {config.min_valid_ratio:.0%})"
        )
    return result.rr_clean
