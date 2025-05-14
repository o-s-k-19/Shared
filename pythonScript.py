import json
from pathlib import Path

def filtrer_definitions_json(chemin_fichier: str, cles_a_garder: list[str]) -> dict:
    """
    Garde uniquement les clés spécifiées dans la propriété 'definitions' d'un fichier JSON.

    Args:
        chemin_fichier (str): Le chemin vers le fichier JSON.
        cles_a_garder (list[str]): Liste des clés à conserver dans 'definitions'.

    Returns:
        dict: Le contenu JSON filtré.
    """
    chemin = Path(chemin_fichier)

    if not chemin.exists():
        raise FileNotFoundError(f"Fichier non trouvé : {chemin_fichier}")

    with chemin.open(encoding="utf-8") as fichier:
        contenu = json.load(fichier)

    if "definitions" not in contenu:
        raise KeyError("La clé 'definitions' est absente du fichier JSON.")

    definitions_originales = contenu["definitions"]
    definitions_filtrees = {
        cle: valeur for cle, valeur in definitions_originales.items() if cle in cles_a_garder
    }

    contenu["definitions"] = definitions_filtrees
    return contenu


def sauvegarder_json(donnees: dict, chemin_sortie: str) -> None:
    """
    Sauvegarde un dictionnaire Python en fichier JSON.

    Args:
        donnees (dict): Les données à écrire.
        chemin_sortie (str): Le chemin du fichier de sortie.
    """
    with open(chemin_sortie, "w", encoding="utf-8") as f:
        json.dump(donnees, f, indent=2, ensure_ascii=False)


# Exemple d’utilisation
if __name__ == "__main__":
    cles_a_garder = ["Adresse", "Utilisateur", "Commande"]
    fichier_entree = "mon_fichier.json"
    fichier_sortie = "mon_fichier_filtré.json"

    try:
        json_filtré = filtrer_definitions_json(fichier_entree, cles_a_garder)
        sauvegarder_json(json_filtré, fichier_sortie)
        print(f"Fichier filtré sauvegardé : {fichier_sortie}")
    except Exception as e:
        print(f"Erreur : {e}")