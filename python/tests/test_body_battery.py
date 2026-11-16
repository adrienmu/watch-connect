"""Tests du package body_battery."""

from __future__ import annotations

import math

import numpy as np
import pandas as pd
import pytest

from body_battery import (BaselineTracker, BodyBatteryConfig, BodyBatteryEngine,
                          InsufficientDataError, UserProfile, WindowState, ans_quality,
                          assess_rr_intervals, clean_rr_intervals, compute_rate,
                          compute_stress_score, compute_window_metrics, segment_windows)
from body_battery.synthetic import generate_rr

CFG = BodyBatteryConfig()
PROFILE = UserProfile(hr_rest=55.0, hr_max=190.0)
T0 = pd.Timestamp("2026-11-10 23:00")


def make_windows(start: pd.Timestamp, hours: float, hr: float, rmssd: float,
                 activity: float, seed: int, cfg: BodyBatteryConfig = CFG) -> pd.DataFrame:
    """Fenêtres consécutives d'un régime stationnaire (horodatage = fin de fenêtre)."""
    rng = np.random.default_rng(seed)
    n = int(round(hours * 60 / cfg.window_minutes))
    step = pd.Timedelta(minutes=cfg.window_minutes)
    return pd.DataFrame({
        "timestamp": [start + (i + 1) * step for i in range(n)],
        "rr_intervals": [generate_rr(hr, rmssd, cfg.window_minutes * 60, rng) for _ in range(n)],
        "activity_level": np.full(n, activity),
    })


def warmed_baseline(days: int = 3, seed: int = 1) -> BaselineTracker:
    """Ligne de base d'un utilisateur suivi depuis quelques jours (ln RMSSD ≈ 3,7 ± 0,35)."""
    tracker = BaselineTracker(CFG)
    rng = np.random.default_rng(seed)
    times = pd.date_range(T0 - pd.Timedelta(days=days), T0, freq="3min", inclusive="left")
    for ts, x in zip(times, rng.normal(3.7, 0.35, len(times)), strict=True):
        tracker.update(ts, float(x))
    return tracker


# --------------------------------------------------------------------------- nettoyage


class TestCleaning:
    def test_removes_artifacts_and_interpolates_ectopics(self) -> None:
        rng = np.random.default_rng(42)
        rr = 800.0 + rng.normal(0.0, 15.0, 60)
        rr[5] = 0.0  # perte de détection
        rr[12] = 3000.0  # battement manqué / artefact
        rr[20] = 450.0  # extrasystole prématurée…
        rr[21] = 1150.0  # … suivie de la pause compensatrice
        rr[40] = 1300.0  # pic ectopique isolé
        expected_normal = np.delete(rr, [5, 12, 20, 21, 40])

        clean = clean_rr_intervals(rr, CFG)
        report = assess_rr_intervals(rr, CFG)

        assert clean.size == rr.size - 2  # seuls les hors-bornes sont retirés
        assert report.n_out_of_range == 2
        assert report.n_ectopic == 3
        assert np.all((clean >= CFG.rr_min_ms) & (clean <= CFG.rr_max_ms))
        # Les ectopiques (indices décalés de 2 après retrait) sont ramenés près de 800 ms.
        for idx in (18, 19, 38):
            assert abs(clean[idx] - 800.0) < 60.0
        # Les battements normaux sont intacts.
        normal_mask = np.ones(clean.size, bool)
        normal_mask[[18, 19, 38]] = False
        np.testing.assert_allclose(clean[normal_mask], expected_normal)

    def test_clean_signal_is_unchanged(self) -> None:
        rr = generate_rr(60, 40, 180, np.random.default_rng(0))
        np.testing.assert_allclose(clean_rr_intervals(rr, CFG), rr)

    def test_too_few_beats_raises(self) -> None:
        with pytest.raises(InsufficientDataError):
            clean_rr_intervals(np.full(9, 800.0), CFG)

    def test_mostly_invalid_window_raises(self) -> None:
        rr = np.r_[np.full(8, 800.0), np.full(12, 0.0)]
        assert not assess_rr_intervals(rr, CFG).is_valid
        with pytest.raises(InsufficientDataError):
            clean_rr_intervals(rr, CFG)

    def test_nan_counts_as_out_of_range(self) -> None:
        rr = np.r_[np.full(20, 800.0), [np.nan]]
        assert clean_rr_intervals(rr, CFG).size == 20

    def test_sensor_error_estimate_rejects_beats(self) -> None:
        rr = np.full(40, 800.0)
        errors = np.full(40, 8.0)
        errors[[5, 6]] = 80.0
        errors[7] = np.nan  # pas d'estimation : conservé
        assert clean_rr_intervals(rr, CFG, errors).size == 38
        with pytest.raises(ValueError):
            clean_rr_intervals(rr, CFG, errors[:10])

    def test_rmssd_does_not_bridge_removed_beats(self) -> None:
        # Alternance 790/810 (RMSSD 20) ; un battement retiré au milieu ne doit pas créer
        # une fausse différence 790 → 790 entre ses voisins.
        rr = np.array([790.0, 810.0] * 20)
        rr[21] = 0.0
        report = assess_rr_intervals(rr, CFG)
        assert np.isnan(report.rr_with_gaps[21])
        assert compute_window_metrics(report.rr_with_gaps)["rmssd"] == pytest.approx(20.0)
        assert compute_window_metrics(report.rr_clean)["rmssd"] < 20.0


