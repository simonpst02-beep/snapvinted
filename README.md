# SnapVinted

Application Android : une photo + un prix, et l'IA (Claude) rédige l'annonce Vinted (titre, description, catégorie, état, mots-clés). Un assistant intégré permet de l'améliorer en discutant.

La publication est **assistée** : l'application enregistre les photos dans la galerie (album SnapVinted), copie la description, puis ouvre Vinted. Il ne reste qu'à ajouter les photos, coller le texte et valider. Rien n'est publié automatiquement, conformément aux conditions d'utilisation de Vinted, qui interdisent les robots.

Application indépendante, non affiliée à Vinted.

## Installation

1. Sur le téléphone, ouvrez l'onglet **Releases** de ce dépôt et téléchargez `SnapVinted.apk`.
2. Ouvrez le fichier. Android demande d'autoriser l'installation depuis le navigateur : acceptez.
3. Dans l'appli, onglet **Réglages**, collez votre clé API Claude (créée sur console.anthropic.com → API Keys) et touchez **Tester la clé**.

## Nouvelle version

Changez le numéro dans le fichier `VERSION` (par exemple `v1.1.0`) et envoyez : GitHub compile l'APK et le publie dans les Releases. Installez-le par-dessus l'ancien, vos annonces et réglages sont conservés.

## Contenu

- `app/src/main/assets/index.html` : l'interface et l'appel à l'IA.
- `app/src/main/java/fr/snapvinted/app/MainActivity.kt` : la partie Android (appareil photo, galerie, appel API, ouverture de Vinted).
- `signing/snapvinted.jks` : la clé de signature de l'APK. **Gardez ce dépôt privé** : quiconque a cette clé peut signer une fausse mise à jour de l'appli.
- `.github/workflows/apk.yml` : la compilation automatique.
