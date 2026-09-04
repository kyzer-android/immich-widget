# Immich Widget

Widget Android affichant une photo aléatoire d'un album Immich à chaque tap,
avec accès aux paramètres via une petite icône ⚙️ intégrée au widget.

## 🔑 Créer l'API Key Immich

1. Ouvre l'interface web Immich → **Account Settings** → **API Keys**
2. Crée une nouvelle clé (nom libre, ex: "widget-android")
3. Copie-la immédiatement (elle n'est affichée qu'une fois)
4. Colle-la dans l'écran de config du widget, avec l'URL de ton instance
   (ex: `https://photos.maisondrusch.duckdns.org`, **sans** `/api` à la fin)

## 🏗️ Build

Ouvre le dossier `ImmichWidget/` dans Android Studio (Koala ou plus récent) :
- **File → Open** → sélectionner le dossier racine (celui avec `settings.gradle.kts`)
- Android Studio propose de synchroniser le Gradle Wrapper automatiquement
  si le jar wrapper est absent (seul `gradle-wrapper.properties` est fourni ici)
- Compile en Debug (`Build → Make Project`) puis installe sur ton S21 (`Run`)

Aucune dépendance Immich officielle n'est utilisée (pas de SDK Immich) —
uniquement des appels REST bruts via OkHttp, pour rester léger et sans
dépendance à une version précise de leur client officiel.

## 📱 Utilisation

1. Ajoute le widget "Photo Immich aléatoire" depuis le tiroir de widgets
2. Il s'affiche vide au départ → **tape sur l'icône ⚙️** en bas à droite
3. Renseigne URL + API Key → **Tester la connexion**
4. Choisis un album dans la liste → **Enregistrer**
5. Une sync immédiate se lance (peut prendre plusieurs minutes sur un gros
   album, ex: ~2000 photos avec 8 téléchargements en parallèle)
6. Une fois la sync terminée, **tape sur la photo** pour en afficher une nouvelle

## ⏱️ Changement automatique de photo

Dans les params, un champ avec `−` / `+` (pas de 5 min, 0 à 1440) permet de
régler un intervalle de changement auto de la photo affichée, indépendant
du tap manuel. `0` = désactivé (comportement d'origine, changement au tap
uniquement).

Implémenté via `AlarmManager.setAndAllowWhileIdle` (auto-reprogrammé à
chaque tick) plutôt que WorkManager, qui impose un plancher de 15 min entre
deux exécutions périodiques — trop restrictif pour un intervalle de 5-10 min.

⚠️ Sur LineageOS avec restrictions batterie agressives, pense à désactiver
l'optimisation de batterie pour l'app (`Paramètres → Apps → Immich Widget →
Batterie → Non restreinte`), sinon Doze peut retarder les ticks de plusieurs
minutes au-delà de l'intervalle réglé.

## ⚠️ Notes et limitations connues

- **Vérifie les endpoints Immich** : le code cible l'API v1 (`/api/albums`,
  `/api/assets/{id}/thumbnail`). Si ta version d'Immich diffère, vérifie la
  doc Swagger de ton serveur (`/api/docs`) et ajuste `ImmichApiClient.kt`
  si besoin — les noms de champs JSON (`albumName`, `assetCount`, `assets`)
  peuvent varier légèrement entre versions.
- **Long-press impossible sur un widget** (limitation Android, cf. discussion
  précédente) → remplacé par l'icône ⚙️ en coin du widget.
- **`PhotoWidgetProvider` est `exported="true"`** par obligation Android
  (le système envoie les broadcasts d'update depuis l'extérieur de l'app).
  Effet de bord mineur : une autre app pourrait théoriquement broadcaster
  `ACTION_NEXT_PHOTO` pour forcer un changement de photo — impact nul
  côté sécurité/données (aucune info sensible exposée par ce chemin),
  juste une curiosité à connaître.
- **Sync initiale longue sur gros album** : ~2000 photos ÷ 8 téléchargements
  parallèles ≈ plusieurs minutes selon la latence réseau vers ton serveur.
- **Pas de retry automatique immédiat** en cas d'échec partiel : les photos
  manquantes seront simplement retentées au prochain cycle de sync (6h),
  puisqu'absentes du cache elles réapparaissent dans le delta.
