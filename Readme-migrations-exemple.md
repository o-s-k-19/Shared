# 📦 Migration du Framework Custom (v6 → v7 → v1 Nouveau Framework)

## 🎯 Objectif
Migrer la solution existante basée sur **Framework Custom v6** vers :
1. **Framework Custom v7**
2. Puis vers le **Nouveau Framework v1** (basé sur Java + Spring 7)

---

## 📌 Contexte
- **Projet** : [Nom du projet]
- **Structure** : Multi-modules Maven (un module parent + plusieurs modules enfants imbriqués)
- **Stack technique** : Java, Spring, Maven, Nexus
- **Dépendances clés** : Spring, OpenAPI Generator, Swagger Parser, Commons CLI, Commons IO
- **Gestion du build** : Maven + Nexus (proxy Maven Central)

---

## 🗂️ Étapes de migration

### Étape 1 — Migration v6 → v7
- ✅ Mise à jour de la dépendance `parent` vers la version `7.x`
- ✅ Ajout des versions manquantes dans les dépendances enfants (trouvées sur Nexus)
- ✅ Téléchargement réussi de toutes les dépendances
- ⚠️ **Problèmes rencontrés :**
  - `org.openapitool` mal écrit → corrigé en `org.openapitools`
  - `<type>bar</type>` remplacé par `<type>jar</type>`
  - Dépendances manquantes (`swagger`, `commons-io`, `commons-cli`) → ajout des versions correctes depuis Nexus

### Étape 2 — Migration v7 → Nouveau Framework v1
- ⏳ Utiliser la **recette OpenRewrite** fournie par l’équipe du framework custom
- ⏳ Vérifier compatibilité avec **Spring Boot 3.x** + **Jakarta**
- ⏳ Adapter les modules enfants au nouveau modèle
- ⏳ Mettre à jour la configuration CI/CD

---

## 🛠️ Problèmes rencontrés & solutions

| Problème | Cause | Solution |
|----------|-------|----------|
| Erreur `could transfer artifact org.openapitool` | Mauvais `groupId` (`org.openapitool`) | Corrigé en `org.openapitools` |
| Type `bar` au lieu de `jar` | Mauvaise config `<type>` | Supprimé `<type>` → valeur par défaut `jar` |
| Dépendances introuvables (`swagger`, `commons-io`, `commons-cli`) | Versions manquantes dans Nexus | Ajout de versions valides depuis Nexus |

---

## 🔧 Commandes utiles

### Maven
```bash
# Clean + build avec update
mvn -U clean install

# Afficher POM effectif (utile pour debug)
mvn help:effective-pom > effective-pom.xml

# Télécharger un artefact précis depuis Nexus
mvn dependency:get -Dartifact=org.openapitools:openapi-generator:7.6.0 -U
