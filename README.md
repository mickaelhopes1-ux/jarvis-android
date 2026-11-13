# J.A.R.V.I.S. Mobile Agent - Projet Android Natif (Kotlin + Jetpack Compose)

Projet Android natif pour smartphone **OPPO A74s 5G** (Android 12+, API 31).

## 🚀 Compilation Automatique dans le Cloud (Sans Ordinateur)

1. Déposez ce dossier sur votre compte **GitHub** (dans un nouveau dépôt, par exemple `jarvis-android`).
2. Le workflow automatique `.github/workflows/build-apk.yml` démarre immédiatement.
3. Rendez-vous dans l'onglet **Actions** de votre dépôt GitHub depuis votre smartphone.
4. Cliquez sur le build terminé et téléchargez le fichier **`jarvis-oppo-a74s-debug-apk`**.
5. Ouvrez le fichier sur votre OPPO A74s 5G et installez l'application en 1 clic !

## 📁 Structure du Projet
- `gradlew` / `gradlew.bat` : Scripts Gradle Wrapper exécutables
- `gradle/wrapper/` : Contient `gradle-wrapper.jar` et `gradle-wrapper.properties` (Gradle 8.7)
- `gradle/libs.versions.toml` : Catalogue de versions Android Gradle Plugin & Jetpack Compose
- `.github/workflows/build-apk.yml` : Workflow Cloud CI/CD
- `app/src/main/AndroidManifest.xml` : Permissions matérielles (Appels, SMS, Caméra, Torche, Vibreur, Audio)
- `app/src/main/java/com/stark/jarvis/` : Code source Kotlin Jetpack Compose
