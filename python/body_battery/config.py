"""Hyperparamètres ajustables du modèle Body Battery."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class BodyBatteryConfig:
    """Hyperparamètres du pipeline (nettoyage, ligne de base, intégration).

    Les champs du cahier des charges viennent en premier ; les suivants (marqués
    « extension ») rendent configurables des constantes qui seraient sinon en dur.

    Attributes:
        window_minutes: Taille de la fenêtre d'analyse, en minutes.
        rr_min_ms: Borne basse physiologique d'un intervalle RR (200 bpm).
        rr_max_ms: Borne haute physiologique d'un intervalle RR (30 bpm).
        ectopic_threshold_pct: Écart relatif maximal à la médiane glissante au-delà
            duquel un battement est jugé ectopique.
        baseline_days: Profondeur de la ligne de base personnelle glissante.
        activity_threshold: Seuil de niveau d'activité (ENMO, en g) séparant
            repos et effort.
        stress_threshold_recharge: Stress sous lequel la batterie recharge.
        alpha_recharge: Gain de recharge pendant le sommeil, en points/heure par point de
            stress sous le seuil (jusqu'à 7,5 points/h). Réglé pour qu'une nuit habituelle
            (ANS charge 0) ne suffise pas à remplir la batterie depuis un niveau bas : une
            bonne nuit (ANS charge +4) la remplit, une mauvaise (−4) en regagne moins de la
            moitié.
        alpha_recharge_awake: Gain de recharge au calme pendant l'éveil, même unité
            (extension). Bien plus faible que la nuit : une soirée calme freine la baisse
            ou recharge un peu, elle ne remplit pas la batterie (≤ 2,5 points/h).
        beta_stress_drain: Perte, en points/heure par point de stress au-dessus
            du seuil.
        gamma_activity_drain: Perte maximale en points/heure à 100 % de la
            réserve cardiaque.
        min_score: Plancher du score.
        max_score: Plafond du score.
        initial_score: Score de départ d'un moteur neuf (extension).
        ectopic_median_window: Taille (impaire) de la médiane glissante (extension).
        min_beats: Nombre minimal de battements bruts par fenêtre (extension).
        min_valid_ratio: Part minimale de battements valides (extension).
        baseline_prior_mean: μ par défaut de ln(RMSSD) en démarrage à froid (extension).
        baseline_prior_std: σ par défaut de ln(RMSSD) en démarrage à froid (extension).
        baseline_cold_start_hours: Durée de convergence du prior vers les données
            de l'utilisateur (extension).
        baseline_min_std: Plancher de σ pour éviter des Z-scores explosifs (extension).
        stress_sigmoid_slope: Pente k de la sigmoïde S = 100 / (1 + e^(k·(Z + z₀))) (extension).
        stress_sigmoid_offset: Décalage z₀ de la sigmoïde : avec 0,55, une VFC habituelle (Z = 0)
            donne un stress ≈ 30, dans la zone « faible » de l'échelle de Garmin, plutôt que 50 qui
            viderait la batterie toute la journée ; 0 redonne la formule d'origine (extension).
        max_error_estimate_ms: Battement écarté si le capteur estime son erreur au-delà (extension).
        baseline_exercise_hr_margin: Une fenêtre à plus de FC de repos + cette marge n'alimente pas
            la ligne de base, même sans accéléromètre (extension).
        ans_usual_quality: Qualité de nuit attribuée à une ANS charge Polar habituelle (0) (extension).
        ans_quality_per_point: Pente de la qualité par point d'ANS charge (−10 à +10) (extension).
        gap_threshold_hours: Au-delà de cet écart entre deux fenêtres valides,
            l'intervalle est traité comme un trou de données (extension).
        gap_target_score: Valeur neutre vers laquelle le score relaxe pendant un
            trou (extension).
        gap_relaxation_tau_hours: Constante de temps de cette relaxation (extension).
    """

    window_minutes: int = 3
    rr_min_ms: float = 300.0
    rr_max_ms: float = 2000.0
    ectopic_threshold_pct: float = 0.20
    baseline_days: int = 7
    activity_threshold: float = 0.2
    stress_threshold_recharge: float = 25.0
    alpha_recharge: float = 0.3
    alpha_recharge_awake: float = 0.1
    beta_stress_drain: float = 0.1
    gamma_activity_drain: float = 15.0
    min_score: float = 5.0
    max_score: float = 100.0

    initial_score: float = 75.0
    ectopic_median_window: int = 5
    min_beats: int = 10
    min_valid_ratio: float = 0.5
    baseline_prior_mean: float = 3.8
    baseline_prior_std: float = 0.5
    baseline_cold_start_hours: float = 24.0
    baseline_min_std: float = 0.1
    stress_sigmoid_slope: float = 1.5
    stress_sigmoid_offset: float = 0.55
    max_error_estimate_ms: float = 25.0
    baseline_exercise_hr_margin: float = 30.0
    ans_usual_quality: float = 0.6
    ans_quality_per_point: float = 0.08
    gap_threshold_hours: float = 0.25
    gap_target_score: float = 50.0
    gap_relaxation_tau_hours: float = 4.0

    def __post_init__(self) -> None:
        """Valide la cohérence des paramètres.

        Raises:
            ValueError: Si un paramètre est hors de son domaine.
        """
        if self.window_minutes <= 0:
            raise ValueError("window_minutes doit être > 0")
        if not 0 < self.rr_min_ms < self.rr_max_ms:
            raise ValueError("il faut 0 < rr_min_ms < rr_max_ms")
        if not 0 < self.ectopic_threshold_pct < 1:
            raise ValueError("ectopic_threshold_pct doit être dans ]0, 1[")
        if self.ectopic_median_window < 3 or self.ectopic_median_window % 2 == 0:
            raise ValueError("ectopic_median_window doit être impair et >= 3")
        if self.baseline_days <= 0:
            raise ValueError("baseline_days doit être > 0")
        if not self.min_score < self.max_score:
            raise ValueError("il faut min_score < max_score")
        if not self.min_score <= self.initial_score <= self.max_score:
            raise ValueError("initial_score doit être dans [min_score, max_score]")
        if not self.min_score <= self.gap_target_score <= self.max_score:
            raise ValueError("gap_target_score doit être dans [min_score, max_score]")
        if not 0 < self.min_valid_ratio <= 1:
            raise ValueError("min_valid_ratio doit être dans ]0, 1]")
        if self.min_beats < 3:
            raise ValueError("min_beats doit être >= 3")
        if self.baseline_prior_std <= 0 or self.baseline_min_std <= 0:
            raise ValueError("les écarts-types doivent être > 0")
        if self.baseline_cold_start_hours <= 0 or self.gap_relaxation_tau_hours <= 0:
            raise ValueError("les constantes de temps doivent être > 0")
        if self.gap_threshold_hours <= self.window_hours:
            raise ValueError("gap_threshold_hours doit dépasser la durée d'une fenêtre")
        for name in ("alpha_recharge", "alpha_recharge_awake", "beta_stress_drain",
                     "gamma_activity_drain"):
            if getattr(self, name) < 0:
                raise ValueError(f"{name} doit être >= 0")

    @property
    def window_hours(self) -> float:
        """Durée d'une fenêtre d'analyse, en heures."""
        return self.window_minutes / 60.0