# --------------------------------------------------------------------------- métriques


class TestMetrics:
    def test_window_metrics(self) -> None:
        m = compute_window_metrics([1000.0, 1020.0, 1000.0, 1020.0])
        assert m["rmssd"] == pytest.approx(20.0)
        assert m["ln_rmssd"] == pytest.approx(math.log(20.0))
        assert m["hr_mean"] == pytest.approx(60_000 / 1010.0)

    def test_stress_sigmoid(self) -> None:
        assert compute_stress_score(3.8, 3.8, 0.5) == pytest.approx(50.0)
        assert compute_stress_score(4.8, 3.8, 0.5) == pytest.approx(100 / (1 + math.exp(3.0)))
        assert compute_stress_score(2.8, 3.8, 0.5) > 95.0
        assert 0.0 <= compute_stress_score(100.0, 3.8, 0.5) <= compute_stress_score(-100, 3.8, 0.5) <= 100.0
        with pytest.raises(ValueError):
            compute_stress_score(3.8, 3.8, 0.0)

    def test_stress_offset_centres_usual_hrv_on_low_stress(self) -> None:
        assert compute_stress_score(3.8, 3.8, 0.5, offset=0.55) == pytest.approx(
            100 / (1 + math.exp(1.5 * 0.55)))
        assert 25.0 < compute_stress_score(3.8, 3.8, 0.5, offset=CFG.stress_sigmoid_offset) < 35.0

    def test_ans_quality(self) -> None:
        assert ans_quality(0.0) == pytest.approx(0.6)
        assert ans_quality(5.0) == pytest.approx(1.0)
        assert ans_quality(-3.0) == pytest.approx(0.36)
        assert ans_quality(-10.0) == 0.0
        assert ans_quality(None) is None
        assert ans_quality(float("nan")) is None
        assert ans_quality(42.0) is None

    def test_baseline_cold_start_converges(self) -> None:
        tracker = BaselineTracker(CFG)
        assert tracker.get().mean == CFG.baseline_prior_mean
        times = pd.date_range(T0, periods=48 * 20, freq="3min")  # 48 h
        weights, means = [], []
        for ts in times:
            tracker.update(ts, 3.2)
            b = tracker.get(ts)
            weights.append(b.weight)
            means.append(b.mean)
        assert np.all(np.diff(weights) >= 0)
        assert np.all(np.diff(means) <= 1e-12)  # converge de 3,8 vers 3,2
        assert 0.4 < weights[12 * 20] < 0.6
        assert means[-1] == pytest.approx(3.2)
        assert tracker.get().std == pytest.approx(CFG.baseline_min_std)  # plancher

    def test_baseline_window_evicts_old_samples(self) -> None:
        cfg = BodyBatteryConfig(baseline_days=1)
        tracker = BaselineTracker(cfg)
        for ts in pd.date_range(T0, periods=20 * 24, freq="3min"):
            tracker.update(ts, 3.0)
        for ts in pd.date_range(T0 + pd.Timedelta(days=2), periods=20 * 30, freq="3min"):
            tracker.update(ts, 4.0)
        assert tracker.get().mean == pytest.approx(4.0)
        assert len(tracker) <= 20 * 24 + 1


# --------------------------------------------------------------------------- moteur


