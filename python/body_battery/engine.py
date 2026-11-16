"""Moteur d'intégration temporelle du score Body Battery."""

from __future__ import annotations

import math
from dataclasses import asdict, dataclass
from datetime import datetime
from enum import Enum
from typing import Final

import numpy as np
import numpy.typing as npt
import pandas as pd

from body_battery.config import BodyBatteryConfig
from body_battery.metrics import (BaselineTracker, ans_quality, compute_stress_score,
                                  compute_window_metrics)
from body_battery.preprocessing import assess_rr_intervals

TimestampLike = pd.Timestamp | datetime | np.datetime64 | str | int | float

REQUIRED_COLUMNS: Final = ("timestamp", "rr_intervals", "activity_level")
#: Colonnes facultatives de :meth:`BodyBatteryEngine.process_dataframe`.
OPTIONAL_COLUMNS: Final = ("rr_errors", "sleeping", "ans_charge")


class WindowState(str, Enum):
    """Régime appliqué à une fenêtre."""

    RECHARGE = "recharge"  # repos, stress sous le seuil
    STRESS_DRAIN = "stress_drain"  # repos, stress au-dessus du seuil
    ACTIVITY = "activity"  # effort physique
    SLEEP = "sleep"  # sommeil : recharge modulée par l'ANS charge
    INVALID = "invalid"  # données insuffisantes : score inchangé


@dataclass(frozen=True, slots=True)
class UserProfile:
    """Profil cardiaque de l'utilisateur.

    Attributes:
        hr_rest: FC de repos, en bpm.
        hr_max: FC maximale, en bpm.
    """

    hr_rest: float = 60.0
    hr_max: float = 190.0

    def __post_init__(self) -> None:
        """Valide le profil.

        Raises:
            ValueError: Si ``hr_max <= hr_rest`` ou si une FC est négative.
        """
        if not 0 < self.hr_rest < self.hr_max:
            raise ValueError("il faut 0 < hr_rest < hr_max")


@dataclass(frozen=True, slots=True)
class WindowResult:
    """Résultat d'un pas d'intégration.

    Attributes:
        timestamp: Fin de la fenêtre.
        score: Score Body Battery après la fenêtre.
        delta_rate: Taux appliqué, en points/heure (0 si invalide).
        dt_hours: Durée intégrée avec ``delta_rate`` (hors trou de données).
        gap_hours: Durée traitée comme trou de données avant cette fenêtre.
        state: Régime appliqué.
        valid: Vrai si la fenêtre était exploitable.
        stress: Score de stress [0, 100] (NaN si invalide).
        rmssd: RMSSD en ms (NaN si invalide).
        ln_rmssd: ln(RMSSD) (NaN si invalide).
        hr_mean: FC moyenne en bpm (NaN si invalide).
        hr_reserve: Fraction de réserve cardiaque [0, 1] (NaN si invalide).
        activity_level: Niveau d'activité reçu.
        sleeping: Vrai si la fenêtre était marquée comme du sommeil.
        ans_charge: ANS charge Polar de la nuit appliquée (NaN sinon).
        baseline_mean: μ de la ligne de base utilisée.
        baseline_std: σ de la ligne de base utilisée.
        baseline_weight: Poids des données personnelles dans la ligne de base.
        n_beats: Battements bruts reçus.
        valid_ratio: Part de battements valides.
    """

    timestamp: pd.Timestamp
    score: float
    delta_rate: float
    dt_hours: float
    gap_hours: float
    state: WindowState
    valid: bool
    stress: float
    rmssd: float
    ln_rmssd: float
    hr_mean: float
    hr_reserve: float
    activity_level: float
    sleeping: bool
    ans_charge: float
    baseline_mean: float
    baseline_std: float
    baseline_weight: float
    n_beats: int
    valid_ratio: float


