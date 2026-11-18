---
title: Politique de confidentialité
permalink: /confidentialite/
---

# Politique de confidentialité de Boucle

Dernière mise à jour : 18 novembre 2026

**Contact :** adrien.mussio@eurl-baam.fr

## En bref

- Vos données de santé restent **sur votre téléphone** : aucune ne passe par un serveur, tout est local. Seule exception, l'affichage d'une carte de parcours GPS (voir « Cartes » ci-dessous).
- Pas de compte, pas de publicité, pas d'analyse d'audience, pas de pistage.
- Boucle ne vend ni ne partage vos données avec qui que ce soit.
- Dans Health Connect, Boucle ne fait que **lire** : elle n'écrit ni ne modifie rien.
- Les données sont conservées **sur le téléphone uniquement, tant que vous gardez l'application** ; vous pouvez tout effacer à tout moment (voir « Durée de conservation »).

## Données traitées

| Source | Données |
| --- | --- |
| Bracelet Polar Loop (Bluetooth) | Pas, calories, distance, temps actif et sédentaire ; fréquence cardiaque ; intervalles pouls à pouls (variabilité) ; sommeil et ses phases ; température de la peau ; séances d'entraînement ; niveau de batterie, version du firmware, identifiant et nom du bracelet |
| Health Connect (lecture seule, uniquement ce que vous autorisez) | Poids, masse grasse, masse maigre, masse osseuse, eau corporelle, saturation en oxygène, variabilité de la fréquence cardiaque, fréquence cardiaque, pas, sommeil, fréquence respiratoire, séances d'exercice, distance, calories actives, tracés GPS des séances (autorisés séance par séance) |
| GPS du téléphone (uniquement pendant une activité que vous lancez depuis Boucle) | Position, altitude et précision du GPS une fois par seconde ; pression du baromètre pour l'altitude ; pas comptés par le téléphone |
| Profil que vous saisissez | Sexe, date de naissance, taille, poids, fréquence cardiaque maximale |
| Réglages | Bracelet associé, dates des dernières synchronisations, passage de l'écran d'accueil, dossier de sauvegarde automatique et résultat de la dernière sauvegarde, clé des sauvegardes chiffrées (elle-même chiffrée, voir plus bas ; jamais la phrase de passe) |

Les données d'activité de Health Connect ne sont lues que depuis l'application Withings, afin de ne pas compter deux fois les pas du téléphone.

## Position pendant une activité

Quand vous lancez une marche, une course ou une sortie vélo depuis Boucle, l'application lit la position du GPS de votre téléphone pour tracer le parcours et calculer la distance, l'allure ou la vitesse, l'altitude et le dénivelé.

- **Seulement pendant l'activité :** la position n'est lue qu'entre « Démarrer » et « Terminer » (et, avant le départ, tant que l'écran « Nouvelle activité » est ouvert, pour afficher la qualité du signal). Boucle ne suit jamais votre position en dehors d'une activité.
- **Écran éteint :** l'enregistrement continue grâce à un service au premier plan, signalé par une notification permanente tant qu'il tourne.
- **Stockage :** le tracé est enregistré dans l'espace privé de l'application, comme vos autres données. Il n'est envoyé à aucun serveur.
- **Pas du téléphone :** pendant une marche, Boucle peut compter vos pas avec le capteur du téléphone (autorisation « Activité physique »). Refuser cette autorisation n'empêche pas l'enregistrement.
- **Activités détectées :** Boucle peut vous proposer une marche ou une course repérée dans les pas relevés par votre bracelet. Cette détection se fait sur le téléphone, sans position, et se désactive dans l'écran « Activité détectée ».

## À quoi servent ces données

Uniquement à afficher vos mesures et à calculer vos scores (sommeil, récupération, Énergie, VO2max estimé, stress) sur votre téléphone. Elles ne servent ni à la publicité, ni au profilage, ni à des décisions automatisées vous concernant.

L'utilisation par Boucle des informations reçues de Health Connect respecte la [politique relative aux données utilisateur de Google Play](https://support.google.com/googleplay/android-developer/answer/9888076), y compris ses exigences d'utilisation limitée : ces données ne sont ni vendues, ni utilisées pour la publicité, ni transmises à des tiers, et personne chez Boucle ne les consulte.

### Usage de chaque donnée lue dans Health Connect

