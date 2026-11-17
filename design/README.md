# Maquette officielle

La maquette de référence de Boucle est le canevas Claude Design
« Boucle — écrans de l'app » : https://claude.ai/artifact/JqrYbHye9JJG2A8tvL8NSd

Une copie de ses fichiers est gardée dans [`maquette/`](maquette/) : un fichier `.dc.html` par écran
(HTML lisible dans un navigateur, styles en ligne) et `canvas.json`, qui donne la disposition des écrans
sur le canevas. L'app doit rester fidèle à ces écrans.

| Écran | Fichier | Composable |
| --- | --- | --- |
| Aujourd'hui | `Main.dc.html` | `TodayScreen` |
| Sommeil | `Sommeil.dc.html` | `SleepScreen` |
| Récupération | `Recuperation.dc.html` | `RecoveryScreen` |
| Body Battery | `Energie.dc.html` | `EnergyScreen` |
| Fréquence cardiaque | `Coeur.dc.html` | `HeartScreen` |
| Forme | `Activite.dc.html` | `ActivityScreen` |
| VO2max | `Vo2max.dc.html` | `Vo2MaxScreen` |
| Corps | `Corps.dc.html` | `BodyScreen` |
| Appareils | `Appareils.dc.html` | `DevicesScreen` |
| Associer un bracelet | `Association.dc.html` | `PairingScreen` |
| Configuration du bracelet | `Profil.dc.html` | `ProfileScreen` |
| Première ouverture | `Bienvenue.dc.html` | `WelcomeScreen` |

Conventions de passage de la maquette au code :

- 1 px de la maquette = 1 dp ; 1 px de police = 1 sp.
- Couleurs, styles de texte et rayons : `app/src/main/java/com/boucle/app/ui/theme/Theme.kt`.
- Icônes au trait : `ui/theme/BoucleIcons.kt` (mêmes tracés SVG que la maquette).
- Les valeurs affichées dans la maquette sont fictives ; l'app affiche les données réelles.
- Material 3 Expressive : `MaterialExpressiveTheme` avec `MotionScheme.expressive()`, formes
  `MaterialShapes`, ressorts du thème pour toutes les animations.