def to_timestamp(value: TimestampLike) -> pd.Timestamp:
    """Convertit un horodatage ; les nombres sont lus comme des secondes epoch.

    Args:
        value: Horodatage (datetime, chaîne ISO, numpy, ou secondes epoch).

    Returns:
        Le ``pd.Timestamp`` correspondant.
    """
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return pd.Timestamp(value, unit="s")
    return pd.Timestamp(value)


def hr_reserve_fraction(hr: float, profile: UserProfile) -> float:
    """Fraction de réserve cardiaque (Karvonen), bornée à [0, 1]."""
    return float(np.clip((hr - profile.hr_rest) / (profile.hr_max - profile.hr_rest), 0.0, 1.0))


def compute_rate(stress: float, hr: float, activity_level: float, profile: UserProfile,
                 config: BodyBatteryConfig, sleeping: bool = False,
                 ans_charge: float | None = None) -> tuple[float, WindowState]:
    """Taux horaire ΔBB d'une fenêtre valide.

    * Effort (``activity_level > activity_threshold``) : ``−γ · r²`` où ``r`` est la
      fraction de réserve cardiaque.
    * Sommeil : ``+α · max(seuil − stress, 0) · f`` — la nuit ne vide jamais la batterie.
      ``f`` vaut la qualité tirée de l'ANS charge Polar divisée par celle d'une nuit
      habituelle (de 0 à ≈ 1,67), ou 1 sans ANS charge : comme le Nightly Recharge, la
      récupération du système nerveux module ce que la nuit regagne.
    * Repos, stress < seuil : ``+α · (seuil − stress)``.
    * Repos, sinon : ``−β · (stress − seuil)``.

    Args:
        stress: Score de stress [0, 100].
        hr: FC moyenne, en bpm.
        activity_level: Niveau d'activité de la fenêtre.
        profile: Profil cardiaque.
        config: Hyperparamètres.
        sleeping: Vrai pendant le sommeil.
        ans_charge: ANS charge Polar de la nuit (−10 à +10), ou None.

    Returns:
        Le taux en points/heure et le régime appliqué.
    """
    threshold = config.stress_threshold_recharge
    if activity_level > config.activity_threshold:
        r = hr_reserve_fraction(hr, profile)
        return -config.gamma_activity_drain * r * r, WindowState.ACTIVITY
    if sleeping:
        quality = ans_quality(ans_charge, config)
        factor = 1.0 if quality is None else quality / config.ans_usual_quality
        return config.alpha_recharge * max(threshold - stress, 0.0) * factor, WindowState.SLEEP
    if stress < threshold:
        return config.alpha_recharge * (threshold - stress), WindowState.RECHARGE
    return -config.beta_stress_drain * (stress - threshold), WindowState.STRESS_DRAIN