| Donnée Health Connect | Fonction dans Boucle | Bénéfice pour vous |
| --- | --- | --- |
| Poids (Weight) | Écran Corps : dernière pesée, courbe sur 30 jours, évolution sur 7 et 30 jours | Suivre l'évolution de votre poids mesuré par votre balance |
| Masse grasse (BodyFat) | Écran Corps : carte « Composition corporelle » (pourcentage et barre de répartition) | Voir la part de masse grasse mesurée par votre balance |
| Masse maigre (LeanBodyMass) | Écran Corps : carte « Composition corporelle » | Suivre votre masse maigre à côté du poids |
| Masse osseuse (BoneMass) | Écran Corps : carte « Composition corporelle » | Voir la masse osseuse mesurée par votre balance |
| Eau corporelle (BodyWaterMass) | Écran Corps : carte « Composition corporelle » (masse hydrique) | Suivre votre hydratation mesurée par votre balance |
| Saturation en oxygène (OxygenSaturation) | Écran Corps : carte « SpO2 · nuit » (moyenne et minimum de la nuit) | Consulter la saturation en oxygène nocturne mesurée par votre montre |
| Pas (Steps) | Écrans Aujourd'hui et Activité : pas du jour et de la semaine ; calcul du score Énergie | Suivre votre activité quotidienne quand votre montre remplace le bracelet |
| Distance | Écran Activité : distance du jour et de chaque séance | Connaître la distance parcourue par jour et par séance |

Ces données ne servent à aucune autre fin que ces affichages et calculs, faits sur le téléphone.

## Où elles sont stockées

Dans l'espace privé de l'application sur votre téléphone (base de données locale et préférences), isolé des autres applications par Android. Elles ne sont envoyées à aucun serveur.

La sauvegarde Android dans le cloud est désactivée pour Boucle : vos données ne sont jamais copiées dans un compte Google ni sur Google Drive. Trois moyens, tous locaux, permettent de les conserver :