class TestEngine:
    def test_rate_formulas(self) -> None:
        rate, state = compute_rate(10.0, 60.0, 0.0, PROFILE, CFG)
        assert state is WindowState.RECHARGE and rate == pytest.approx(0.4 * 15)
        rate, state = compute_rate(75.0, 80.0, 0.0, PROFILE, CFG)
        assert state is WindowState.STRESS_DRAIN and rate == pytest.approx(-0.1 * 50)
        rate, state = compute_rate(10.0, 190.0, 0.9, PROFILE, CFG)
        assert state is WindowState.ACTIVITY and rate == pytest.approx(-15.0)
        rate, _ = compute_rate(10.0, 40.0, 0.9, PROFILE, CFG)
        assert rate == 0.0  # FC sous la FC de repos : réserve bornée à 0

    def test_sleep_8h_recharges_to_ceiling(self) -> None:
        engine = BodyBatteryEngine(CFG, PROFILE, baseline_tracker=warmed_baseline())
        night = make_windows(T0, 8.0, hr=52, rmssd=75, activity=0.0, seed=2)
        out = engine.process_dataframe(night)

        assert (out["state"] == "recharge").mean() > 0.95
        assert out["score"].iloc[0] > CFG.initial_score
        assert out["score"].diff().dropna().min() >= -1e-9  # ne fait que monter
        assert out["score"].iloc[-1] == pytest.approx(CFG.max_score)
        assert out["score"].max() <= CFG.max_score

    def test_sleep_on_cold_start_still_recharges(self) -> None:
        engine = BodyBatteryEngine(CFG, PROFILE, initial_score=40.0)
        out = engine.process_dataframe(make_windows(T0, 8.0, 50, 90, 0.0, seed=3))
        assert out["score"].iloc[-1] > 70.0
        assert out["baseline_weight"].iloc[-1] == pytest.approx(8 / 24, abs=0.01)

    def test_intense_sport_2h_drains_fast(self) -> None:
        engine = BodyBatteryEngine(CFG, PROFILE, baseline_tracker=warmed_baseline())
        out = engine.process_dataframe(make_windows(T0, 2.0, hr=180, rmssd=6, activity=0.9, seed=4))

        assert (out["state"] == "activity").all()
        assert out["hr_reserve"].mean() > 0.9
        # γ·r² ≈ 12–15 pts/h : environ −25 à −30 points en 2 h.
        assert CFG.initial_score - out["score"].iloc[-1] > 20.0
        assert out["score"].diff().dropna().max() <= 1e-9

    def test_intense_sport_respects_floor(self) -> None:
        engine = BodyBatteryEngine(CFG, PROFILE, initial_score=20.0,
                                   baseline_tracker=warmed_baseline())
        out = engine.process_dataframe(make_windows(T0, 2.0, hr=185, rmssd=5, activity=1.2, seed=5))
        assert out["score"].min() == pytest.approx(CFG.min_score)
        assert (out["score"] >= CFG.min_score).all()
        assert out["score"].iloc[-1] == pytest.approx(CFG.min_score)

    def test_activity_windows_do_not_feed_baseline(self) -> None:
        engine = BodyBatteryEngine(CFG, PROFILE)
        engine.process_dataframe(make_windows(T0, 1.0, 170, 8, 0.9, seed=6))
        assert len(engine.baseline_tracker) == 0

    def test_high_heart_rate_at_rest_does_not_feed_baseline(self) -> None:
        engine = BodyBatteryEngine(CFG, PROFILE)
        engine.process_dataframe(make_windows(T0, 1.0, 120, 10, 0.1, seed=14))
        assert len(engine.baseline_tracker) == 0

    def test_sleep_windows_do_not_feed_baseline_and_never_drain(self) -> None:
        engine = BodyBatteryEngine(CFG, PROFILE, initial_score=50.0)
        night = make_windows(T0, 2.0, 70, 10, 0.0, seed=15).assign(sleeping=True)
        out = engine.process_dataframe(night)
        assert len(engine.baseline_tracker) == 0
        assert (out["state"] == "sleep").all()
        assert (out["delta_rate"] >= 0.0).all()

    def test_ans_charge_scales_the_night_recharge(self) -> None:
        def night_end(ans: float | None) -> float:
            engine = BodyBatteryEngine(CFG, PROFILE, initial_score=30.0,
                                       baseline_tracker=warmed_baseline())
            night = make_windows(T0, 4.0, 52, 75, 0.0, seed=16).assign(sleeping=True, ans_charge=ans)
            return float(engine.process_dataframe(night)["score"].iloc[-1])

        poor, usual, good, unknown = night_end(-5.0), night_end(0.0), night_end(4.0), night_end(None)
        assert poor < usual - 5 < good - 10
        assert unknown == pytest.approx(usual)

    def test_4h_sensor_gap_relaxes_toward_neutral(self) -> None:
        engine = BodyBatteryEngine(CFG, PROFILE, baseline_tracker=warmed_baseline())
        before = engine.process_dataframe(make_windows(T0, 8.0, 52, 75, 0.0, seed=7))
        score_before = before["score"].iloc[-1]
        assert score_before > 90.0
        # Dernière fenêtre en effort intense juste avant le retrait du capteur.
        last = before["timestamp"].iloc[-1]
        sport = engine.process_dataframe(make_windows(last, 0.5, 180, 6, 0.9, seed=8))
        score_before_gap = sport["score"].iloc[-1]
        last = sport["timestamp"].iloc[-1]

        after = engine.process_dataframe(
            make_windows(last + pd.Timedelta(hours=4), 0.5, 60, 40, 0.0, seed=9))
        first = after.iloc[0]

        assert first["gap_hours"] == pytest.approx(4.0)
        assert first["dt_hours"] == pytest.approx(CFG.window_hours)
        # Relaxation : 50 + (S − 50)·e^(−4/τ), sans extrapoler la pente de l'effort.
        relaxed = 50.0 + (score_before_gap - 50.0) * math.exp(-4.0 / CFG.gap_relaxation_tau_hours)
        assert first["score"] == pytest.approx(relaxed + first["delta_rate"] * CFG.window_hours)
        extrapolated = score_before_gap + sport["delta_rate"].iloc[-1] * 4.0
        assert first["score"] > max(extrapolated, 50.0)
        assert first["score"] < score_before_gap
        # Les fenêtres suivantes reprennent l'intégration normale.
        assert (after["gap_hours"].iloc[1:] == 0.0).all()

    def test_gap_from_low_score_moves_up_toward_neutral(self) -> None:
        engine = BodyBatteryEngine(CFG, PROFILE, initial_score=10.0)
        engine.step(T0, generate_rr(60, 40, 180, np.random.default_rng(0)), 0.0)
        res = engine.step(T0 + pd.Timedelta(hours=4), generate_rr(60, 40, 180, np.random.default_rng(1)), 0.0)
        assert 10.0 < res.score < 50.0

    def test_invalid_window_keeps_score_and_clock(self) -> None:
        engine = BodyBatteryEngine(CFG, PROFILE)
        rng = np.random.default_rng(10)
        engine.step(T0, generate_rr(60, 40, 180, rng), 0.0)
        res = engine.step(T0 + pd.Timedelta(minutes=3), np.full(5, 800.0), 0.0)
        assert not res.valid and res.state is WindowState.INVALID
        assert res.score == engine.current_score
        assert engine.last_timestamp == T0

    def test_rejects_non_increasing_timestamps(self) -> None:
        engine = BodyBatteryEngine(CFG, PROFILE)
        rr = generate_rr(60, 40, 180, np.random.default_rng(0))
        engine.step(T0, rr, 0.0)
        with pytest.raises(ValueError):
            engine.step(T0, rr, 0.0)

    def test_chunked_processing_matches_single_pass(self) -> None:
        day = pd.concat([make_windows(T0, 2.0, 55, 60, 0.0, seed=11),
                         make_windows(T0 + pd.Timedelta(hours=2), 1.0, 150, 10, 0.6, seed=12),
                         make_windows(T0 + pd.Timedelta(hours=3), 2.0, 75, 25, 0.05, seed=13)],
                        ignore_index=True)
        whole = BodyBatteryEngine(CFG, PROFILE).process_dataframe(day)
        streaming = BodyBatteryEngine(CFG, PROFILE)
        parts = [streaming.process_dataframe(day.iloc[i:i + 17]) for i in range(0, len(day), 17)]
        pd.testing.assert_frame_equal(whole, pd.concat(parts, ignore_index=True))

    def test_missing_columns(self) -> None:
        with pytest.raises(KeyError):
            BodyBatteryEngine().process_dataframe(pd.DataFrame({"timestamp": []}))


