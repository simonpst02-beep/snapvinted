# SpyEbay

Appli Android qui cherche des appareils HS sur eBay et calcule la marge après réparation.

## Obtenir l'APK

1. Mets tous ces fichiers dans un dépôt GitHub, sur la branche `main`.
2. GitHub compile tout seul l'APK (onglet **Actions**, environ 3 à 5 minutes).
3. Télécharge `SpyEbay.apk` dans l'onglet **Releases** du dépôt.
4. Installe-le sur ton téléphone (autorise l'installation d'applis inconnues si Android le demande).

Pour relancer une compilation à la main : **Actions → Construire l'APK → Run workflow**.

## Utilisation

- Sans clés, l'appli fonctionne en mode démo, avec de fausses annonces.
- Touche l'icône de clé dans la barre du bas, puis colle ton App ID et ton Cert ID de **production**.
- Tape un appareil et valide : l'appli cherche les annonces « pour pièces », estime le prix de revente
  (médiane des annonces en état de marche) et classe les affaires par marge.

Tes clés restent enregistrées sur le téléphone, et ne sont jamais dans le dépôt.


## Sécurité de la signature Android

Le fichier `app/margeo.keystore` ne doit jamais être versionné. Pour les builds GitHub Actions, configure les secrets `MARGEO_KEYSTORE` (keystore encodé en base64) et `MARGEO_KEYSTORE_PASSWORD`.