- **Transfert d'appareil à appareil :** quand vous configurez un nouveau téléphone en le reliant directement à l'ancien (câble ou connexion sans fil directe, pendant la configuration d'Android), la base de données et les réglages de Boucle sont copiés d'un téléphone à l'autre. Aucun serveur n'intervient dans cette copie, ni Google ni Boucle. La clé des sauvegardes chiffrées n'est pas utilisable sur le nouveau téléphone (voir plus bas).
- **Sauvegarde automatique (facultative, désactivée par défaut) :** Boucle > Appareils > Sauvegarde automatique. Vous choisissez un dossier (stockage du téléphone, carte SD, clé USB…) et Boucle y écrit, après chaque synchronisation (avec une vérification quotidienne de secours) et seulement si vos données ont changé, une archive identique à l'export décrit plus bas, nommée `boucle-sauvegarde-AAAAMMJJ-HHMMSS.zip`. Les 7 dernières sont gardées ; Boucle ne supprime dans ce dossier que ses propres sauvegardes plus anciennes, jamais d'autres fichiers.
  - **Chiffrement par phrase de passe (proposé à l'activation, facultatif) :** les sauvegardes sont alors chiffrées sur le téléphone, pendant leur écriture (AES-256-GCM, clé dérivée de la phrase de passe par PBKDF2 ; aucune copie en clair n'est écrite), et nommées `boucle-sauvegarde-AAAAMMJJ-HHMMSS.boucle`. Le format est public et décrit dans [Format des sauvegardes chiffrées](../format-sauvegarde/). Boucle n'enregistre jamais la phrase de passe : pour que la sauvegarde automatique fonctionne sans vous, il garde la clé qui en est dérivée, elle-même chiffrée par une clé du Keystore Android qui ne quitte pas le téléphone. **Sans la phrase de passe, une sauvegarde chiffrée est irrécupérable** : ni Boucle ni personne ne peut la retrouver. Après un transfert vers un nouveau téléphone, il faut ressaisir la phrase de passe ; en attendant, aucune sauvegarde n'est écrite. Changer de phrase de passe ne s'applique qu'aux sauvegardes suivantes ; les précédentes gardent la leur. Boucle n'envoie rien : si le dossier choisi est lui-même synchronisé par une autre application (stockage en ligne, par exemple), cette copie relève de cette application et de sa politique, pas de Boucle. Ces archives ne sont pas protégées par l'espace privé de l'application ; sans chiffrement, quiconque accède au dossier peut les lire. Désactiver la sauvegarde automatique retire à Boucle l'accès au dossier ; les sauvegardes déjà écrites y restent, à vous de les supprimer si vous le souhaitez.
- **Export manuel**, puis import de l'archive dans Boucle sur le nouveau téléphone (voir plus bas).

Si vous désinstallez l'application ou changez de téléphone sans transfert ni sauvegarde, l'historique stocké dans Boucle est perdu : le bracelet est vidé à chaque synchronisation, le téléphone en garde la seule copie.

## Durée de conservation

- **Données de santé (bracelet et Health Connect), profil et réglages :** conservés dans l'espace privé de l'application, sur votre téléphone, **tant que l'application est installée**, pour vous montrer votre historique et calculer vos tendances. Il n'y a pas de suppression automatique : l'historique n'existe qu'à cet endroit (le bracelet est vidé à chaque synchronisation).
- **Effacement :** vous pouvez tout supprimer à tout moment (Réglages Android > Applications > Boucle > Stockage > Effacer les données). Désinstaller l'application supprime définitivement toutes ces données du téléphone. Retirer l'accès à Health Connect arrête toute nouvelle lecture ; pour supprimer aussi les mesures déjà lues, effacez les données de l'application.
- **Sauvegardes automatiques :** les 7 plus récentes sont gardées dans le dossier que vous avez choisi, les plus anciennes sont supprimées par Boucle. Elles restent dans ce dossier, sous votre contrôle, même après désinstallation.
- **Exports manuels :** conservés là où vous les avez enregistrés, jusqu'à ce que vous les supprimiez.
- **Images de carte :** gardées en cache sur le téléphone ; Android peut les effacer à tout moment pour libérer de la place.
- **Côté Boucle :** aucune donnée n'est stockée ni conservée sur un serveur, puisque rien n'est envoyé. Boucle ne conserve donc aucune de vos données en dehors de votre téléphone.

## Partage et services tiers

Boucle ne transmet vos mesures de santé à personne. Elle s'appuie sur :

- **La bibliothèque Polar BLE SDK**, intégrée à l'application, qui dialogue avec votre bracelet en Bluetooth.
- **Health Connect**, composant d'Android fourni par Google. Les données qu'il contient restent soumises à ses propres règles.
- **Les applications des fabricants** (Withings aujourd'hui) qui écrivent vos mesures dans Health Connect. Chacune a sa propre politique de confidentialité : le trajet de vos données entre le fabricant et Health Connect relève d'elle, pas de Boucle.
- **OpenStreetMap et OpenTopoMap**, qui fournissent les images de fond de carte (voir la section suivante).

La permission « Internet » sert à télécharger ces images de carte et, pour la bibliothèque Polar, les mises à jour du firmware du bracelet. En dehors de l'affichage des cartes, Boucle n'envoie aucune donnée.

## Cartes (tracés GPS)

Quand vous ouvrez la carte d'une séance avec un tracé GPS, ou pendant une activité en cours, Boucle télécharge des images de fond de carte depuis OpenStreetMap (`tile.openstreetmap.org`) et, pour le style « Relief », depuis OpenTopoMap (`tile.opentopomap.org`). Ces services reçoivent votre adresse IP et les coordonnées des carrés de carte demandés, ce qui révèle la zone géographique de la séance. Ils ne reçoivent ni vos mesures de santé, ni le tracé lui-même, qui est dessiné sur votre téléphone, ni aucun identifiant de compte.

Les images téléchargées sont gardées en cache sur le téléphone pour limiter ces requêtes. Ces services appliquent leurs propres règles, par exemple la [politique de confidentialité de la fondation OpenStreetMap](https://osmfoundation.org/wiki/Privacy_Policy).

## Consentement et vos droits

Les données de santé sont des données sensibles. Boucle ne les lit qu'avec votre accord, donné au moment où vous accordez les autorisations Bluetooth, Health Connect et, pour lancer une activité, la position. Vous pouvez le retirer à tout moment :

- **Bluetooth :** Réglages Android > Applications > Boucle > Autorisations.
- **Position, activité physique et notifications :** Réglages Android > Applications > Boucle > Autorisations. Sans la position, vous ne pouvez plus lancer d'activité avec tracé depuis Boucle ; le reste de l'application fonctionne.
- **Health Connect :** Réglages > Health Connect > Autorisations des applications > Boucle.
- **Récupérer toutes vos données (droit à la portabilité) :** Boucle > Appareils > Exporter mes données. L'application crée une archive ZIP, enregistrée à l'endroit que vous choisissez, qui contient toutes les données qu'elle garde : un fichier JSON structuré (mesures, sommeil, séances, profil, mesures lues dans Health Connect) et un fichier GPX par tracé GPS, importable dans d'autres applications. Une fois exportée, l'archive n'est plus protégée par l'espace privé de l'application : conservez-la en lieu sûr. Cet export n'est pas chiffré. Boucle > Appareils > Importer une sauvegarde relit cette archive, ou une sauvegarde automatique (`.zip`, ou `.boucle` avec sa phrase de passe), pour restaurer vos données : seules les données absentes du téléphone sont ajoutées, celles déjà présentes sont conservées sans être remplacées. L'import et le déchiffrement se font entièrement sur le téléphone, sans aucun envoi.
- **Supprimer toutes les données de Boucle :** Réglages Android > Applications > Boucle > Stockage > Effacer les données, ou désinstaller l'application.

Boucle n'ayant aucun accès à vos données, elle ne peut ni les consulter, ni les exporter, ni les effacer à votre place : ces opérations se font depuis l'application. Pour toute question, écrivez à l'adresse de contact ci-dessus. Vous pouvez aussi saisir la CNIL (cnil.fr).

## Mineurs

Boucle n'est pas destinée aux personnes de moins de 18 ans.

## Modifications

Si cette politique change, la date de mise à jour ci-dessus change aussi. Les changements importants seront signalés dans l'application.
