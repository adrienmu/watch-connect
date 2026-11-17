---
title: Politique de confidentialité
permalink: /confidentialite/
---

# Politique de confidentialité de Boucle

Dernière mise à jour : 17 novembre 2026

**Contact :** adrien.mussio@eurl-baam.fr

## En bref

- Vos données de santé restent **sur votre téléphone** : aucune ne passe par un serveur, tout est local. Seule exception, l'affichage d'une carte de parcours GPS (voir « Cartes » ci-dessous).
- Pas de compte, pas de publicité, pas d'analyse d'audience, pas de pistage.
- Boucle ne vend ni ne partage vos données avec qui que ce soit.
- Dans Health Connect, Boucle ne fait que **lire** : elle n'écrit ni ne modifie rien.

## Données traitées

| Source | Données |
| --- | --- |
| Bracelet Polar Loop (Bluetooth) | Pas, calories, distance, temps actif et sédentaire ; fréquence cardiaque ; intervalles pouls à pouls (variabilité) ; sommeil et ses phases ; température de la peau ; séances d'entraînement ; niveau de batterie, version du firmware, identifiant et nom du bracelet |
| Health Connect (lecture seule, uniquement ce que vous autorisez) | Poids, masse grasse, masse maigre, masse osseuse, eau corporelle, saturation en oxygène, variabilité de la fréquence cardiaque, fréquence cardiaque, pas, sommeil, fréquence respiratoire, séances d'exercice, distance, calories actives, tracés GPS des séances (autorisés séance par séance) |
| Profil que vous saisissez | Sexe, date de naissance, taille, poids, fréquence cardiaque maximale |
| Réglages | Bracelet associé, dates des dernières synchronisations, passage de l'écran d'accueil |

Les données d'activité de Health Connect ne sont lues que depuis l'application Withings, afin de ne pas compter deux fois les pas du téléphone.

## À quoi servent ces données

Uniquement à afficher vos mesures et à calculer vos scores (sommeil, récupération, Énergie, VO2max estimé, stress) sur votre téléphone. Elles ne servent ni à la publicité, ni au profilage, ni à des décisions automatisées vous concernant.

L'utilisation par Boucle des informations reçues de Health Connect respecte la [politique relative aux données utilisateur de Google Play](https://support.google.com/googleplay/android-developer/answer/9888076), y compris ses exigences d'utilisation limitée : ces données ne sont ni vendues, ni utilisées pour la publicité, ni transmises à des tiers, et personne chez Boucle ne les consulte.

## Où elles sont stockées

Dans l'espace privé de l'application sur votre téléphone (base de données locale et préférences), isolé des autres applications par Android. Elles ne sont envoyées à aucun serveur.

Les sauvegardes Android sont désactivées pour Boucle : vos données ne sont pas copiées dans un compte Google. Conséquence : si vous changez de téléphone ou désinstallez l'application, l'historique stocké dans Boucle est perdu. Pour le conserver, exportez vos données (voir plus bas) puis importez l'archive dans Boucle sur le nouveau téléphone.

## Partage et services tiers

Boucle ne transmet vos mesures de santé à personne. Elle s'appuie sur :

- **La bibliothèque Polar BLE SDK**, intégrée à l'application, qui dialogue avec votre bracelet en Bluetooth.
- **Health Connect**, composant d'Android fourni par Google. Les données qu'il contient restent soumises à ses propres règles.
- **Les applications des fabricants** (Withings aujourd'hui) qui écrivent vos mesures dans Health Connect. Chacune a sa propre politique de confidentialité : le trajet de vos données entre le fabricant et Health Connect relève d'elle, pas de Boucle.
- **OpenStreetMap et OpenTopoMap**, qui fournissent les images de fond de carte (voir la section suivante).

La permission « Internet » sert à télécharger ces images de carte et, pour la bibliothèque Polar, les mises à jour du firmware du bracelet. En dehors de l'affichage des cartes, Boucle n'envoie aucune donnée.

## Cartes (tracés GPS)

Quand vous ouvrez la carte d'une séance avec un tracé GPS, Boucle télécharge des images de fond de carte depuis OpenStreetMap (`tile.openstreetmap.org`) et, pour le style « Relief », depuis OpenTopoMap (`tile.opentopomap.org`). Ces services reçoivent votre adresse IP et les coordonnées des carrés de carte demandés, ce qui révèle la zone géographique de la séance. Ils ne reçoivent ni vos mesures de santé, ni le tracé lui-même, qui est dessiné sur votre téléphone, ni aucun identifiant de compte.

Les images téléchargées sont gardées en cache sur le téléphone pour limiter ces requêtes. Ces services appliquent leurs propres règles, par exemple la [politique de confidentialité de la fondation OpenStreetMap](https://osmfoundation.org/wiki/Privacy_Policy).

## Consentement et vos droits

Les données de santé sont des données sensibles. Boucle ne les lit qu'avec votre accord, donné au moment où vous accordez les autorisations Bluetooth et Health Connect. Vous pouvez le retirer à tout moment :

- **Bluetooth :** Réglages Android > Applications > Boucle > Autorisations.
- **Health Connect :** Réglages > Health Connect > Autorisations des applications > Boucle.
- **Récupérer toutes vos données (droit à la portabilité) :** Boucle > Appareils > Exporter mes données. L'application crée une archive ZIP, enregistrée à l'endroit que vous choisissez, qui contient toutes les données qu'elle garde : un fichier JSON structuré (mesures, sommeil, séances, profil, mesures lues dans Health Connect) et un fichier GPX par tracé GPS, importable dans d'autres applications. Une fois exportée, l'archive n'est plus protégée par l'espace privé de l'application : conservez-la en lieu sûr. Boucle > Appareils > Importer une sauvegarde relit cette archive pour restaurer vos données ; l'import se fait entièrement sur le téléphone, sans aucun envoi.
- **Supprimer toutes les données de Boucle :** Réglages Android > Applications > Boucle > Stockage > Effacer les données, ou désinstaller l'application.

Boucle n'ayant aucun accès à vos données, elle ne peut ni les consulter, ni les exporter, ni les effacer à votre place : ces opérations se font depuis l'application. Pour toute question, écrivez à l'adresse de contact ci-dessus. Vous pouvez aussi saisir la CNIL (cnil.fr).

## Mineurs

Boucle n'est pas destinée aux personnes de moins de 18 ans.

## Modifications

Si cette politique change, la date de mise à jour ci-dessus change aussi. Les changements importants seront signalés dans l'application.
