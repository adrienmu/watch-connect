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
        rr_with_gaps: Même série à la longueur d'origine, avec NaN à la place des battements
            retirés : un battement retiré coupe la suite, aucune différence successive n'est
            calculée par-dessus (utilisé par :func:`compute_window_metrics`).
        n_total: Nombre de battements bruts reçus.
        n_out_of_range: Battements retirés car hors de [rr_min_ms, rr_max_ms] ou non finis.
        n_ectopic: Battements ectopiques remplacés par interpolation.
        valid_ratio: Part des battements bruts ni aberrants ni ectopiques.
        is_valid: Vrai si la fenêtre respecte les critères de qualité.
    """

    rr_clean: FloatArray
    rr_with_gaps: FloatArray
    n_total: int
    n_out_of_range: int
    n_ectopic: int
    valid_ratio: float
    is_valid: bool


def assess_rr_intervals(rr_ms: npt.ArrayLike, config: BodyBatteryConfig,
                        error_estimates_ms: npt.ArrayLike | None = None) -> CleaningResult:
    """Nettoie une fenêtre RR et renvoie un diagnostic de qualité, sans lever d'exception.

    Étape A : retire les valeurs non finies ou hors de [rr_min_ms, rr_max_ms], ainsi que
    celles dont l'estimation d'erreur du capteur dépasse ``max_error_estimate_ms``.
    Étape B : compare chaque battement restant à la médiane glissante sur
    ``ectopic_median_window`` battements ; un écart relatif supérieur à
    ``ectopic_threshold_pct`` le marque ectopique, et il est remplacé par
    interpolation linéaire entre ses voisins valides.

    Args:
        rr_ms: Intervalles RR bruts, en millisecondes.
        config: Paramètres du nettoyage.
        error_estimates_ms: Estimation d'erreur de chaque battement (PPI Polar), ou None.

    Returns:
        Le résultat détaillé ; ``is_valid`` vaut False si moins de ``min_beats``
        battements bruts ou moins de ``min_valid_ratio`` de battements valides.
    """
    rr = np.asarray(rr_ms, dtype=np.float64).ravel()
    n_total = int(rr.size)
    empty = np.empty(0, dtype=np.float64)
    if n_total == 0:
        return CleaningResult(empty, empty, 0, 0, 0, 0.0, False)

    # Étape A : bornes physiologiques et estimation d'erreur du capteur.
    in_range = np.isfinite(rr) & (rr >= config.rr_min_ms) & (rr <= config.rr_max_ms)
    if error_estimates_ms is not None:
        err = np.asarray(error_estimates_ms, dtype=np.float64).ravel()
        if err.size != n_total:
            raise ValueError("error_estimates_ms doit avoir la longueur de rr_ms")
        in_range &= ~(err > config.max_error_estimate_ms)  # NaN = pas d'estimation : conservé
    kept = rr[in_range]
    n_out = n_total - int(kept.size)
    gaps = np.full(n_total, np.nan)
    if kept.size < 3:
        gaps[in_range] = kept
        return CleaningResult(kept, gaps, n_total, n_out, 0, 0.0, False)

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

    gaps[in_range] = kept
    is_valid = n_total >= config.min_beats and valid_ratio >= config.min_valid_ratio
    return CleaningResult(kept, gaps, n_total, n_out, n_ectopic, valid_ratio, is_valid)


def clean_rr_intervals(rr_ms: npt.ArrayLike, config: BodyBatteryConfig,
                       error_estimates_ms: npt.ArrayLike | None = None) -> FloatArray:
    """Nettoie une fenêtre RR (voir :func:`assess_rr_intervals`).

    Args:
        rr_ms: Intervalles RR bruts, en millisecondes.
        config: Paramètres du nettoyage.
        error_estimates_ms: Estimation d'erreur de chaque battement, ou None.

    Returns:
        Les intervalles nettoyés, en millisecondes.

    Raises:
        InsufficientDataError: Si ``len(rr) < min_beats`` ou si moins de
            ``min_valid_ratio`` des battements sont valides.
    """
    result = assess_rr_intervals(rr_ms, config, error_estimates_ms)
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
