---
title: Format des sauvegardes chiffrées
permalink: /format-sauvegarde/
---

# Format des sauvegardes chiffrées de Boucle (version 1)

La sauvegarde automatique de Boucle peut chiffrer ses archives avec une phrase de passe. Le fichier obtenu,
`boucle-sauvegarde-AAAAMMJJ-HHMMSS.boucle`, contient exactement l'archive ZIP de l'export (`boucle-donnees.json`,
`parcours/*.gpx`, `LISEZMOI.txt`), chiffrée. Cette page suffit pour la déchiffrer sans l'application. Aucun serveur
n'intervient : la phrase de passe n'est connue que de vous, et sans elle le fichier est irrécupérable.

Les sauvegardes en clair (`.zip`) et l'export manuel (« Exporter mes données ») ne sont pas concernés.

## Vue d'ensemble

- Dérivation de la clé : PBKDF2-HMAC-SHA256, sel aléatoire de 16 octets, 600 000 itérations, clé de 32 octets.
- Chiffrement : AES-256-GCM par segments de 64 Kio, étiquette de 16 octets par segment.
- Nonce de chaque segment : un préfixe aléatoire de 8 octets propre au fichier, suivi du numéro du segment.
- Données associées (AAD) de chaque segment : l'en-tête complet, suivi d'un octet « dernier segment ».

Tous les entiers sont non signés, en big-endian.

## En-tête (60 octets)

| Position | Taille | Contenu |
| --- | --- | --- |
| 0 | 9 | `BOUCLEENC` (ASCII), le « magic » |
| 9 | 1 | version du format : `1` |
| 10 | 1 | KDF : `1` = PBKDF2-HMAC-SHA256 |
| 11 | 4 | nombre d'itérations (600 000 pour les fichiers actuels) |
| 15 | 16 | sel |
| 31 | 1 | chiffrement : `1` = AES-256-GCM par segments |
| 32 | 4 | taille d'un segment en clair, `S` (65 536) |
| 36 | 8 | préfixe de nonce, aléatoire pour chaque fichier |
| 44 | 16 | contrôle de clé : les 16 premiers octets de HMAC-SHA256(clé, `BOUCLEENC v1 key check` en ASCII) |

Une version supérieure à 1 vient d'une version plus récente de Boucle et n'est pas lisible avec ces règles.

## Clé

    clé = PBKDF2-HMAC-SHA256(mot de passe = UTF-8(NFC(phrase de passe)), sel, itérations, longueur = 32 octets)

La phrase de passe est normalisée en Unicode NFC avant d'être encodée en UTF-8, pour qu'un caractère accentué donne
la même clé quel que soit le clavier. Le contrôle de clé permet de distinguer une mauvaise phrase de passe (contrôle
différent) d'un fichier endommagé (contrôle correct, mais un segment refusé). Il ne facilite pas une attaque : le
tester coûte une dérivation PBKDF2 complète, comme tester le premier segment.

## Segments

Après l'en-tête viennent les segments chiffrés, numérotés `i = 0, 1, 2…` :

    nonce(i) = préfixe de nonce (8 octets) ‖ i (4 octets)
    AAD(i)   = en-tête (60 octets) ‖ d      où d = 1 pour le dernier segment, 0 sinon
    segment  = AES-256-GCM-chiffrement(clé, nonce(i), AAD(i), clair) ‖ étiquette (16 octets)

Chaque segment clair fait `S` octets, sauf le dernier, qui en fait de 1 à `S` (0 seulement si l'archive est vide,
auquel cas le fichier n'a qu'un segment, réduit à son étiquette). Un segment chiffré fait donc `S + 16` octets,
sauf le dernier ; le dernier segment est celui qui finit le fichier.

Ces règles détectent :

- une modification de n'importe quel octet (étiquette GCM) ;
- une permutation ou un segment pris dans un autre fichier (numéro de segment dans le nonce, en-tête dans l'AAD,
  préfixe de nonce différent pour chaque fichier) ;
- un fichier coupé, même exactement entre deux segments : le segment devenu dernier a été chiffré avec `d = 0` ;
- des octets ajoutés après la fin : le vrai dernier segment, chiffré avec `d = 1`, n'est plus le dernier.

Le préfixe de nonce de 8 octets est tiré au hasard pour chaque fichier ; avec la même clé, deux fichiers n'ont le
même préfixe qu'avec une probabilité négligeable (environ 2⁻⁶⁴ par paire), et le compteur rend chaque nonce unique à
l'intérieur d'un fichier. Un fichier ne peut pas dépasser 2³² segments (256 Tio).

## Déchiffrer sans l'application

Exemple en Python 3 avec le paquet `cryptography` (`pip install cryptography`) :

```python
import hashlib, hmac, struct, sys, unicodedata
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

def dechiffrer(chemin_entree, chemin_sortie, phrase):
    with open(chemin_entree, "rb") as f:
        data = f.read()
    entete = data[:60]
    if entete[:9] != b"BOUCLEENC" or entete[9] != 1 or entete[10] != 1 or entete[31] != 1:
        sys.exit("pas une sauvegarde chiffrée de Boucle (version 1)")
    iterations, = struct.unpack(">I", entete[11:15])
    sel = entete[15:31]
    taille, = struct.unpack(">I", entete[32:36])
    prefixe = entete[36:44]
    cle = hashlib.pbkdf2_hmac("sha256", unicodedata.normalize("NFC", phrase).encode("utf-8"),
                              sel, iterations, 32)
    controle = hmac.new(cle, b"BOUCLEENC v1 key check", hashlib.sha256).digest()[:16]
    if not hmac.compare_digest(controle, entete[44:60]):
        sys.exit("phrase de passe incorrecte")
    aes, corps, i, morceaux = AESGCM(cle), data[60:], 0, []
    pas = taille + 16
    while True:
        segment = corps[i * pas:(i + 1) * pas]
        dernier = (i + 1) * pas >= len(corps)
        nonce = prefixe + struct.pack(">I", i)
        morceaux.append(aes.decrypt(nonce, segment, entete + bytes([1 if dernier else 0])))
        if dernier:
            break
        i += 1
    with open(chemin_sortie, "wb") as f:
        f.write(b"".join(morceaux))

dechiffrer(sys.argv[1], sys.argv[2], input("Phrase de passe : "))
```

`python3 dechiffrer.py boucle-sauvegarde-20261117-180412.boucle sauvegarde.zip` produit l'archive ZIP, dont le
contenu est décrit dans son fichier `LISEZMOI.txt`. Une exception `InvalidTag` signale un fichier endommagé ou
incomplet. (Ce script lit tout le fichier en mémoire, pour rester court ; l'application, elle, déchiffre en flux.)

## Clé de la sauvegarde automatique

La sauvegarde automatique tourne sans vous ; elle ne peut pas vous demander la phrase de passe à chaque fois. Boucle
n'enregistre **jamais** la phrase de passe. Il garde seulement la clé qui en est dérivée (avec son sel et ses
itérations), elle-même chiffrée en AES-256-GCM par une clé du Keystore Android, créée sur le téléphone et non
exportable. Cette clé du Keystore ne quitte pas le téléphone, même lors d'un transfert d'appareil à appareil : sur un
nouveau téléphone, Boucle demande de ressaisir la phrase de passe, et n'écrit aucune sauvegarde en attendant (ni
chiffrée, ni en clair).

Changer de phrase de passe ne touche pas aux fichiers déjà écrits : chacun garde la phrase de passe (et le sel) avec
lesquels il a été chiffré. Pour importer une ancienne sauvegarde, il faut sa phrase de passe d'origine.
