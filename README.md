# watch-connect (Boucle)

Alternative à Google Health pour bracelets connectés : application Android qui lit les données de votre bracelet
(Polar Loop en Bluetooth, d'autres marques via Health Connect) et les analyse sur votre téléphone, sans compte
et sans serveur Boucle.

## Licence

Le code source est publié sous licence [PolyForm Noncommercial 1.0.0](LICENSE) : vous pouvez l'utiliser, le lire,
le modifier et le redistribuer pour tout usage **non commercial** (usage personnel, recherche, étude, projets
associatifs ou publics). Tout usage commercial, y compris l'intégrer à un produit ou à un service payant, est
interdit sans accord écrit de l'auteur.

Ce n'est donc pas une licence « open source » au sens de l'Open Source Initiative : le code est consultable
(« source disponible »), mais son usage commercial est réservé à l'auteur. Pour un accord commercial :
adrien.mussio@eurl-baam.fr

## Confidentialité

Voir la [politique de confidentialité](docs/confidentialite.md) et les [conditions d'utilisation](docs/conditions.md).

## Build de release

1. Générer la clé de signature (une seule fois, à sauvegarder précieusement) :
   `keytool -genkeypair -v -keystore ~/keys/boucle-upload.jks -alias boucle -keyalg RSA -keysize 2048 -validity 10000`
2. Créer `keystore.properties` à la racine (ignoré par git) :
   ```
   storeFile=/Users/<toi>/keys/boucle-upload.jks
   storePassword=...
   keyAlias=boucle
   keyPassword=...
   ```
3. `./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab` (à envoyer sur la Play Console).
   `./gradlew :app:assembleRelease` produit l'APK signé pour un test sur appareil.