# --------------------------------------------------------------------------- fenêtrage


def test_segment_windows_from_raw_streams() -> None:
    rng = np.random.default_rng(0)
    rr = generate_rr(60, 40, 9 * 60, rng)
    beat_times = T0 + pd.to_timedelta(np.cumsum(rr), unit="ms")
    keep = beat_times < T0 + pd.Timedelta(minutes=9)  # exactement 9 min de battements
    rr, beat_times = rr[keep], beat_times[keep]
    acc_times = pd.date_range(T0, periods=9 * 60 * 5, freq="200ms")
    acc = np.zeros((acc_times.size, 3))
    acc[:, 2] = 1.0
    acc[: 3 * 60 * 5, 2] = 1.5  # première fenêtre : ENMO = 0,5 g

    df = segment_windows(beat_times, rr, acc_times, acc, CFG, origin=T0)

    assert list(df["timestamp"]) == [T0 + pd.Timedelta(minutes=m) for m in (3, 6, 9)]
    assert df["activity_level"].tolist() == pytest.approx([0.5, 0.0, 0.0])
    assert sum(len(x) for x in df["rr_intervals"]) == rr.size
    assert "rr_errors" not in df

    with_errors = segment_windows(beat_times, rr, acc_times, acc, CFG, origin=T0,
                                  error_estimates_ms=np.full(rr.size, 8.0))
    assert [len(e) for e in with_errors["rr_errors"]] == [len(r) for r in df["rr_intervals"]]
    out = BodyBatteryEngine(CFG, PROFILE).process_dataframe(with_errors)
    assert out["valid"].all()
