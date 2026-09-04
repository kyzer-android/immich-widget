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

## 🎨 Écran de config — dernières évolutions

- **Footer fixe** : l'intervalle auto + le bouton "Enregistrer" sont hors du
  `ScrollView`, donc toujours visibles à l'écran sans avoir à scroller.
- **Intervalle formaté** : affiché en "Xh Ymin" au-delà de 60 min plutôt
  qu'en minutes brutes (ex: "1h 10min" au lieu de "70 min").
- **Thème sombre forcé** (`Theme.Material3.Dark.NoActionBar`, plus
  `DayNight`) avec couleurs de texte explicites — évite le texte peu
  contrasté selon le thème système/constructeur.
- **2 boutons distincts** : "Tester la connexion" et "Charger les albums"
  sont deux actions séparées (au lieu d'un bouton à 2 états).
- **Mode d'affichage crop/fit** (switch dans les params) :
  - *Recadré* (par défaut, coché) : l'image remplit tout le cadre du widget,
    quitte à couper les bords — comportement d'origine.
  - *Image entière* (décoché) : toute la photo est visible, avec des bandes
    si son ratio ne correspond pas à celui du widget.
  - **Important** : le cache ne recadre plus JAMAIS l'image à la synchro
    (juste un redimensionnement qui conserve le cadre complet) — le choix
    crop/fit est appliqué uniquement à l'affichage (2 `ImageView`
    superposées dans le widget, une seule visible à la fois selon le
    réglage). Changer ce réglage prend effet immédiatement sans re-sync.

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

## 🖼️ Qualité des photos

Le client télécharge `size=preview` (source Immich ~1440px, meilleure
qualité que `size=thumbnail` ~250-400px), puis redimensionne à
`MAX_DIMENSION_PX` (500px par défaut) en WebP qualité **90** (`ThumbnailCache.kt`).
Partir d'une source de meilleure qualité évite le double effet de
compression qui donnait un rendu moyen avec `size=thumbnail`.

⚠️ Effet de bord attendu : la **sync initiale** (et toute nouvelle photo
ajoutée à l'album) télécharge un fichier plus lourd depuis le serveur avant
réduction — un peu plus de bande passante et de temps sur un gros album,
mais le poids final en cache reste quasi identique (on redescend toujours
à 500px derrière).

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