class BodyBatteryEngine:
    """Intègre le score Body Battery fenêtre par fenêtre (streaming ou par lots).

    Les horodatages désignent la **fin** de chaque fenêtre. Une fenêtre invalide
    (trop peu de battements exploitables) laisse le score inchangé et n'avance pas
    l'horloge d'intégration : si les fenêtres valides suivantes arrivent plus de
    ``gap_threshold_hours`` après la dernière fenêtre valide, l'intervalle est
    traité comme un trou de données (capteur retiré).

    Pendant un trou, le score n'est pas extrapolé avec le dernier taux connu : il
    relaxe exponentiellement vers ``gap_target_score`` avec la constante de temps
    ``gap_relaxation_tau_hours`` — une hypothèse neutre faute de mesure.

    La ligne de base n'est alimentée que par les fenêtres d'éveil au repos : la VFC à
    l'effort reflète la charge et non l'état de récupération (fenêtres actives ou à FC
    au-delà de ``hr_rest + baseline_exercise_hr_margin`` exclues), et la VFC nocturne,
    bien plus haute, fausserait la référence de la journée (fenêtres ``sleeping`` exclues).

    Args:
        config: Hyperparamètres.
        profile: Profil cardiaque de l'utilisateur.
        initial_score: Score de départ ; par défaut ``config.initial_score``.
        baseline_tracker: Ligne de base existante (reprise d'historique) ; une
            neuve sinon.
    """

    def __init__(self, config: BodyBatteryConfig | None = None,
                 profile: UserProfile | None = None,
                 initial_score: float | None = None,
                 baseline_tracker: BaselineTracker | None = None) -> None:
        self.config: BodyBatteryConfig = config or BodyBatteryConfig()
        self.profile: UserProfile = profile or UserProfile()
        start = self.config.initial_score if initial_score is None else initial_score
        self.current_score: float = float(
            np.clip(start, self.config.min_score, self.config.max_score))
        self.last_timestamp: pd.Timestamp | None = None
        self.baseline_tracker: BaselineTracker = baseline_tracker or BaselineTracker(self.config)
        self._last_seen: pd.Timestamp | None = None

    @property
    def hr_rest(self) -> float:
        """FC de repos du profil."""
        return self.profile.hr_rest

    @property
    def hr_max(self) -> float:
        """FC maximale du profil."""
        return self.profile.hr_max

    def _relax_toward_neutral(self, gap_hours: float) -> None:
        """Fait relaxer le score vers la valeur neutre sur la durée d'un trou."""
        cfg = self.config
        decay = math.exp(-gap_hours / cfg.gap_relaxation_tau_hours)
        self.current_score = cfg.gap_target_score + (self.current_score - cfg.gap_target_score) * decay

    def step(self, timestamp: TimestampLike, rr_ms: npt.ArrayLike,
             activity_level: float, *, rr_errors: npt.ArrayLike | None = None,
             sleeping: bool = False, ans_charge: float | None = None) -> WindowResult:
        """Intègre une fenêtre.

        Args:
            timestamp: Fin de la fenêtre (strictement croissant d'un appel à l'autre).
            rr_ms: Intervalles RR bruts de la fenêtre, en ms.
            activity_level: Niveau d'activité (ENMO en g) ; NaN est traité comme 0.
            rr_errors: Estimation d'erreur de chaque battement (PPI Polar), ou None.
            sleeping: Vrai si la fenêtre tombe pendant le sommeil.
            ans_charge: ANS charge Polar de la nuit en cours (−10 à +10), ou None.

        Returns:
            Le résultat de la fenêtre.

        Raises:
            ValueError: Si ``timestamp`` n'est pas postérieur à l'appel précédent.
        """
        cfg = self.config
        ts = to_timestamp(timestamp)
        if self._last_seen is not None and ts <= self._last_seen:
            raise ValueError(f"horodatage non croissant : {ts} <= {self._last_seen}")
        self._last_seen = ts

        activity = float(activity_level)
        if not math.isfinite(activity):
            activity = 0.0
        quality = assess_rr_intervals(rr_ms, cfg, rr_errors)
        baseline = self.baseline_tracker.get(ts)
        nan = float("nan")
        sleeping = bool(sleeping)
        ans = float(ans_charge) if ans_charge is not None else nan

        if not quality.is_valid:
            return WindowResult(
                timestamp=ts, score=self.current_score, delta_rate=0.0, dt_hours=0.0,
                gap_hours=0.0, state=WindowState.INVALID, valid=False, stress=nan, rmssd=nan,
                ln_rmssd=nan, hr_mean=nan, hr_reserve=nan, activity_level=activity,
                sleeping=sleeping, ans_charge=ans, baseline_mean=baseline.mean, baseline_std=baseline.std,
                baseline_weight=baseline.weight, n_beats=quality.n_total,
                valid_ratio=quality.valid_ratio)

        # 1-2. Pas de temps et trous de données.
        if self.last_timestamp is None:
            dt_hours = cfg.window_hours
        else:
            dt_hours = (ts - self.last_timestamp).total_seconds() / 3600.0
        gap_hours = 0.0
        if dt_hours > cfg.gap_threshold_hours:
            gap_hours = dt_hours - cfg.window_hours
            self._relax_toward_neutral(gap_hours)
            dt_hours = cfg.window_hours

        # 3. Métriques et stress, contre la ligne de base *avant* cette fenêtre.
        metrics = compute_window_metrics(quality.rr_with_gaps)
        stress = compute_stress_score(metrics["ln_rmssd"], baseline.mean, baseline.std,
                                      cfg.stress_sigmoid_slope, cfg.stress_sigmoid_offset)

        # 4-5. Taux horaire et intégration bornée.
        rate, state = compute_rate(stress, metrics["hr_mean"], activity, self.profile, cfg,
                                   sleeping=sleeping, ans_charge=None if math.isnan(ans) else ans)
        self.current_score = float(np.clip(self.current_score + rate * dt_hours,
                                           cfg.min_score, cfg.max_score))
        self.last_timestamp = ts
        exercising = metrics["hr_mean"] > self.profile.hr_rest + cfg.baseline_exercise_hr_margin
        if state in (WindowState.RECHARGE, WindowState.STRESS_DRAIN) and not exercising:
            self.baseline_tracker.update(ts, metrics["ln_rmssd"])

        return WindowResult(
            timestamp=ts, score=self.current_score, delta_rate=rate, dt_hours=dt_hours,
            gap_hours=gap_hours, state=state, valid=True, stress=stress,
            rmssd=metrics["rmssd"], ln_rmssd=metrics["ln_rmssd"], hr_mean=metrics["hr_mean"],
            hr_reserve=hr_reserve_fraction(metrics["hr_mean"], self.profile),
            activity_level=activity, sleeping=sleeping, ans_charge=ans, baseline_mean=baseline.mean, baseline_std=baseline.std,
            baseline_weight=baseline.weight, n_beats=quality.n_total,
            valid_ratio=quality.valid_ratio)

    def process_dataframe(self, df: pd.DataFrame) -> pd.DataFrame:
        """Traite un historique de fenêtres, en reprenant l'état courant du moteur.

        Les lignes sont triées par horodatage avant traitement ; l'intégration est
        séquentielle par nature (bornage et ligne de base dépendent du passé), mais
        chaque fenêtre est traitée en numpy vectorisé. Des appels successifs sur des
        tranches consécutives donnent le même résultat qu'un appel unique.

        Args:
            df: Colonnes ``timestamp``, ``rr_intervals`` (séquence de RR en ms par
                ligne) et ``activity_level`` ; facultatives : ``rr_errors`` (estimations
                d'erreur par battement), ``sleeping`` (bool) et ``ans_charge`` (ANS charge
                Polar de la nuit, NaN si inconnue).

        Returns:
            Un DataFrame, une ligne par fenêtre, avec les champs de :class:`WindowResult`
            (``state`` en chaîne).

        Raises:
            KeyError: Si une colonne requise manque.
        """
        missing = [c for c in REQUIRED_COLUMNS if c not in df.columns]
        if missing:
            raise KeyError(f"colonnes manquantes : {missing}")
        ordered = df.assign(timestamp=pd.to_datetime(df["timestamp"])).sort_values(
            "timestamp", kind="stable")
        n = len(ordered)
        errors = ordered["rr_errors"] if "rr_errors" in ordered else [None] * n
        sleeping = ordered["sleeping"] if "sleeping" in ordered else [False] * n
        ans = ordered["ans_charge"] if "ans_charge" in ordered else [None] * n
        rows = [
            asdict(self.step(ts, rr, act,
                             rr_errors=err if isinstance(err, (list, tuple, np.ndarray)) else None,
                             sleeping=bool(slp),
                             ans_charge=None if a is None or pd.isna(a) else float(a)))
            for ts, rr, act, err, slp, a in zip(
                ordered["timestamp"], ordered["rr_intervals"], ordered["activity_level"],
                errors, sleeping, ans, strict=True)
        ]
        columns = list(WindowResult.__dataclass_fields__)
        out = pd.DataFrame(rows, columns=columns)
        out["state"] = out["state"].map(lambda s: s.value)
        return out
