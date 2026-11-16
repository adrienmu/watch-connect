"""Découpage de flux bruts (battements, accéléromètre) en fenêtres d'analyse."""

from __future__ import annotations

import numpy as np
import numpy.typing as npt
import pandas as pd

from body_battery.config import BodyBatteryConfig

STANDARD_GRAVITY_G = 1.0


def compute_enmo(acc_xyz_g: npt.ArrayLike) -> npt.NDArray[np.float64]:
    """ENMO (Euclidean Norm Minus One) d'échantillons d'accéléromètre, en g.

    Args:
        acc_xyz_g: Tableau (n, 3) d'accélérations en g.

    Returns:
        ``max(‖a‖ − 1 g, 0)`` par échantillon.
    """
    acc = np.asarray(acc_xyz_g, dtype=np.float64).reshape(-1, 3)
    return np.maximum(np.linalg.norm(acc, axis=1) - STANDARD_GRAVITY_G, 0.0)


def segment_windows(beat_times: npt.ArrayLike, rr_ms: npt.ArrayLike,
                    acc_times: npt.ArrayLike, acc_xyz_g: npt.ArrayLike,
                    config: BodyBatteryConfig | None = None,
                    origin: pd.Timestamp | None = None) -> pd.DataFrame:
    """Regroupe des flux bruts en fenêtres fixes de ``window_minutes``.

    Chaque battement est rangé dans la fenêtre contenant son horodatage ; le niveau
    d'activité est l'ENMO moyen des échantillons d'accéléromètre de la fenêtre (0 s'il
    n'y en a aucun). Les fenêtres sans battement sont omises : un capteur retiré se
    traduit donc par une absence de lignes, que le moteur traite comme un trou.

    Args:
        beat_times: Horodatages des battements (datetime64 ou compatibles).
        rr_ms: Intervalle RR de chaque battement, en ms.
        acc_times: Horodatages des échantillons d'accéléromètre.
        acc_xyz_g: Accélérations (n, 3) en g.
        config: Paramètres (taille de fenêtre).
        origin: Origine de la grille ; par défaut le premier battement arrondi à la
            fenêtre inférieure.

    Returns:
        Colonnes ``timestamp`` (fin de fenêtre), ``rr_intervals`` (np.ndarray) et
        ``activity_level``, prêtes pour :meth:`BodyBatteryEngine.process_dataframe`.
    """
    cfg = config or BodyBatteryConfig()
    window = pd.Timedelta(minutes=cfg.window_minutes)
    bt = pd.to_datetime(np.asarray(beat_times)).to_numpy(dtype="datetime64[ns]")
    rr = np.asarray(rr_ms, dtype=np.float64)
    at = pd.to_datetime(np.asarray(acc_times)).to_numpy(dtype="datetime64[ns]")
    enmo = compute_enmo(acc_xyz_g)
    if bt.size != rr.size:
        raise ValueError("beat_times et rr_ms doivent avoir la même longueur")
    if at.size != enmo.size:
        raise ValueError("acc_times et acc_xyz_g doivent avoir le même nombre d'échantillons")
    if bt.size == 0:
        return pd.DataFrame({c: [] for c in ("timestamp", "rr_intervals", "activity_level")})

    order = np.argsort(bt, kind="stable")
    bt, rr = bt[order], rr[order]
    start = origin if origin is not None else pd.Timestamp(bt[0]).floor(window)
    w_ns = window.value
    beat_idx = (bt - np.datetime64(start)).astype(np.int64) // w_ns
    acc_idx = (at - np.datetime64(start)).astype(np.int64) // w_ns

    # Somme et nombre d'échantillons d'ENMO par fenêtre (vectorisé).
    acc_df = pd.DataFrame({"w": acc_idx, "enmo": enmo}).groupby("w")["enmo"].mean()

    windows, bounds = np.unique(beat_idx, return_index=True)
    groups = np.split(rr, bounds[1:])
    activity = acc_df.reindex(windows).fillna(0.0).to_numpy()
    return pd.DataFrame({
        "timestamp": start + (windows + 1) * window,
        "rr_intervals": groups,
        "activity_level": activity,
    })
