"""Démonstration : 24 h de données synthétiques réalistes → évolution du Body Battery.

Scénario (FC de repos 55, FC max 190) :

* 00:00–07:00  sommeil            FC ≈ 52, RMSSD ≈ 75 ms, immobile
* 07:00–08:30  réveil, trajet     FC ≈ 72, RMSSD ≈ 38 ms, marche légère
* 08:30–12:30  bureau stressant   FC ≈ 80, RMSSD ≈ 24 ms, assis
* 12:30–13:30  pause déjeuner     FC ≈ 70, RMSSD ≈ 40 ms, marche
* 13:30–17:30  bureau stressant   FC ≈ 82, RMSSD ≈ 22 ms, assis
* 17:30–18:00  retour à domicile  FC ≈ 75, RMSSD ≈ 32 ms
* 18:00–18:20  capteur retiré     (aucune donnée)
* 18:20–19:20  course à pied      FC ≈ 160 (montée en charge), RMSSD ≈ 8 ms
* 19:20–23:00  soirée calme       FC ≈ 62, RMSSD ≈ 50 ms
* 23:00–24:00  endormissement     FC ≈ 55, RMSSD ≈ 65 ms

Les flux bruts (un intervalle RR par battement, accéléromètre 3 axes à 5 Hz, avec
artefacts injectés) sont découpés en fenêtres de 3 min puis intégrés par le moteur.
Une ligne de base de 3 jours est préchargée : sans elle, le démarrage à froid
s'appuie sur le prior (μ = 3,8, σ = 0,5).

Usage : ``python example_usage.py [--cold-start] [--csv sortie.csv]``
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass

import numpy as np
import pandas as pd

from body_battery import (BaselineTracker, BodyBatteryConfig, BodyBatteryEngine, UserProfile,
                          segment_windows)
from body_battery.synthetic import generate_rr

DAY = pd.Timestamp("2026-11-16")
ACC_HZ = 5


@dataclass(frozen=True, slots=True)
class Phase:
    """Segment homogène de la journée."""

    start: str
    end: str
    label: str
    hr: float
    rmssd: float
    enmo: float  # niveau d'activité moyen (g)
    worn: bool = True


PHASES: tuple[Phase, ...] = (
    Phase("00:00", "07:00", "sommeil", 52, 75, 0.01),
    Phase("07:00", "08:30", "réveil, trajet", 72, 38, 0.12),
    Phase("08:30", "12:30", "bureau stressant", 80, 24, 0.03),
    Phase("12:30", "13:30", "déjeuner", 70, 40, 0.10),
    Phase("13:30", "17:30", "bureau stressant", 82, 22, 0.03),
    Phase("17:30", "18:00", "retour", 75, 32, 0.10),
    Phase("18:00", "18:20", "capteur retiré", 0, 0, 0.0, worn=False),
    Phase("18:20", "19:20", "course à pied", 160, 8, 0.70),
    Phase("19:20", "23:00", "soirée calme", 62, 50, 0.04),
    Phase("23:00", "23:59:59", "endormissement", 55, 65, 0.01),
)


def synthesize_day(rng: np.random.Generator) -> tuple[pd.DatetimeIndex, np.ndarray,
                                                       pd.DatetimeIndex, np.ndarray]:
    """Génère les flux bruts de la journée.

    Returns:
        (horodatages des battements, RR en ms, horodatages accéléromètre, accélérations (n, 3) en g).
    """
    beat_t: list[np.ndarray] = []
    beat_rr: list[np.ndarray] = []
    acc_t: list[pd.DatetimeIndex] = []
    acc_v: list[np.ndarray] = []
    for p in PHASES:
        start = DAY + pd.Timedelta(p.start + ":00" if p.start.count(":") == 1 else p.start)
        end = DAY + pd.Timedelta(p.end + ":00" if p.end.count(":") == 1 else p.end)
        if not p.worn:
            continue
        seconds = (end - start).total_seconds()
        # FC et RMSSD dérivent lentement à l'intérieur d'une phase (blocs de 5 min).
        t = start
        while t < end:
            block = min(300.0, (end - t).total_seconds())
            if p.label == "course à pied":  # échauffement → allure → retour au calme
                frac = (t - start).total_seconds() / seconds
                hr = 120 + 55 * np.sin(np.pi * min(frac * 1.3, 1.0))
            else:
                hr = p.hr * rng.normal(1.0, 0.03)
            rr = generate_rr(hr, p.rmssd * rng.lognormal(0.0, 0.15), block, rng)
            beats = t + pd.to_timedelta(np.cumsum(rr), unit="ms")
            keep = beats < t + pd.Timedelta(seconds=block)
            beat_t.append(beats[keep].to_numpy())
            beat_rr.append(rr[keep])
            t += pd.Timedelta(seconds=block)
        # Accéléromètre : gravité sur z + secousses proportionnelles à l'activité.
        times = pd.date_range(start, end, freq=f"{1000 // ACC_HZ}ms", inclusive="left")
        acc = rng.normal(0.0, 0.01, (times.size, 3))
        acc[:, 2] += 1.0 + np.abs(rng.normal(p.enmo, p.enmo * 0.5 + 1e-3, times.size))
        acc_t.append(times)
        acc_v.append(acc)

    rr_all = np.concatenate(beat_rr)
    # Artefacts : détections manquées, doubles détections, extrasystoles.
    n = rr_all.size
    rr_all[rng.choice(n, n // 400, replace=False)] *= 2.0
    rr_all[rng.choice(n, n // 400, replace=False)] *= 0.45
    rr_all[rng.choice(n, n // 1000, replace=False)] = 0.0
    return (pd.DatetimeIndex(np.concatenate(beat_t)), rr_all,
            pd.DatetimeIndex(np.concatenate([t.to_numpy() for t in acc_t])), np.vstack(acc_v))


def warm_baseline(config: BodyBatteryConfig, rng: np.random.Generator) -> BaselineTracker:
    """Ligne de base de 3 jours de repos (nuits ≈ ln 75 ms, journées ≈ ln 30 ms)."""
    tracker = BaselineTracker(config)
    for ts in pd.date_range(DAY - pd.Timedelta(days=3), DAY, freq="3min", inclusive="left"):
        asleep = ts.hour < 7 or ts.hour >= 23
        tracker.update(ts, float(rng.normal(np.log(75 if asleep else 32), 0.25)))
    return tracker


def bar(score: float, width: int = 40) -> str:
    """Barre ASCII proportionnelle au score."""
    filled = int(round(score / 100 * width))
    return "█" * filled + "·" * (width - filled)


def main() -> None:
    """Point d'entrée."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--cold-start", action="store_true",
                        help="ne pas précharger de ligne de base")
    parser.add_argument("--csv", help="écrire le détail par fenêtre dans ce fichier")
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()

    rng = np.random.default_rng(args.seed)
    config = BodyBatteryConfig()
    profile = UserProfile(hr_rest=55.0, hr_max=190.0)

    beat_t, rr, acc_t, acc = synthesize_day(rng)
    windows = segment_windows(beat_t, rr, acc_t, acc, config, origin=DAY)
    print(f"{rr.size} battements, {acc_t.size} échantillons d'accéléromètre "
          f"→ {len(windows)} fenêtres de {config.window_minutes} min\n")

    baseline = None if args.cold_start else warm_baseline(config, rng)
    engine = BodyBatteryEngine(config, profile, baseline_tracker=baseline)
    out = engine.process_dataframe(windows)

    def phase_of(ts: pd.Timestamp) -> str:
        t = ts - pd.Timedelta(seconds=1)
        for p in PHASES:
            if p.worn and str(t.time()) >= p.start:
                label = p.label
        return label

    print("heure  score  stress   FC  régime dominant")
    hourly = out.set_index("timestamp").resample("1h", label="right", closed="right")
    for ts, grp in hourly:
        if grp.empty:
            continue
        valid = grp[grp["valid"]]
        state = valid["state"].mode().iat[0] if not valid.empty else "invalid"
        print(f"{ts:%H:%M}  {grp['score'].iat[-1]:5.1f}  {valid['stress'].mean():6.1f}  "
              f"{valid['hr_mean'].mean():3.0f}  {state:<13s}{bar(grp['score'].iat[-1])}")

    print("\nBilan par phase :")
    out["phase"] = out["timestamp"].map(phase_of)
    runs = (out["phase"] != out["phase"].shift()).cumsum()
    for _, grp in out.groupby(runs, sort=False):
        label = grp["phase"].iat[0]
        start = grp["score"].iat[0] - grp["delta_rate"].iat[0] * grp["dt_hours"].iat[0]
        print(f"  {label:<17s} {start:5.1f} → {grp['score'].iat[-1]:5.1f}")

    gaps = out[out["gap_hours"] > 0]
    for _, g in gaps.iterrows():
        print(f"\nTrou de données de {g['gap_hours'] * 60:.0f} min avant {g['timestamp']:%H:%M} : "
              f"relaxation vers {config.gap_target_score:.0f}.")
    print(f"Fenêtres invalides : {(~out['valid']).sum()} / {len(out)}")
    print(f"Score final : {engine.current_score:.1f}")

    if args.csv:
        out.drop(columns=["phase"]).to_csv(args.csv, index=False)
        print(f"Détail écrit dans {args.csv}")


if __name__ == "__main__":
    main()
