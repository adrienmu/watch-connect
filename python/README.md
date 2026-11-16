# body_battery (Python)

Prototype de référence du score d'énergie physiologique (5–100) à partir d'intervalles RR et
d'accéléromètre, utilisé pour mettre au point le modèle avant son portage dans l'app Android.

```
python/
├── body_battery/
│   ├── config.py         # BodyBatteryConfig (hyperparamètres)
│   ├── preprocessing.py  # clean_rr_intervals, assess_rr_intervals, InsufficientDataError
│   ├── metrics.py        # compute_window_metrics, BaselineTracker, compute_stress_score
│   ├── engine.py         # BodyBatteryEngine (step / process_dataframe), UserProfile, WindowResult
│   ├── windowing.py      # segment_windows : flux bruts → fenêtres de 3 min, ENMO
│   └── synthetic.py      # generate_rr : RR synthétiques (FC et RMSSD visés)
├── tests/test_body_battery.py
├── example_usage.py
└── pyproject.toml
```

```bash
cd python
pip install -e ".[test]"
pytest
python example_usage.py            # ligne de base préchargée (3 jours)
python example_usage.py --cold-start
```
