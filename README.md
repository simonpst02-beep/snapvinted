# SpyEbay

Application Android qui surveille les annonces eBay « Pour pièces ou ne fonctionne pas » des modèles que vous rachetez, filtre les mauvaises affaires et classe le reste par **bénéfice estimé**.

Application indépendante, non affiliée à eBay. Elle utilise l'**API officielle d'eBay** (Browse API) : pas d'aspiration de pages. C'est vous qui ouvrez les annonces et qui achetez.

## Fonctionnement

- **Recherches** : un modèle par recherche (mots-clés eBay, prix maximum, prix de revente une fois réparé, coût des pièces).
- **Filtres** : mots exclus dans le titre (iCloud, bloqué, oxydé…), bénéfice minimum, frais de revente, note minimale du vendeur, vendeurs de l'Union européenne seulement.
- **Annonces** : liste unique triée par bénéfice (ou par prix, ou récentes), nouvelles annonces signalées, nombre d'annonces écartées et pourquoi. Un appui ouvre l'annonce dans eBay.
- **Surveillance automatique** : toutes les 15 min à 3 h, notification quand une nouvelle annonce passe tous les filtres.

Bénéfice estimé = prix de revente − (prix + livraison) − pièces − frais de revente.

## Installation

1. Sur le téléphone, ouvrez l'onglet **Releases** de ce dépôt et installez `SpyEbay.apk` (Android 10 ou plus). Il s'installe par-dessus SnapVinted.
2. Créez des clés eBay gratuites : developer.ebay.com → **Application Keys** → jeu de clés **Production**. Collez l'App ID et le Cert ID dans Réglages → **Clés eBay**, puis **Tester les clés**.
3. Ajoutez vos recherches, puis **Scanner eBay**.

## Mise à jour

Réglages → **Rechercher une mise à jour**. Le dépôt étant privé, l'appli a besoin d'un jeton GitHub en lecture seule (secret `GH_UPDATE_TOKEN`). Sans lui, le bouton ouvre la page des versions.

Pour publier une version : changez le numéro dans `VERSION` et envoyez. GitHub compile et publie l'APK.

## Contenu

- `app/src/main/assets/index.html` : l'interface.
- `app/src/main/java/fr/snapvinted/app/Ebay.kt` : appel à l'API eBay, filtres, surveillance en arrière-plan et notifications.
- `app/src/main/java/fr/snapvinted/app/MainActivity.kt` : liaison avec Android, mise à jour.
- `signing/snapvinted.jks` : clé de signature. **Gardez ce dépôt privé.**

L'identifiant interne `fr.snapvinted.app` est conservé pour que l'appli s'installe par-dessus l'ancienne version.
