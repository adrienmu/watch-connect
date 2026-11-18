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
| Énergie | `Energie.dc.html` | `EnergyScreen` |
| Fréquence cardiaque | `Coeur.dc.html` | `HeartScreen` |
| Forme (historique des pas : semaine, mois) | `Activite.dc.html`, `ActiviteMois.dc.html` | `ActivityScreen` (`StepsHistoryTile`) |
| VO2max | `Vo2max.dc.html` | `Vo2MaxScreen` |
| Corps | `Corps.dc.html` | `BodyScreen` |
| Appareils | `Appareils.dc.html` | `DevicesScreen` |
| Associer un bracelet | `Association.dc.html` | `PairingScreen` |
| Configuration du bracelet | `Profil.dc.html` | `ProfileScreen` |
| Objectifs quotidiens | `Objectifs.dc.html` | `GoalsScreen` |
| Première ouverture | `Bienvenue.dc.html` | `WelcomeScreen` |
| Détail d'activité (carte GPS, onglets Allure / Fréq. card. / Altitude) | `ActiviteGPS.dc.html`, `ActiviteGPSFrequence.dc.html`, `ActiviteGPSAltitude.dc.html` | `ActivityDetailScreen` |
| Carte plein écran | `ActiviteGPSPleinEcran.dc.html` | `RouteFullScreen` |
| Forme : lancer une activité, activité détectée, activités récentes | `ActiviteLancer.dc.html` | `ActivityScreen` (`ActivityHomeSection`) |
| Nouvelle activité (choix du sport, capteurs) | `ActiviteChoix.dc.html` | `ActivityStartScreen` |
| Autorisation de position | `ActiviteAutorisation.dc.html` | `ActivityPermissionScreen` |
| Activité en cours (course, vélo, marche) | `ActiviteEnCours.dc.html`, `ActiviteEnCoursVelo.dc.html`, `ActiviteEnCoursMarche.dc.html` | `ActivityRecordingScreen` |
| Fin de séance | `ActiviteResume.dc.html` | `ActivitySummaryScreen` |
| Activité détectée par le bracelet | `ActiviteDetectee.dc.html` | `ActivityDetectedScreen` |
| Modifier une activité | `ActiviteModifier.dc.html` | `ActivityEditScreen` |
| Tracé à autoriser, activité sans GPS | `ActiviteSansGPS.dc.html` | `ActivityDetailScreen` (`ConsentMap`) |
| Carte d'exploration (vue de près, vue éloignée, aucun tracé) | `Exploration.dc.html`, `ExplorationLarge.dc.html`, `ExplorationVide.dc.html` | `ExplorationScreen` |

Conventions de passage de la maquette au code :

- 1 px de la maquette = 1 dp ; 1 px de police = 1 sp.
- Couleurs, styles de texte et rayons : `app/src/main/java/com/boucle/app/ui/theme/Theme.kt`.
- Le détail d'activité a sa propre palette indigo (`RouteColors` dans `Theme.kt`) ; son fond de carte vient des
  tuiles OpenStreetMap (`ui/map/`), à la place du fond dessiné de la maquette.
- Police : Google Sans Flex, axe d'arrondi « ROND » à 100 (`app/src/main/res/font/google_sans_flex.ttf`, licence OFL
  dans `app/licences/`).
- Macarons (formes festonnées `MaterialShapes`) et jauges (arc, demi-arc, anneau, linéaire) :
  `ui/components/Expressive.kt` (`BadgeShape`) et `ui/components/Gauges.kt`.
- Icônes au trait : `ui/theme/BoucleIcons.kt` (mêmes tracés SVG que la maquette).
- Les valeurs affichées dans la maquette sont fictives ; l'app affiche les données réelles.
- Material 3 Expressive : `MaterialExpressiveTheme` avec `MotionScheme.expressive()`, formes
  `MaterialShapes`, ressorts du thème pour toutes les animations.
